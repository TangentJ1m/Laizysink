import asyncio
import logging
import re
from uuid import uuid4

from pydantic import ValidationError

from exa_client import ExaConfigurationError, search
from memory_store import memory_store
from models import DataBarrier, Event, EventType, ExaSource, FreshnessTag, NodeStatus, TaskGraph, TemporalMemoryItem, WorkNode, WorkTask, WorkTaskType
from planner import plan


logger = logging.getLogger(__name__)
BRANCH_TIMEOUT_SECONDS = 8


def _missing_trip_details(prompt: str) -> bool:
    travel_query = re.search(r"\b(trip|flight|hotel|travel)\b", prompt, re.IGNORECASE)
    if not travel_query:
        return False
    has_dates = bool(re.search(r"\b(?:jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)\b|\b20\d{2}\b", prompt, re.IGNORECASE))
    has_party = bool(re.search(r"\b\d+\s*(?:guest|people|person|travell?er|passenger)s?\b", prompt, re.IGNORECASE))
    has_budget = bool(re.search(r"\bbudget\b|[\u00a3\u20ac$]", prompt, re.IGNORECASE))
    return not (has_dates and has_party and has_budget)


def _barrier(task_id: str, node_id: str) -> DataBarrier:
    return DataBarrier(
        barrier_id=f"bar_{uuid4().hex[:8]}",
        task_id=task_id,
        source_node_id=node_id,
        badge_text="Quick input needed - Unlocks travel comparisons",
        question="Share your dates, party size, and budget so I can finish comparing options:",
        options=["Specific dates", "Flexible dates", "I'm not sure yet"],
        allow_other_voice_text=True,
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
    task_id = task_id or f"task_{uuid4().hex[:10]}"
    display_prompt = display_prompt or prompt
    inherited_memory = memory_items if memory_items is not None else await memory_store.reuse(task_id, prompt)
    branches = await plan(prompt)
    branch_ids = [f"node_{uuid4().hex[:8]}" for _ in branches]
    nodes = [
        WorkNode(
            id=node_id,
            task_id=task_id,
            title=branch["title"],
            status=NodeStatus.RUNNING,
            freshness_tag=FreshnessTag.USED,
            parent_ids=[],
            plain_summary="Searching live sources.",
            highlight_chips=[],
            inherited_context={item.key: item.value for item in inherited_memory},
            decision_rationale="Source comparison is in progress.",
            work_tasks=[WorkTask(
                id=f"wt_{uuid4().hex[:8]}",
                parent_node_id=node_id,
                task_type=WorkTaskType.EXA_SEARCH,
                label="Exa live web search",
                status=NodeStatus.RUNNING,
            )],
            sources=[],
        )
        for node_id, branch in zip(branch_ids, branches)
    ]

    if on_event and announce_created:
        await on_event(Event(type=EventType.TASK_CREATED, task_id=task_id, payload={"prompt": display_prompt}))

    results: dict[str, list[dict]] = {}
    failures: dict[str, str] = {}

    async def run_branch(node_id: str, query: str) -> None:
        try:
            results[node_id] = await asyncio.wait_for(search(query), timeout=BRANCH_TIMEOUT_SECONDS)
        except asyncio.TimeoutError:
            failures[node_id] = f"Exa search timed out after {BRANCH_TIMEOUT_SECONDS} seconds."
        except ExaConfigurationError:
            failures[node_id] = "Live Exa research unavailable: EXA_API_KEY is not configured."
        except Exception as exc:
            logger.warning("Exa branch failed with %s", type(exc).__name__)
            failures[node_id] = f"Exa search failed ({type(exc).__name__}). Check backend provider configuration."

    async with asyncio.TaskGroup() as group:
        for node_id, branch in zip(branch_ids, branches):
            group.create_task(run_branch(node_id, branch["query"]))

    finalized: list[WorkNode] = []
    for node in nodes:
        failure = failures.get(node.id)
        try:
            sources = [ExaSource.model_validate(source) for source in results.get(node.id, [])]
        except ValidationError:
            sources = []
            failure = "Exa returned source data that did not match the citation contract."

        status = NodeStatus.COMPLETED if sources else NodeStatus.FAILED
        if sources:
            summary = f"Found {len(sources)} live Exa source(s) for {node.title}."
            rationale = f"Exa returned {len(sources)} ranked live result(s); no additional selection criteria were applied."
        elif failure:
            summary = failure
            rationale = failure
        else:
            summary = "Exa returned no sources for this research branch."
            rationale = "No live Exa results were returned."

        finalized_node = node.model_copy(update={
            "status": status,
            "plain_summary": summary,
            "decision_rationale": rationale,
            "sources": sources,
            "work_tasks": [node.work_tasks[0].model_copy(update={"status": status})],
        })
        finalized.append(finalized_node)
        if on_event:
            await on_event(Event(type=EventType.NODE_UPDATED, task_id=task_id, payload={"node": finalized_node.model_dump(mode="json")}))

    barriers = [_barrier(task_id, branch_ids[0])] if check_barriers and branch_ids and _missing_trip_details(display_prompt) else []
    if barriers:
        graph_status = NodeStatus.BLOCKED_USER
    else:
        graph_status = NodeStatus.COMPLETED if any(node.sources for node in finalized) else NodeStatus.FAILED

    graph = TaskGraph(
        id=task_id,
        prompt=display_prompt,
        status=graph_status,
        nodes=finalized,
        barriers=barriers,
        memory=inherited_memory,
    )
    if on_event and barriers:
        await on_event(Event(type=EventType.DATA_BARRIER, task_id=task_id, payload={"barrier": barriers[0].model_dump(mode="json")}))
    elif on_event:
        # TASK_COMPLETED is the terminal graph snapshot event even when its status is FAILED.
        await on_event(Event(type=EventType.TASK_COMPLETED, task_id=task_id, payload={"task_graph": graph.model_dump(mode="json")}))
    return graph


async def resolve_barrier(graph: TaskGraph, barrier_id: str, answer: str, on_event=None) -> TaskGraph:
    barrier = next((item for item in graph.barriers if item.barrier_id == barrier_id), None)
    if barrier is None:
        raise ValueError("unknown data barrier")
    if barrier.task_id != graph.id:
        raise ValueError("data barrier does not belong to this task")
    answer = answer.strip()
    if not answer:
        raise ValueError("barrier answer must not be blank")

    memory_item = await memory_store.add(graph.id, "trip_constraints", answer)
    memory_items = [*graph.memory, memory_item]
    resumed_prompt = f"{graph.prompt}\n\nUser-provided constraints: {answer}"
    return await create_graph(
        resumed_prompt,
        on_event,
        task_id=graph.id,
        display_prompt=graph.prompt,
        memory_items=memory_items,
        check_barriers=False,
        announce_created=False,
    )
