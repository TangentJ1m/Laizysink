import asyncio
import os
from collections import defaultdict

from fastapi import FastAPI, File, HTTPException, UploadFile, WebSocket, WebSocketDisconnect
from pydantic import BaseModel, ValidationError

from dag_engine import create_graph, resolve_barrier
from models import Event, EventType, ResolveBarrier, TaskGraph
from voice import router as voice_router

app = FastAPI(title="Laizysink API")
app.include_router(voice_router)
tasks: dict[str, TaskGraph] = {}
subscribers: dict[str, set[WebSocket]] = defaultdict(set)


class CreateTask(BaseModel):
    prompt: str
    use_active_memory: bool = True


async def broadcast(event: Event) -> None:
    if event.type == EventType.TASK_COMPLETED:
        snapshot = event.payload.get("task_graph")
        if snapshot is not None:
            tasks[event.task_id] = TaskGraph.model_validate(snapshot)
    for socket in list(subscribers[event.task_id]):
        try:
            await socket.send_json(event.model_dump(mode="json"))
        except Exception:
            subscribers[event.task_id].discard(socket)


@app.get("/health")
async def health():
    return {"status": "ok"}


@app.post("/api/tasks", response_model=TaskGraph)
async def create_task(request: CreateTask):
    if not request.prompt.strip():
        raise HTTPException(status_code=422, detail="prompt must not be blank")
    graph = await create_graph(request.prompt.strip(), broadcast)
    tasks[graph.id] = graph
    return graph


@app.get("/api/tasks/{task_id}", response_model=TaskGraph)
async def get_task(task_id: str):
    if task_id not in tasks:
        raise HTTPException(status_code=404, detail="task not found")
    return tasks[task_id]


@app.websocket("/ws/tasks/{task_id}")
async def task_socket(websocket: WebSocket, task_id: str):
    await websocket.accept()
    subscribers[task_id].add(websocket)
    if task_id in tasks:
        await websocket.send_json({"type": "TASK_CREATED", "task_id": task_id, "payload": tasks[task_id].model_dump(mode="json")})
    try:
        while True:
            message = await websocket.receive_json()
            if message.get("type") == "CREATE_TASK":
                prompt = str(message.get("prompt", "")).strip()
                if not prompt:
                    await websocket.send_json({"type": "ERROR", "task_id": task_id, "payload": {"message": "prompt must not be blank"}})
                    continue

                async def send_to_creator(event: Event) -> None:
                    await websocket.send_json(event.model_dump(mode="json"))

                graph = await create_graph(prompt, send_to_creator)
                tasks[graph.id] = graph
                subscribers[task_id].discard(websocket)
                task_id = graph.id
                subscribers[task_id].add(websocket)
            elif message.get("type") == "RESOLVE_BARRIER":
                try:
                    request = ResolveBarrier.model_validate({
                        "task_id": message.get("task_id"),
                        "barrier_id": message.get("barrier_id"),
                        "answer": message.get("answer"),
                    })
                except ValidationError:
                    await websocket.send_json({"type": "ERROR", "task_id": task_id, "payload": {"message": "invalid RESOLVE_BARRIER payload"}})
                    continue
                if request.task_id != task_id:
                    await websocket.send_json({"type": "ERROR", "task_id": task_id, "payload": {"message": "task_id does not match this WebSocket"}})
                    continue
                graph = tasks.get(task_id)
                if graph is None:
                    await websocket.send_json({"type": "ERROR", "task_id": task_id, "payload": {"message": "task not found"}})
                    continue
                try:
                    graph = await resolve_barrier(graph, request.barrier_id, request.answer, broadcast)
                except ValueError as exc:
                    await websocket.send_json({"type": "ERROR", "task_id": task_id, "payload": {"message": str(exc)}})
                    continue
                tasks[task_id] = graph
    except WebSocketDisconnect:
        subscribers[task_id].discard(websocket)

