import asyncio
import logging
from collections import defaultdict
from urllib.parse import urlsplit

import models
from fastapi import FastAPI, HTTPException, WebSocket, WebSocketDisconnect
from pydantic import BaseModel, ConfigDict, Field, ValidationError

from dag_engine import create_graph, resolve_barrier
from memory_store import memory_store
from models import DataBarrier, Event, NodeStatus, ResolveBarrier, TaskGraph, WorkNode
from voice import router as voice_router


logger = logging.getLogger(__name__)
app = FastAPI(title="Laizysink API")
app.include_router(voice_router)

# Task and subscriber state is process-local. A server restart loses task graphs.
tasks: dict[str, TaskGraph] = {}
subscribers: dict[str, set[WebSocket]] = defaultdict(set)
_snapshot_locks: defaultdict[str, asyncio.Lock] = defaultdict(asyncio.Lock)
_command_locks: defaultdict[str, asyncio.Lock] = defaultdict(asyncio.Lock)
_pending_hitl_tabs: defaultdict[str, dict[str, str]] = defaultdict(dict)
_keychain_completions: dict[tuple[str, str], str] = {}


class CreateTask(BaseModel):
    model_config = ConfigDict(extra="forbid")

    prompt: str
    attachments: list[str] = Field(default_factory=list)
    use_active_memory: bool = True
    request_id: str | None = None


class KeychainAuthCompleteCommand(BaseModel):
    model_config = ConfigDict(extra="forbid")

    task_id: str
    tab_id: str
    keychain_ref: str = Field(pattern=r"^vault://")


class ExpireMemoryCommand(BaseModel):
    model_config = ConfigDict(extra="forbid")

    memory_id: str


def _event_type(event: Event) -> str:
    value = event.type
    return str(getattr(value, "value", value))


def _wire_event(event_type: str, task_id: str, payload: object) -> dict:
    """Serialize with A2's strict wire models when they are present after integration."""
    class_names = {
        "TASK_STATE_SYNC": "TaskStateSyncEvent",
        "BARRIER_TRIGGERED": "BarrierTriggeredEvent",
        "HITL_TAB_REQUIRED": "HitlTabRequiredEvent",
        "COMMAND_RESULT": "CommandResultEvent",
    }
    event_model = getattr(models, class_names[event_type], None)
    if event_model is not None:
        return event_model(task_id=task_id, payload=payload).model_dump(mode="json")

    # This fallback keeps A1's branch usable before A2's model commit is integrated.
    if hasattr(payload, "model_dump"):
        payload = payload.model_dump(mode="json")
    return {"type": event_type, "task_id": task_id, "payload": payload}


def _command_result(
    task_id: str,
    command_type: str,
    accepted: bool,
    message: str | None = None,
    request_id: str | None = None,
) -> dict:
    payload: dict[str, object] = {"command_type": command_type, "accepted": accepted}
    if message is not None:
        payload["message"] = message
    if request_id is not None:
        payload["request_id"] = request_id
    return _wire_event("COMMAND_RESULT", task_id, payload)


def _request_id(message: dict) -> str | None:
    value = message.get("request_id")
    return value if isinstance(value, str) and 0 < len(value) <= 128 else None


async def _emit(
    event: dict,
    direct_socket: WebSocket | None = None,
    *,
    to_subscribers: bool = True,
) -> None:
    task_id = event["task_id"]
    recipients = set(subscribers.get(task_id, set())) if to_subscribers else set()
    if direct_socket is not None:
        recipients.add(direct_socket)
    for socket in recipients:
        try:
            await socket.send_json(event)
        except Exception:
            subscribers[task_id].discard(socket)


async def _publish_dag_event(event: Event, direct_socket: WebSocket | None = None) -> None:
    """Translate legacy DAG callbacks into canonical events and current snapshots."""
    task_id = event.task_id
    kind = _event_type(event)
    payload = event.payload
    snapshot: TaskGraph | None = None
    barrier: DataBarrier | None = None
    hitl_payload: dict[str, str] | None = None

    async with _snapshot_locks[task_id]:
        current = tasks.get(task_id)

        if kind == "TASK_CREATED":
            raw_graph = payload.get("task_graph")
            if raw_graph is not None:
                snapshot = TaskGraph.model_validate(raw_graph)
                if snapshot.id != task_id:
                    logger.warning("Ignoring creation snapshot for mismatched task %s", task_id)
                    return
            else:
                snapshot = TaskGraph(
                    id=task_id,
                    prompt=str(payload.get("prompt", "")),
                    status=NodeStatus.RUNNING,
                    nodes=[],
                    barriers=[],
                    memory=[],
                )
        elif kind == "NODE_UPDATED":
            node_data = payload.get("node")
            if node_data is not None:
                node = WorkNode.model_validate(node_data)
                if node.task_id != task_id:
                    logger.warning("Ignoring node %s from mismatched task %s", node.id, task_id)
                    return
                if current is None:
                    current = TaskGraph(
                        id=task_id,
                        prompt="",
                        status=NodeStatus.RUNNING,
                        nodes=[],
                        barriers=[],
                        memory=[],
                    )
                nodes = [item for item in current.nodes if item.id != node.id]
                nodes.append(node)
                snapshot = current.model_copy(update={"nodes": nodes})
        elif kind in {"DATA_BARRIER", "BARRIER_TRIGGERED"}:
            barrier_data = payload.get("barrier", payload)
            barrier = DataBarrier.model_validate(barrier_data)
            if barrier.task_id != task_id:
                logger.warning("Ignoring barrier %s from mismatched task %s", barrier.barrier_id, task_id)
                return
            if current is None:
                current = TaskGraph(
                    id=task_id,
                    prompt="",
                    status=NodeStatus.BLOCKED_USER,
                    nodes=[],
                    barriers=[],
                    memory=[],
                )
            barriers = [item for item in current.barriers if item.barrier_id != barrier.barrier_id]
            barriers.append(barrier)
            snapshot = current.model_copy(update={"status": NodeStatus.BLOCKED_USER, "barriers": barriers})
        elif kind in {"TASK_COMPLETED", "TASK_STATE_SYNC"}:
            graph_data = payload.get("task_graph", payload)
            snapshot = TaskGraph.model_validate(graph_data)
            if snapshot.id != task_id:
                logger.warning("Ignoring snapshot for mismatched task %s", task_id)
                return
        elif kind in {"HITL_BROWSER", "HITL_TAB_REQUIRED"}:
            required = ("tab_id", "url", "target_selector", "tooltip_text", "domain")
            if all(isinstance(payload.get(key), str) and payload[key] for key in required):
                hitl_payload = {key: str(payload[key]) for key in required}
                hitl_payload["task_id"] = task_id
                _pending_hitl_tabs[task_id][hitl_payload["tab_id"]] = hitl_payload["domain"]
            else:
                logger.warning("Ignoring an incomplete HITL event for task %s", task_id)
        elif kind == "ERROR":
            message = str(payload.get("message", "Task execution failed."))
            await _emit(
                _command_result(task_id, "TASK_EXECUTION", False, message),
                direct_socket,
            )
            return
        else:
            logger.warning("Ignoring unsupported internal DAG event type %s", kind)
            return

        if snapshot is not None:
            tasks[task_id] = snapshot

        if barrier is not None:
            await _emit(_wire_event("BARRIER_TRIGGERED", task_id, barrier), direct_socket)
        if hitl_payload is not None:
            await _emit(_wire_event("HITL_TAB_REQUIRED", task_id, hitl_payload), direct_socket)
        if snapshot is not None:
            await _emit(_wire_event("TASK_STATE_SYNC", task_id, snapshot), direct_socket)


async def broadcast(event: Event) -> None:
    await _publish_dag_event(event)


async def _send_result(
    websocket: WebSocket,
    task_id: str,
    command_type: str,
    accepted: bool,
    message: str | None = None,
    request_id: str | None = None,
) -> None:
    await _emit(
        _command_result(task_id, command_type, accepted, message, request_id),
        websocket,
        to_subscribers=False,
    )


def _command_request(model_type: type[BaseModel], message: dict, fields: tuple[str, ...]):
    values = {field: message[field] for field in fields if field in message}
    return model_type.model_validate(values)


def _keychain_ref_is_opaque(value: str) -> bool:
    parsed = urlsplit(value)
    return (
        parsed.scheme == "vault"
        and bool(parsed.netloc)
        and parsed.username is None
        and parsed.password is None
        and not parsed.query
        and not parsed.fragment
        and parsed.path in ("", "/")
    )


@app.get("/health")
async def health():
    return {"status": "ok"}


@app.post("/api/tasks", response_model=TaskGraph)
async def create_task(request: CreateTask):
    prompt = request.prompt.strip()
    if not prompt:
        raise HTTPException(status_code=422, detail="prompt must not be blank")
    if request.attachments:
        raise HTTPException(status_code=422, detail="task attachments are not supported by the current DAG engine")
    graph = await create_graph(
        prompt,
        broadcast,
        memory_items=None if request.use_active_memory else [],
    )
    tasks[graph.id] = graph
    return graph


@app.get("/api/tasks/{task_id}", response_model=TaskGraph)
async def get_task(task_id: str):
    if task_id not in tasks:
        raise HTTPException(status_code=404, detail="task not found")
    return tasks[task_id]


async def _attach_socket(websocket: WebSocket, task_id: str) -> bool:
    graph = tasks.get(task_id)
    if graph is None:
        await _send_result(websocket, task_id, "ATTACH_TASK", False, "task not found")
        return False
    async with _snapshot_locks[task_id]:
        graph = tasks.get(task_id)
        if graph is None:
            await _send_result(websocket, task_id, "ATTACH_TASK", False, "task not found")
            return False
        subscribers[task_id].add(websocket)
        await _emit(_wire_event("TASK_STATE_SYNC", task_id, graph), websocket, to_subscribers=False)
    return True


async def _handle_create_command(websocket: WebSocket, message: dict, request_id: str | None) -> None:
    try:
        request = _command_request(CreateTask, message, ("prompt", "attachments", "use_active_memory"))
    except ValidationError:
        await _send_result(websocket, "new", "CREATE_TASK", False, "invalid CREATE_TASK payload", request_id)
        return

    prompt = request.prompt.strip()
    if not prompt:
        await _send_result(websocket, "new", "CREATE_TASK", False, "prompt must not be blank", request_id)
        return
    if request.attachments:
        await _send_result(
            websocket,
            "new",
            "CREATE_TASK",
            False,
            "task attachments are not supported by the current DAG engine",
            request_id,
        )
        return

    created_task_id: str | None = None
    acknowledged = False

    async def send_to_creator(event: Event) -> None:
        nonlocal created_task_id, acknowledged
        if _event_type(event) == "TASK_CREATED" and not acknowledged:
            created_task_id = event.task_id
            await _send_result(websocket, created_task_id, "CREATE_TASK", True, request_id=request_id)
            acknowledged = True
        await _publish_dag_event(event, direct_socket=websocket)

    try:
        graph = await create_graph(
            prompt,
            send_to_creator,
            memory_items=None if request.use_active_memory else [],
        )
        async with _snapshot_locks[graph.id]:
            if tasks.get(graph.id) != graph:
                tasks[graph.id] = graph
                await _emit(_wire_event("TASK_STATE_SYNC", graph.id, graph), websocket)
    except Exception as exc:
        logger.error("Task creation failed (%s)", type(exc).__name__)
        if not acknowledged:
            await _send_result(websocket, "new", "CREATE_TASK", False, "task creation failed", request_id)
        elif created_task_id is not None and created_task_id in tasks:
            async with _snapshot_locks[created_task_id]:
                failed = tasks[created_task_id].model_copy(update={"status": NodeStatus.FAILED})
                tasks[created_task_id] = failed
                await _emit(_wire_event("TASK_STATE_SYNC", created_task_id, failed), websocket)


async def _handle_barrier_command(websocket: WebSocket, task_id: str, message: dict, request_id: str | None) -> None:
    try:
        request = _command_request(ResolveBarrier, message, ("task_id", "barrier_id", "answer"))
    except ValidationError:
        await _send_result(websocket, task_id, "RESOLVE_BARRIER", False, "invalid RESOLVE_BARRIER payload", request_id)
        return
    if request.task_id != task_id:
        await _send_result(websocket, task_id, "RESOLVE_BARRIER", False, "task_id does not match this WebSocket", request_id)
        return
    if not request.answer.strip():
        await _send_result(websocket, task_id, "RESOLVE_BARRIER", False, "barrier answer must not be blank", request_id)
        return

    async with _command_locks[task_id]:
        graph = tasks.get(task_id)
        if graph is None:
            await _send_result(websocket, task_id, "RESOLVE_BARRIER", False, "task not found", request_id)
            return
        if not any(item.barrier_id == request.barrier_id for item in graph.barriers):
            await _send_result(websocket, task_id, "RESOLVE_BARRIER", False, "unknown data barrier", request_id)
            return
        try:
            graph = await resolve_barrier(graph, request.barrier_id, request.answer, broadcast)
        except ValueError as exc:
            await _send_result(websocket, task_id, "RESOLVE_BARRIER", False, str(exc), request_id)
            return
        tasks[task_id] = graph
        await _send_result(websocket, task_id, "RESOLVE_BARRIER", True, request_id=request_id)


async def _handle_keychain_command(websocket: WebSocket, task_id: str, message: dict, request_id: str | None) -> None:
    try:
        request = _command_request(
            KeychainAuthCompleteCommand,
            message,
            ("task_id", "tab_id", "keychain_ref"),
        )
    except ValidationError:
        await _send_result(websocket, task_id, "KEYCHAIN_AUTH_COMPLETE", False, "invalid KEYCHAIN_AUTH_COMPLETE payload", request_id)
        return
    if request.task_id != task_id:
        await _send_result(websocket, task_id, "KEYCHAIN_AUTH_COMPLETE", False, "task_id does not match this WebSocket", request_id)
        return
    if not request.tab_id.strip():
        await _send_result(websocket, task_id, "KEYCHAIN_AUTH_COMPLETE", False, "tab_id must not be blank", request_id)
        return
    if not _keychain_ref_is_opaque(request.keychain_ref):
        await _send_result(websocket, task_id, "KEYCHAIN_AUTH_COMPLETE", False, "keychain_ref must be an opaque vault:// reference", request_id)
        return
    if task_id not in tasks:
        await _send_result(websocket, task_id, "KEYCHAIN_AUTH_COMPLETE", False, "task not found", request_id)
        return
    pending_tabs = _pending_hitl_tabs[task_id]
    pending_domain = pending_tabs.get(request.tab_id)
    if pending_tabs and pending_domain is None:
        await _send_result(websocket, task_id, "KEYCHAIN_AUTH_COMPLETE", False, "tab_id is not awaiting input for this task", request_id)
        return
    if pending_domain is not None and pending_domain.casefold() != urlsplit(request.keychain_ref).netloc.casefold():
        await _send_result(websocket, task_id, "KEYCHAIN_AUTH_COMPLETE", False, "keychain_ref domain does not match the pending tab", request_id)
        return

    # Only the opaque reference crosses the socket. The DAG currently has no
    # HITL work item that can consume this completion and resume a branch.
    _keychain_completions[(task_id, request.tab_id)] = request.keychain_ref
    pending_tabs.pop(request.tab_id, None)
    await _send_result(
        websocket,
        task_id,
        "KEYCHAIN_AUTH_COMPLETE",
        True,
        "opaque reference recorded; no HITL branch resume is implemented",
        request_id,
    )


async def _handle_expire_memory(websocket: WebSocket, task_id: str, message: dict, request_id: str | None) -> None:
    try:
        request = _command_request(ExpireMemoryCommand, message, ("memory_id",))
    except ValidationError:
        await _send_result(websocket, task_id, "EXPIRE_MEMORY", False, "invalid EXPIRE_MEMORY payload", request_id)
        return

    graph = tasks.get(task_id)
    if graph is None:
        await _send_result(websocket, task_id, "EXPIRE_MEMORY", False, "task not found", request_id)
        return
    if not any(item.id == request.memory_id for item in graph.memory):
        await _send_result(websocket, task_id, "EXPIRE_MEMORY", False, "memory item is not attached to this task", request_id)
        return

    try:
        expired = await memory_store.expire(request.memory_id)
    except Exception as exc:
        logger.error("Memory expiration failed (%s)", type(exc).__name__)
        await _send_result(websocket, task_id, "EXPIRE_MEMORY", False, "memory expiration failed", request_id)
        return
    if not expired:
        await _send_result(websocket, task_id, "EXPIRE_MEMORY", False, "memory item not found", request_id)
        return

    for affected_id, affected_graph in list(tasks.items()):
        if not any(item.id == request.memory_id for item in affected_graph.memory):
            continue
        async with _snapshot_locks[affected_id]:
            current = tasks.get(affected_id)
            if current is None or not any(item.id == request.memory_id for item in current.memory):
                continue
            updated = current.model_copy(update={
                "memory": [item for item in current.memory if item.id != request.memory_id]
            })
            tasks[affected_id] = updated
            await _emit(_wire_event("TASK_STATE_SYNC", affected_id, updated))
    await _send_result(websocket, task_id, "EXPIRE_MEMORY", True, request_id=request_id)


@app.websocket("/ws/tasks/{task_id}")
async def task_socket(websocket: WebSocket, task_id: str):
    await websocket.accept()
    attached = False
    try:
        if task_id != "new":
            attached = await _attach_socket(websocket, task_id)
            if not attached:
                await websocket.close(code=4404)
                return

        while True:
            try:
                message = await websocket.receive_json()
            except WebSocketDisconnect:
                raise
            except Exception:
                await _send_result(websocket, task_id, "UNKNOWN", False, "message must be a JSON object")
                continue

            if not isinstance(message, dict):
                await _send_result(websocket, task_id, "UNKNOWN", False, "message must be a JSON object")
                continue

            command_type = message.get("type")
            if not isinstance(command_type, str):
                await _send_result(websocket, task_id, "UNKNOWN", False, "command type is required", _request_id(message))
                continue
            request_id = _request_id(message)

            if command_type == "CREATE_TASK":
                if task_id != "new":
                    await _send_result(websocket, task_id, command_type, False, "CREATE_TASK is only valid on /ws/tasks/new", request_id)
                    continue
                await _handle_create_command(websocket, message, request_id)
            elif command_type == "RESOLVE_BARRIER":
                if task_id == "new":
                    await _send_result(websocket, task_id, command_type, False, "task_id is required before resolving a barrier", request_id)
                    continue
                await _handle_barrier_command(websocket, task_id, message, request_id)
            elif command_type == "KEYCHAIN_AUTH_COMPLETE":
                if task_id == "new":
                    await _send_result(websocket, task_id, command_type, False, "task_id is required before completing keychain auth", request_id)
                    continue
                await _handle_keychain_command(websocket, task_id, message, request_id)
            elif command_type == "EXPIRE_MEMORY":
                if task_id == "new":
                    await _send_result(websocket, task_id, command_type, False, "task_id is required before expiring memory", request_id)
                    continue
                await _handle_expire_memory(websocket, task_id, message, request_id)
            else:
                await _send_result(websocket, task_id, command_type, False, f"unsupported command: {command_type}", request_id)
    except WebSocketDisconnect:
        pass
    finally:
        if attached:
            subscribers[task_id].discard(websocket)
