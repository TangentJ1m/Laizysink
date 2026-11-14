"""Execution for the plan -> research branches -> evidence merge DAG.

The wire models currently expose high-level nodes (`WorkNode`) and their
low-level work items (`WorkTask`). The planner's local IDs are translated to
runtime node IDs here. Since the current TaskGraph contract has no serialized
execution-state field, resumable query/answer state is kept in `_task_runs`
for the lifetime of this backend process.
"""

import asyncio
import inspect
import logging
import re
from dataclasses import dataclass, field
from uuid import uuid4

from pydantic import ValidationError

from exa_client import ExaConfigurationError, search
from memory_store import memory_store
from models import (
    DataBarrier,
    Event,
    EventType,
    ExaSource,
    FreshnessTag,
    NodeStatus,
    TaskGraph,
    TemporalMemoryItem,
    WorkNode,
    WorkTask,
    WorkTaskType,
)
from planner import plan


logger = logging.getLogger(__name__)
BRANCH_TIMEOUT_SECONDS = 8.0
MAX_SUBTASKS = 6
MAX_INPUTS = 5
PLACEHOLDER_OPTIONS = {
    "provide a specific value",
    "share a flexible range",
    "other (type details)",
}
_CRITERIA_STOPWORDS = {
    "and", "any", "are", "for", "from", "into", "its", "match", "that", "the", "their", "this", "to", "with",
}
_SEARCH_HAS_TIMEOUT_ARGUMENT = "timeout_seconds" in inspect.signature(search).parameters


@dataclass(frozen=True)
class SubtaskSpec:
    plan_id: str
    title: str
    goal: str
    query: str
    depends_on: tuple[str, ...]
    required_input_ids: tuple[str, ...]
    comparison_criteria: tuple[str, ...]
    dependency_error: str | None = None


@dataclass(frozen=True)
class InputSpec:
    plan_id: str
    key: str
    question: str
    options: tuple[str, ...]
    required: bool
    blocks_subtask_ids: tuple[str, ...]


@dataclass
class BranchOutcome:
    status: NodeStatus
    sources: list[ExaSource] = field(default_factory=list)
    error: str | None = None
    retryable: bool = False
    attempts: int = 1
    malformed_source_count: int = 0


@dataclass
class TaskRun:
    task_id: str
    prompt: str
    specs: list[SubtaskSpec]
    input_specs: dict[str, InputSpec]
    plan_criteria: tuple[str, ...]
    planning_mode: str | None
    planning_error: str | None
    memory: list[TemporalMemoryItem]
    node_ids: dict[str, str]
    search_work_task_ids: dict[str, str]
    barrier_work_task_ids: dict[tuple[str, str], str]
    merge_node_id: str
    on_event: object | None = None
    answers: dict[str, str] = field(default_factory=dict)
    outcomes: dict[str, BranchOutcome] = field(default_factory=dict)
    runtime_statuses: dict[str, NodeStatus] = field(default_factory=dict)
    barrier_ids: dict[str, str] = field(default_factory=dict)
    last_node_payloads: dict[str, dict] = field(default_factory=dict)
    event_lock: asyncio.Lock = field(default_factory=asyncio.Lock)

    @property
    def spec_by_id(self) -> dict[str, SubtaskSpec]:
        return {spec.plan_id: spec for spec in self.specs}

    @property
    def input_blockers(self) -> dict[str, set[str]]:
        """Map required plan inputs to their affected subtask IDs."""
        blockers = {input_id: set(item.blocks_subtask_ids) for input_id, item in self.input_specs.items()}
        for spec in self.specs:
            for input_id in spec.required_input_ids:
                if input_id in blockers and self.input_specs[input_id].required:
                    blockers[input_id].add(spec.plan_id)
        return blockers


# This state enables precise in-process barrier resumption without re-planning
# or rerunning already completed Exa branches. A future API/model change should
# serialize this state if recovery across backend restarts is required.
_task_runs: dict[str, TaskRun] = {}


def _as_string_list(value, *, max_count: int, max_length: int, allow_empty: bool = False) -> tuple[str, ...]:
    if not isinstance(value, list):
        return ()
    items: list[str] = []
    for item in value[:max_count]:
        if not isinstance(item, str):
            continue
        cleaned = item.strip()[:max_length]
        if cleaned or allow_empty:
            items.append(cleaned)
    return tuple(dict.fromkeys(items))


def _normalize_plan(raw_plan) -> tuple[list[SubtaskSpec], dict[str, InputSpec], tuple[str, ...], str | None, str | None]:
    """Accept the structured planner contract and the previous list contract."""
    if isinstance(raw_plan, list):
        # Transitional compatibility with the old planner. It supplied no
        # requirement metadata, so no travel-specific barrier is guessed here.
        raw_subtasks = raw_plan
        raw_inputs = []
        plan_criteria = ()
        planning_mode = None
        planning_error = None
    elif isinstance(raw_plan, dict):
        raw_subtasks = raw_plan.get("subtasks", [])
        raw_inputs = raw_plan.get("required_inputs", [])
        plan_criteria = _as_string_list(raw_plan.get("comparison_criteria"), max_count=6, max_length=160)
        planning_mode = raw_plan.get("planning_mode") if isinstance(raw_plan.get("planning_mode"), str) else None
        planning_error = raw_plan.get("planning_error") if isinstance(raw_plan.get("planning_error"), str) else None
    else:
        raw_subtasks, raw_inputs, plan_criteria = [], [], ()
        planning_mode, planning_error = None, "Planner returned an unsupported plan shape."

    if not isinstance(raw_subtasks, list):
        raw_subtasks = []
    if not isinstance(raw_inputs, list):
        raw_inputs = []

    specs: list[SubtaskSpec] = []
    used_ids: set[str] = set()
    raw_id_map: dict[str, str] = {}
    for index, raw in enumerate(raw_subtasks[:MAX_SUBTASKS]):
        if not isinstance(raw, dict):
            continue
        original_id = raw.get("id")
        plan_id = str(original_id).strip() if original_id is not None else f"subtask_{index + 1}"
        plan_id = plan_id[:80] or f"subtask_{index + 1}"
        if plan_id in used_ids:
            plan_id = f"{plan_id}_{index + 1}"
        used_ids.add(plan_id)
        if original_id is not None:
            raw_id_map[str(original_id)] = plan_id

        title = raw.get("title") if isinstance(raw.get("title"), str) else ""
        query = raw.get("query") if isinstance(raw.get("query"), str) else ""
        goal = raw.get("goal") if isinstance(raw.get("goal"), str) else title
        if not title.strip() or not query.strip():
            continue
        specs.append(SubtaskSpec(
            plan_id=plan_id,
            title=title.strip()[:80],
            goal=goal.strip()[:240],
            query=query.strip()[:1000],
            depends_on=_as_string_list(raw.get("depends_on"), max_count=MAX_SUBTASKS, max_length=80),
            required_input_ids=_as_string_list(raw.get("required_input_ids"), max_count=MAX_INPUTS, max_length=80),
            comparison_criteria=_as_string_list(raw.get("comparison_criteria"), max_count=6, max_length=160),
        ))

    # Normalize planner-local references only after all IDs have been collected.
    normalized_specs: list[SubtaskSpec] = []
    valid_ids = {spec.plan_id for spec in specs}
    for spec in specs:
        normalized_specs.append(SubtaskSpec(
            plan_id=spec.plan_id,
            title=spec.title,
            goal=spec.goal,
            query=spec.query,
            depends_on=tuple(
                mapped for source_id in spec.depends_on
                if (mapped := raw_id_map.get(source_id, source_id)) in valid_ids
            ),
            required_input_ids=spec.required_input_ids,
            comparison_criteria=spec.comparison_criteria,
            dependency_error=spec.dependency_error,
        ))

    by_id = {spec.plan_id: spec for spec in normalized_specs}
    visit_state: dict[str, int] = {}
    path: list[str] = []
    cycle_ids: set[str] = set()

    def visit(plan_id: str) -> None:
        state = visit_state.get(plan_id, 0)
        if state == 2:
            return
        if state == 1:
            if plan_id in path:
                cycle_ids.update(path[path.index(plan_id):])
            return
        visit_state[plan_id] = 1
        path.append(plan_id)
        for dependency in by_id[plan_id].depends_on:
            visit(dependency)
        path.pop()
        visit_state[plan_id] = 2

    for plan_id in by_id:
        visit(plan_id)
    if cycle_ids:
        normalized_specs = [SubtaskSpec(
            plan_id=spec.plan_id,
            title=spec.title,
            goal=spec.goal,
            query=spec.query,
            depends_on=spec.depends_on,
            required_input_ids=spec.required_input_ids,
            comparison_criteria=spec.comparison_criteria,
            dependency_error="This branch belongs to a cyclic dependency set and was not run.",
        ) if spec.plan_id in cycle_ids else spec for spec in normalized_specs]

    inputs: dict[str, InputSpec] = {}
    for index, raw in enumerate(raw_inputs[:MAX_INPUTS]):
        if not isinstance(raw, dict):
            continue
        original_id = raw.get("id")
        input_id = str(original_id).strip() if original_id is not None else f"input_{index + 1}"
        input_id = input_id[:80] or f"input_{index + 1}"
        if input_id in inputs:
            input_id = f"{input_id}_{index + 1}"
        key = raw.get("key") if isinstance(raw.get("key"), str) else input_id
        question = raw.get("question") if isinstance(raw.get("question"), str) else "Please provide the missing information:"
        options = _as_string_list(raw.get("options"), max_count=4, max_length=100)
        raw_blocks = _as_string_list(raw.get("blocks_subtask_ids"), max_count=MAX_SUBTASKS, max_length=80)
        blocks = tuple(mapped for source_id in raw_blocks if (mapped := raw_id_map.get(source_id, source_id)) in valid_ids)
        inputs[input_id] = InputSpec(
            plan_id=input_id,
            key=key.strip()[:100] or input_id,
            question=question.strip()[:240] or "Please provide the missing information:",
            options=options,
            required=raw.get("required") is not False,
            blocks_subtask_ids=blocks,
        )

    # Drop references to inputs the planner omitted or marked as optional.
    normalized_specs = [SubtaskSpec(
        plan_id=spec.plan_id,
        title=spec.title,
        goal=spec.goal,
        query=spec.query,
        depends_on=spec.depends_on,
        required_input_ids=tuple(
            input_id for input_id in spec.required_input_ids
            if input_id in inputs and inputs[input_id].required
        ),
        comparison_criteria=spec.comparison_criteria,
        dependency_error=spec.dependency_error,
    ) for spec in normalized_specs]

    if not normalized_specs:
        # If planner metadata is malformed, still make a real Exa request for
        # the user's prompt and surface the fallback mode in the audit node.
        normalized_specs = [SubtaskSpec(
            plan_id="request",
            title="Research the request",
            goal="Find live evidence relevant to the user's request.",
            query="",
            depends_on=(),
            required_input_ids=(),
            comparison_criteria=(),
        )]
        planning_mode = planning_mode or "deterministic_fallback"
        planning_error = planning_error or "Planner returned no usable subtasks; used the original request as the Exa query."

    return normalized_specs, inputs, plan_criteria, planning_mode, planning_error


def _options_for(item: InputSpec) -> list[str]:
    if 3 <= len(item.options) <= 4:
        return list(item.options)
    # The planner intentionally does not invent choices before live evidence is
    # available. These are response modes requiring user details, not example
    # values or claims about the task.
    return ["Provide a specific value", "Share a flexible range", "Other (type details)"]


def _unresolved_inputs(run: TaskRun, plan_id: str) -> list[str]:
    blockers = run.input_blockers
    return [
        input_id for input_id, affected in blockers.items()
        if run.input_specs[input_id].required and plan_id in affected and input_id not in run.answers
    ]


def _waiting_on_required_input(run: TaskRun, plan_id: str, seen: set[str] | None = None) -> bool:
    """Return whether this branch has a prerequisite held by an open barrier."""
    if _unresolved_inputs(run, plan_id):
        return True
    if seen is None:
        seen = set()
    if plan_id in seen:
        return False
    seen.add(plan_id)
    spec = run.spec_by_id.get(plan_id)
    if spec is None:
        return False
    return any(
        dependency not in run.outcomes and _waiting_on_required_input(run, dependency, seen)
        for dependency in spec.depends_on
    )


def _barriers(run: TaskRun) -> list[DataBarrier]:
    result: list[DataBarrier] = []
    blockers = run.input_blockers
    for input_id, item in run.input_specs.items():
        affected = sorted(
            plan_id for plan_id in blockers.get(input_id, set())
            if plan_id not in run.outcomes
            and not run.spec_by_id[plan_id].dependency_error
            and not any(
                dependency in run.outcomes and run.outcomes[dependency].status == NodeStatus.FAILED
                for dependency in run.spec_by_id[plan_id].depends_on
            )
        )
        if not item.required or input_id in run.answers or not affected:
            continue
        barrier_id = run.barrier_ids.setdefault(input_id, f"bar_{uuid4().hex[:8]}")
        result.append(DataBarrier(
            barrier_id=barrier_id,
            task_id=run.task_id,
            # The current DataBarrier contract has one source node; the
            # complete affected set remains in this in-process run state.
            source_node_id=run.node_ids[affected[0]],
            badge_text=f"Input needed - {item.key}",
            question=item.question,
            options=_options_for(item),
            allow_other_voice_text=True,
        ))
    return result


def _branch_sources(run: TaskRun, spec: SubtaskSpec) -> tuple[list[ExaSource], int]:
    outcome = run.outcomes.get(spec.plan_id)
    if outcome is None:
        return [], 0
    criteria = spec.comparison_criteria or run.plan_criteria
    criteria_terms = {
        term
        for criterion in criteria
        for term in re.findall(r"\b[a-z0-9]{3,}\b", criterion.casefold())
        if term not in _CRITERIA_STOPWORDS
    }
    scored: list[tuple[ExaSource, int]] = []
    for source in outcome.sources:
        highlight_terms = set(re.findall(r"\b[a-z0-9]{3,}\b", source.highlighted_quote.casefold()))
        score = len(criteria_terms & highlight_terms)
        scored.append((source, score))
    best = max((score for _, score in scored), default=0)
    sources: list[ExaSource] = []
    selected_count = 0
    for source, score in scored:
        selected = best > 0 and score == best
        selected_count += int(selected)
        rejection_reason = None
        if best > 0 and score < best:
            rejection_reason = (
                f"The highlighted quote matched {score} planned comparison term(s), "
                f"fewer than the leading citation's {best}."
            )
        sources.append(source.model_copy(update={"selected": selected, "rejection_reason": rejection_reason}))
    return sources, selected_count


def _node_status(run: TaskRun, spec: SubtaskSpec) -> NodeStatus:
    outcome = run.outcomes.get(spec.plan_id)
    if outcome:
        return outcome.status
    failed_dependencies = [
        dependency for dependency in spec.depends_on
        if dependency in run.outcomes and run.outcomes[dependency].status == NodeStatus.FAILED
    ]
    if failed_dependencies:
        return NodeStatus.FAILED
    if _unresolved_inputs(run, spec.plan_id):
        return NodeStatus.BLOCKED_USER
    if any(dependency not in run.outcomes for dependency in spec.depends_on):
        return NodeStatus.WAITING
    return run.runtime_statuses.get(spec.plan_id, NodeStatus.WAITING)


def _node_summary(run: TaskRun, spec: SubtaskSpec, status: NodeStatus, sources: list[ExaSource]) -> tuple[str, str]:
    outcome = run.outcomes.get(spec.plan_id)
    if outcome and status == NodeStatus.FAILED:
        retry_note = "Retry may help." if outcome.retryable else "Retry requires a configuration or plan change."
        details = f" {retry_note} Attempt {outcome.attempts}."
        summary = f"{outcome.error or 'This research branch failed.'}{details}"
        return summary, summary
    if outcome and status == NodeStatus.COMPLETED:
        _, selected_count = _branch_sources(run, spec)
        if selected_count:
            rationale = (
                f"Selected {selected_count} citation(s) whose exact Exa highlights contain the most planned "
                "comparison terms. This ranks evidence text only; it does not establish a winning option."
            )
        else:
            rationale = (
                "No source was selected because no planned comparison term appeared in the Exa highlights. "
                "All usable citations remain available as evidence."
            )
        if outcome.malformed_source_count:
            rationale += f" Ignored {outcome.malformed_source_count} malformed source result(s)."
        return f"Found {len(sources)} usable live Exa source(s) for this subtask.", rationale
    unresolved = _unresolved_inputs(run, spec.plan_id)
    if unresolved:
        labels = ", ".join(run.input_specs[item_id].key for item_id in unresolved)
        return f"Waiting for required user input: {labels}.", "This branch will resume after its required input is answered."
    failed_dependencies = [
        dependency for dependency in spec.depends_on
        if dependency in run.outcomes and run.outcomes[dependency].status == NodeStatus.FAILED
    ]
    if failed_dependencies:
        summary = "A required prerequisite branch failed; this branch was not run."
        return summary, summary
    if any(dependency not in run.outcomes for dependency in spec.depends_on):
        return "Waiting for prerequisite research branches.", "This branch runs only after its declared dependencies complete."
    if status == NodeStatus.RUNNING:
        return "Searching live sources with Exa.", "The branch is running with its plan query and an eight-second limit."
    return "Waiting to start.", "The branch is scheduled when its dependencies and required inputs are ready."


def _build_graph(run: TaskRun) -> TaskGraph:
    barriers = _barriers(run)
    blockers = run.input_blockers
    nodes: list[WorkNode] = []
    selected_for_merge: list[ExaSource] = []
    successful = 0
    failed = 0
    active = 0

    for spec in run.specs:
        status = _node_status(run, spec)
        if status == NodeStatus.COMPLETED:
            successful += 1
        elif status == NodeStatus.FAILED:
            failed += 1
        elif status in (NodeStatus.RUNNING, NodeStatus.WAITING, NodeStatus.BLOCKED_USER):
            active += 1

        sources, _ = _branch_sources(run, spec)
        selected_for_merge.extend(sources)
        summary, rationale = _node_summary(run, spec, status, sources)
        context = {item.key: item.value for item in run.memory}
        context.update({run.input_specs[input_id].key: answer for input_id, answer in run.answers.items()})

        work_tasks: list[WorkTask] = [WorkTask(
            id=run.search_work_task_ids[spec.plan_id],
            parent_node_id=run.node_ids[spec.plan_id],
            task_type=WorkTaskType.EXA_SEARCH,
            label=f"Exa: {spec.title}",
            status=status if status in (NodeStatus.RUNNING, NodeStatus.COMPLETED, NodeStatus.FAILED) else NodeStatus.WAITING,
        )]
        for input_id, affected in blockers.items():
            if spec.plan_id not in affected:
                continue
            input_status = NodeStatus.COMPLETED if input_id in run.answers else NodeStatus.BLOCKED_USER
            work_tasks.append(WorkTask(
                id=run.barrier_work_task_ids[(spec.plan_id, input_id)],
                parent_node_id=run.node_ids[spec.plan_id],
                task_type=WorkTaskType.DATA_BARRIER,
                label=f"Input: {run.input_specs[input_id].key}",
                status=input_status,
            ))

        nodes.append(WorkNode(
            id=run.node_ids[spec.plan_id],
            task_id=run.task_id,
            title=spec.title,
            status=status,
            freshness_tag=FreshnessTag.USED,
            parent_ids=[] if spec.dependency_error else [run.node_ids[dependency] for dependency in spec.depends_on],
            plain_summary=summary,
            highlight_chips=[f"{len(sources)} live source(s)"] if sources else [],
            inherited_context=context,
            decision_rationale=rationale,
            work_tasks=work_tasks,
            sources=sources,
        ))

    unresolved_barriers = bool(barriers)
    if unresolved_barriers:
        merge_status = NodeStatus.BLOCKED_USER
        graph_status = NodeStatus.BLOCKED_USER
    elif active or successful + failed < len(run.specs):
        merge_status = NodeStatus.RUNNING if any(value == NodeStatus.RUNNING for value in run.runtime_statuses.values()) else NodeStatus.WAITING
        graph_status = merge_status
    elif failed:
        merge_status = NodeStatus.FAILED
        graph_status = NodeStatus.FAILED
    else:
        merge_status = NodeStatus.COMPLETED
        graph_status = NodeStatus.COMPLETED

    source_count = len(selected_for_merge)
    matched_count = sum(source.selected for source in selected_for_merge)
    total_criteria = sum(len(spec.comparison_criteria or run.plan_criteria) for spec in run.specs)
    if source_count:
        merge_summary = (
            f"Merged {source_count} Exa citation(s) across {successful} completed subtask(s). "
            f"{matched_count} citation(s) matched planned comparison terms in exact highlights."
        )
    else:
        merge_summary = "No evidence was available to merge yet."
    if failed:
        merge_summary += f" {failed} subtask(s) failed; available partial citations remain attached to their branches."
    if total_criteria == 0:
        merge_rationale = "No comparison criteria were supplied by the plan; no source was selected or rejected by rank."
    else:
        merge_rationale = (
            "Citation leads are marked only when exact Exa highlight text contains planned comparison terms. "
            "A citation lead does not claim that a product, route, or option won. All other usable citations remain alternatives."
        )
    if run.planning_mode:
        merge_rationale += f" Planning mode: {run.planning_mode}."
    if run.planning_error:
        merge_rationale += f" Planner fallback detail: {run.planning_error}"
    if unresolved_barriers:
        merge_summary += f" {len(barriers)} required input barrier(s) remain open."
        merge_rationale += " Only branches that depend on unanswered required inputs are held."
    if failed and merge_status == NodeStatus.FAILED:
        merge_rationale += " The graph remains failed until every required research branch completes successfully."

    merge_work_status = merge_status
    nodes.append(WorkNode(
        id=run.merge_node_id,
        task_id=run.task_id,
        title="Evidence comparison and merge",
        status=merge_status,
        freshness_tag=FreshnessTag.USED,
        parent_ids=[run.node_ids[spec.plan_id] for spec in run.specs],
        plain_summary=merge_summary,
        highlight_chips=[f"{source_count} citation(s)", f"{failed} failed branch(es)"],
        inherited_context={},
        decision_rationale=merge_rationale,
        work_tasks=[WorkTask(
            id=f"wt_merge_{run.task_id.removeprefix('task_')}",
            parent_node_id=run.merge_node_id,
            task_type=WorkTaskType.MERGE,
            label="Evidence-backed source comparison",
            status=merge_work_status,
        )],
        sources=selected_for_merge,
    ))

    return TaskGraph(
        id=run.task_id,
        prompt=run.prompt,
        status=graph_status,
        nodes=nodes,
        barriers=barriers,
        memory=run.memory,
    )


async def _emit(run: TaskRun, event: Event) -> None:
    if run.on_event is None:
        return
    async with run.event_lock:
        await run.on_event(event)


async def _publish_state(run: TaskRun, *, include_barriers: bool = True) -> TaskGraph:
    graph = _build_graph(run)
    for node in graph.nodes:
        payload = node.model_dump(mode="json")
        if run.last_node_payloads.get(node.id) != payload:
            await _emit(run, Event(type=EventType.NODE_UPDATED, task_id=run.task_id, payload={"node": payload}))
            run.last_node_payloads[node.id] = payload
    if include_barriers:
        for barrier in graph.barriers:
            await _emit(run, Event(type=EventType.DATA_BARRIER, task_id=run.task_id, payload={"barrier": barrier.model_dump(mode="json")}))
    if graph.status in (NodeStatus.COMPLETED, NodeStatus.FAILED):
        await _emit(run, Event(type=EventType.TASK_COMPLETED, task_id=run.task_id, payload={"task_graph": graph.model_dump(mode="json")}))
    return graph


async def _search(query: str) -> list[dict]:
    if _SEARCH_HAS_TIMEOUT_ARGUMENT:
        return await search(query, timeout_seconds=BRANCH_TIMEOUT_SECONDS)
    # Compatibility with a checkout running the previous Exa client. Once the
    # updated client is integrated, its own timeout is used without nesting.
    return await asyncio.wait_for(search(query), timeout=BRANCH_TIMEOUT_SECONDS)


def _safe_exa_error(exc: Exception) -> tuple[str, bool]:
    if exc.__class__.__name__ == "ExaSearchError":
        message = getattr(exc, "message", None)
        kind = getattr(exc, "kind", None)
        if not isinstance(message, str) or not message.strip():
            message = str(exc)
        safe_message = message if isinstance(message, str) and message.strip() else f"Live Exa search failed ({kind or 'provider error'})."
        retryable = getattr(exc, "retryable", False) is True
        return safe_message, retryable
    return f"Exa search failed ({type(exc).__name__}). Check backend provider configuration.", False


def _query_for(run: TaskRun, spec: SubtaskSpec) -> str:
    query = spec.query or run.prompt
    affected_inputs = [input_id for input_id, affected in run.input_blockers.items() if spec.plan_id in affected]
    constraints = [
        f"{run.input_specs[input_id].key}: {run.answers[input_id]}"
        for input_id in affected_inputs
        if input_id in run.answers
    ]
    if constraints:
        query += "\n\nUser-provided constraints: " + "; ".join(constraints)
    return query


async def _run_branch(run: TaskRun, spec: SubtaskSpec) -> None:
    query = _query_for(run, spec)
    try:
        raw_sources = await _search(query)
    except asyncio.TimeoutError:
        run.outcomes[spec.plan_id] = BranchOutcome(
            status=NodeStatus.FAILED,
            error=f"Exa search timed out after {BRANCH_TIMEOUT_SECONDS:g} seconds.",
            retryable=True,
        )
        return
    except ExaConfigurationError:
        run.outcomes[spec.plan_id] = BranchOutcome(
            status=NodeStatus.FAILED,
            error="Live Exa research unavailable: EXA_API_KEY is not configured.",
            retryable=False,
        )
        return
    except Exception as exc:
        message, retryable = _safe_exa_error(exc)
        logger.warning("Exa branch failed with %s", type(exc).__name__)
        run.outcomes[spec.plan_id] = BranchOutcome(
            status=NodeStatus.FAILED,
            error=message,
            retryable=retryable,
        )
        return

    valid_sources: list[ExaSource] = []
    malformed_count = 0
    for index, raw in enumerate(raw_sources or []):
        try:
            source = ExaSource.model_validate(raw)
            if not source.highlighted_quote.strip():
                malformed_count += 1
                continue
            # Exa IDs are only unique within an individual search response.
            # Prefix at the DAG boundary so citations remain distinct across
            # parallel branches without altering their source content.
            source = source.model_copy(update={
                "id": f"{run.node_ids[spec.plan_id]}_{index}_{source.id}",
                "selected": False,
                "rejection_reason": None,
            })
            valid_sources.append(source)
        except (ValidationError, TypeError, ValueError):
            malformed_count += 1

    if not valid_sources:
        error = "Exa returned no usable source highlights."
        if malformed_count:
            error = f"{error} Ignored {malformed_count} malformed source result(s)."
        run.outcomes[spec.plan_id] = BranchOutcome(
            status=NodeStatus.FAILED,
            error=error,
            retryable=False,
            malformed_source_count=malformed_count,
        )
        return
    run.outcomes[spec.plan_id] = BranchOutcome(
        status=NodeStatus.COMPLETED,
        sources=valid_sources,
        malformed_source_count=malformed_count,
    )


async def _execute_ready_branches(run: TaskRun) -> None:
    """Run all currently independent, unblocked branches concurrently."""
    for spec in run.specs:
        if spec.dependency_error and spec.plan_id not in run.outcomes:
            run.outcomes[spec.plan_id] = BranchOutcome(
                status=NodeStatus.FAILED,
                error=spec.dependency_error,
                retryable=False,
                attempts=0,
            )
    while True:
        ready: list[SubtaskSpec] = []
        for spec in run.specs:
            if spec.plan_id in run.outcomes:
                continue
            failed_dependencies = [
                dependency for dependency in spec.depends_on
                if dependency in run.outcomes and run.outcomes[dependency].status == NodeStatus.FAILED
            ]
            if failed_dependencies:
                run.outcomes[spec.plan_id] = BranchOutcome(
                    status=NodeStatus.FAILED,
                    error="A required prerequisite branch failed; this branch was not run.",
                    retryable=False,
                    attempts=0,
                )
                continue
            if _unresolved_inputs(run, spec.plan_id):
                run.runtime_statuses[spec.plan_id] = NodeStatus.BLOCKED_USER
                continue
            if any(dependency not in run.outcomes for dependency in spec.depends_on):
                run.runtime_statuses[spec.plan_id] = NodeStatus.WAITING
                continue
            run.runtime_statuses[spec.plan_id] = NodeStatus.RUNNING
            ready.append(spec)

        if not ready:
            break
        await _publish_state(run, include_barriers=False)
        await asyncio.gather(*(_run_branch(run, spec) for spec in ready))
        for spec in ready:
            run.runtime_statuses[spec.plan_id] = run.outcomes[spec.plan_id].status
        await _publish_state(run, include_barriers=False)

    # A plan cycle or unresolved dependency with no required input is an
    # explicit graph failure, rather than a task that can wait forever.
    for spec in run.specs:
        if spec.plan_id in run.outcomes or _unresolved_inputs(run, spec.plan_id):
            continue
        if _waiting_on_required_input(run, spec.plan_id):
            run.runtime_statuses[spec.plan_id] = NodeStatus.WAITING
            continue
        run.outcomes[spec.plan_id] = BranchOutcome(
            status=NodeStatus.FAILED,
            error="This branch could not be scheduled because its dependency graph contains a cycle or missing prerequisite.",
            retryable=False,
            attempts=0,
        )
    await _publish_state(run)


def _create_run(
    task_id: str,
    prompt: str,
    normalized,
    memory_items: list[TemporalMemoryItem],
    on_event=None,
) -> TaskRun:
    specs, inputs, criteria, planning_mode, planning_error = normalized
    node_ids = {spec.plan_id: f"node_{uuid4().hex[:8]}" for spec in specs}
    search_task_ids = {spec.plan_id: f"wt_{uuid4().hex[:8]}" for spec in specs}
    blockers = {input_id: set(item.blocks_subtask_ids) for input_id, item in inputs.items()}
    for spec in specs:
        for input_id in spec.required_input_ids:
            if input_id in blockers:
                blockers[input_id].add(spec.plan_id)
    barrier_task_ids = {
        (plan_id, input_id): f"wt_{uuid4().hex[:8]}"
        for input_id, plan_ids in blockers.items()
        for plan_id in plan_ids
        if plan_id in node_ids
    }
    return TaskRun(
        task_id=task_id,
        prompt=prompt,
        specs=specs,
        input_specs=inputs,
        plan_criteria=criteria,
        planning_mode=planning_mode,
        planning_error=planning_error,
        memory=memory_items,
        node_ids=node_ids,
        search_work_task_ids=search_task_ids,
        barrier_work_task_ids=barrier_task_ids,
        merge_node_id=f"node_merge_{uuid4().hex[:8]}",
        on_event=on_event,
    )


async def create_graph(
    prompt: str,
    on_event=None,
    *,
    task_id: str | None = None,
    display_prompt: str | None = None,
    memory_items: list[TemporalMemoryItem] | None = None,
    check_barriers: bool = True,
    announce_created: bool = True,
) -> TaskGraph:
    del check_barriers  # Barriers now come only from explicit planner requirements.
    task_id = task_id or f"task_{uuid4().hex[:10]}"
    display_prompt = display_prompt or prompt
    inherited_memory = memory_items if memory_items is not None else await memory_store.reuse(task_id, prompt)
    try:
        raw_plan = await plan(prompt)
        normalized = _normalize_plan(raw_plan)
    except Exception as exc:
        logger.warning("Planner failed with %s; using original prompt as one Exa query", type(exc).__name__)
        normalized = _normalize_plan({
            "subtasks": [{
                "id": "request",
                "title": "Research the request",
                "goal": "Find live evidence relevant to the user's request.",
                "query": prompt,
                "depends_on": [],
                "required_input_ids": [],
                "comparison_criteria": [],
            }],
            "required_inputs": [],
            "comparison_criteria": [],
            "planning_mode": "deterministic_fallback",
            "planning_error": f"Planner failed ({type(exc).__name__}); the original request was used as the Exa query.",
        })

    run = _create_run(task_id, display_prompt, normalized, inherited_memory, on_event)
    _task_runs[task_id] = run
    if on_event and announce_created:
        await _emit(run, Event(type=EventType.TASK_CREATED, task_id=task_id, payload={"prompt": display_prompt}))
    await _execute_ready_branches(run)
    return _build_graph(run)


async def resolve_barrier(graph: TaskGraph, barrier_id: str, answer: str, on_event=None) -> TaskGraph:
    barrier = next((item for item in graph.barriers if item.barrier_id == barrier_id), None)
    if barrier is None:
        raise ValueError("unknown data barrier")
    if barrier.task_id != graph.id:
        raise ValueError("data barrier does not belong to this task")
    answer = answer.strip()
    if not answer:
        raise ValueError("barrier answer must not be blank")

    run = _task_runs.get(graph.id)
    if run is None:
        raise ValueError("resumable DAG state is unavailable; this backend may have restarted")
    input_id = next((key for key, value in run.barrier_ids.items() if value == barrier_id), None)
    if input_id is None or input_id not in run.input_specs:
        raise ValueError("data barrier is not part of the resumable plan")
    item = run.input_specs[input_id]
    if answer.casefold() in PLACEHOLDER_OPTIONS:
        raise ValueError("provide task details with this response mode before the dependent branches can resume")
    if input_id in run.answers:
        raise ValueError("data barrier has already been resolved")

    memory_item = await memory_store.add(graph.id, item.key, answer)
    run.answers[input_id] = answer
    run.memory = [*run.memory, memory_item]
    if on_event is not None:
        run.on_event = on_event
    await _execute_ready_branches(run)
    return _build_graph(run)
