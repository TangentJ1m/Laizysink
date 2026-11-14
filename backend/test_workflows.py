import asyncio

import dag_engine
from memory_store import TemporalMemoryStore
from models import EventType, FreshnessTag, NodeStatus


def test_tokyo_trip_searches_three_parallel_branches(monkeypatch):
    concurrent = 0
    maximum_concurrent = 0

    async def fake_plan(_):
        return [{"title": f"Branch {n}", "query": f"query {n}"} for n in range(3)]

    async def fake_search(prompt):
        nonlocal concurrent, maximum_concurrent
        concurrent += 1
        maximum_concurrent = max(maximum_concurrent, concurrent)
        await asyncio.sleep(0.02)
        concurrent -= 1
        return [{"id": prompt, "domain": "ana.co.jp", "url": "https://ana.co.jp", "title": "ANA flights", "highlighted_quote": "Direct flight evidence", "selected": True}]

    monkeypatch.setattr(dag_engine, "plan", fake_plan)
    monkeypatch.setattr(dag_engine, "search", fake_search)
    events = []

    async def collect(event):
        events.append(event)

    prompt = "Plan a Japan Trip Nov 18-23, 2 guests, budget £1500"
    graph = asyncio.run(dag_engine.create_graph(prompt, collect))
    assert graph.status == NodeStatus.COMPLETED
    assert len(graph.nodes) == 3
    assert maximum_concurrent == 3
    assert [event.type for event in events].count(EventType.NODE_UPDATED) == 3
    assert events[0].type == EventType.TASK_CREATED
    assert events[-1].type == EventType.TASK_COMPLETED


def test_missing_trip_details_create_resolvable_barrier(monkeypatch):
    async def fake_plan(_):
        return [{"title": "Flights", "query": "flight"}]

    async def fake_search(_):
        return [{"id": "cit", "domain": "ana.co.jp", "url": "https://ana.co.jp", "title": "ANA", "highlighted_quote": "Fare source", "selected": True}]

    monkeypatch.setattr(dag_engine, "plan", fake_plan)
    monkeypatch.setattr(dag_engine, "search", fake_search)
    graph = asyncio.run(dag_engine.create_graph("Plan a Japan trip"))
    assert graph.status == NodeStatus.BLOCKED_USER
    assert len(graph.barriers) == 1
    resolved = dag_engine.resolve_barrier(graph, graph.barriers[0].barrier_id, "Nov 18-23, 2 guests, £1500")
    assert resolved.status == NodeStatus.COMPLETED
    assert not resolved.barriers


def test_follow_up_reuses_temporal_hotel_memory():
    store = TemporalMemoryStore()
    store.add("task_japan", "hotel", "Tokyo coordinates near Shinjuku")
    inherited = store.reuse("task_dinner", "Dinner near my Tokyo hotel")
    assert inherited[0].task_id == "task_japan"
    assert inherited[0].key == "hotel"
    assert inherited[0].freshness_tag == FreshnessTag.USED


def test_helsinki_legal_query_carries_citations(monkeypatch):
    async def fake_plan(_):
        return [{"title": "Verify law", "query": "official Helsinki rent law"}]

    async def fake_search(prompt):
        assert "Helsinki" in prompt
        return [{"id": "statute", "domain": "finlex.fi", "url": "https://finlex.fi", "title": "Housing statute", "highlighted_quote": "Official legal source excerpt", "selected": True}]

    monkeypatch.setattr(dag_engine, "plan", fake_plan)
    monkeypatch.setattr(dag_engine, "search", fake_search)
    graph = asyncio.run(dag_engine.create_graph("Check if a 12% rent increase in Helsinki is legal"))
    assert graph.status == NodeStatus.COMPLETED
    assert graph.nodes[0].sources[0].domain == "finlex.fi"
