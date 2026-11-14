from fastapi.testclient import TestClient

import main
from models import Event, EventType, NodeStatus, TaskGraph


def test_websocket_task_creation_streams_contract_events(monkeypatch):
    async def fake_create_graph(prompt, on_event=None):
        graph = TaskGraph(id="task_test", prompt=prompt, status=NodeStatus.COMPLETED, nodes=[])
        await on_event(Event(type=EventType.TASK_CREATED, task_id=graph.id, payload={"prompt": prompt}))
        await on_event(Event(type=EventType.TASK_COMPLETED, task_id=graph.id, payload={"task_graph": graph.model_dump(mode="json")}))
        return graph

    monkeypatch.setattr(main, "create_graph", fake_create_graph)
    with TestClient(main.app) as client:
        with client.websocket_connect("/ws/tasks/new") as socket:
            socket.send_json({"type": "CREATE_TASK", "prompt": "Check a Helsinki rent increase"})
            created = socket.receive_json()
            completed = socket.receive_json()
    assert created["type"] == "TASK_CREATED"
    assert completed["type"] == "TASK_COMPLETED"
    assert completed["payload"]["task_graph"]["id"] == "task_test"

