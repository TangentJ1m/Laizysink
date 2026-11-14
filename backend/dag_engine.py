import asyncio
import re
from uuid import uuid4

from exa_client import search
from memory_store import memory_store
from models import DataBarrier, Event, EventType, ExaSource, FreshnessTag, NodeStatus, TaskGraph, WorkNode, WorkTask, WorkTaskType
from planner import plan


def _missing_trip_details(prompt: str) -> bool:
    travel_query = re.search(r"\b(trip|flight|hotel|travel)\b", prompt, re.IGNORECASE)
    if not travel_query:
        return False
    has_dates = bool(re.search(r"\b(?:jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)\b|\b20\d{2}\b", prompt, re.IGNORECASE))
    has_party = bool(re.search(r"\b\d+\s*(?:guest|people|person|travell?er|passenger)s?\b", prompt, re.IGNORECASE))
    has_budget = bool(re.search(r"\b(budget|£|€|\$)\b", prompt, re.IGNORECASE))
    return not (has_dates and has_party and has_budget)


def _barrier(task_id: str, node_id: str) -> DataBarrier:
    return DataBarrier(
        barrier_id=f"bar_{uuid4().hex[:8]}", task_id=task_id, source_node_id=node_id,
        badge_text="Quick input needed • Unlocks travel comparisons",
        question="Confirm your dates, party size, and budget so I can finish comparing options:",
        options=["Nov 18–23 • 2 guests • £1,500 budget", "Nov 20–25 • 1 guest • £1,200 budget", "Flexible dates • 2 guests • Mid-range"],
        allow_other_voice_text=True,
    )


async def create_graph(prompt: str, on_event=None) -> TaskGraph:
    task_id = f"task_{uuid4().hex[:10]}"
    inherited_memory = memory_store.reuse(task_id, prompt)
    branches = await plan(prompt)
    branch_ids = [f"node_{uuid4().hex[:8]}" for _ in branches]
    nodes = [
        WorkNode(
            id=node_id, task_id=task_id, title=branch["title"], status=NodeStatus.RUNNING,
            freshness_tag=FreshnessTag.USED, parent_ids=[], plain_summary="Searching live sources.",
            highlight_chips=[], inherited_context={item.key: item.value for item in inherited_memory},
            decision_rationale="Source comparison is in progress.",
            work_tasks=[WorkTask(id=f"wt_{uuid4().hex[:8]}", parent_node_id=node_id, task_type=WorkTaskType.EXA_SEARCH, label="Exa live web search", status=NodeStatus.RUNNING)],
            sources=[],
        ) for node_id, branch in zip(branch_ids, branches)
    ]
    if on_event:
        await on_event(Event(type=EventType.TASK_CREATED, task_id=task_id, payload={"prompt": prompt}))

    results: dict[str, list[dict]] = {}
    async def run_branch(node_id: str, query: str) -> None:
        try:
            results[node_id] = await asyncio.wait_for(search(query), timeout=8)
        except Exception:
            results[node_id] = []

    async with asyncio.TaskGroup() as group:
        for node_id, branch in zip(branch_ids, branches):
            group.create_task(run_branch(node_id, branch["query"]))

    finalized = []
    for node in nodes:
        sources = [ExaSource.model_validate(source) for source in results[node.id]]
        status = NodeStatus.COMPLETED if sources else NodeStatus.FAILED
        finalized_node = node.model_copy(update={
            "status": status,
            "plain_summary": f"Found {len(sources)} source(s) for {node.title}." if sources else "This research branch failed or timed out.",
            "sources": sources,
            "work_tasks": [node.work_tasks[0].model_copy(update={"status": status})],
        })
        finalized.append(finalized_node)
        if on_event:
            await on_event(Event(type=EventType.NODE_UPDATED, task_id=task_id, payload={"node": finalized_node.model_dump(mode="json")}))

    barriers = [_barrier(task_id, branch_ids[0])] if _missing_trip_details(prompt) else []
    graph_status = NodeStatus.BLOCKED_USER if barriers else (NodeStatus.COMPLETED if any(node.sources for node in finalized) else NodeStatus.FAILED)
    graph = TaskGraph(id=task_id, prompt=prompt, status=graph_status, nodes=finalized, barriers=barriers, memory=inherited_memory)
    if on_event and barriers:
        await on_event(Event(type=EventType.DATA_BARRIER, task_id=task_id, payload={"barrier": barriers[0].model_dump(mode="json")}))
    elif on_event and graph_status == NodeStatus.COMPLETED:
        await on_event(Event(type=EventType.TASK_COMPLETED, task_id=task_id, payload={"task_graph": graph.model_dump(mode="json")}))
    return graph


def resolve_barrier(graph: TaskGraph, barrier_id: str, answer: str) -> TaskGraph:
    barrier = next((item for item in graph.barriers if item.barrier_id == barrier_id), None)
    if barrier is None:
        raise ValueError("unknown data barrier")
    memory_item = memory_store.add(graph.id, "trip_constraints", answer)
    return graph.model_copy(update={
        "status": NodeStatus.COMPLETED,
        "barriers": [item for item in graph.barriers if item.barrier_id != barrier_id],
        "memory": [*graph.memory, memory_item],
    })

