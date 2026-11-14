"""Canonical Laizysink wire models plus legacy DAG callback models.

``Event`` and ``EventType`` are intentionally retained for the existing
``dag_engine`` callback API. They are internal implementation events, not the
WebSocket contract. The server must translate them to the typed
``*Event``/``WireEvent`` models before sending anything to a client.
"""

from datetime import date, datetime
from enum import StrEnum
from typing import Annotated, Any, Literal, TypeAlias

from pydantic import BaseModel, ConfigDict, Field, HttpUrl


class ContractModel(BaseModel):
    model_config = ConfigDict(extra="forbid")


class NodeStatus(StrEnum):
    WAITING = "WAITING"
    RUNNING = "RUNNING"
    BLOCKED_USER = "BLOCKED_USER"
    COMPLETED = "COMPLETED"
    PARTIAL = "PARTIAL"
    STALE = "STALE"
    FAILED = "FAILED"


class FreshnessTag(StrEnum):
    USED = "USED"
    STALE_QUERIED = "STALE_QUERIED"
    ENTIRELY_STALE = "ENTIRELY_STALE"


class WorkTaskType(StrEnum):
    DECOMPOSE = "DECOMPOSE"
    EXA_SEARCH = "EXA_SEARCH"
    DATA_BARRIER = "DATA_BARRIER"
    MERGE = "MERGE"
    HITL_BROWSER = "HITL_BROWSER"
    KEYCHAIN_REF = "KEYCHAIN_REF"


class WorkTask(ContractModel):
    id: str
    parent_node_id: str
    task_type: WorkTaskType
    label: str
    status: NodeStatus


class ExaCitation(ContractModel):
    """Evidence returned by Exa; provenance fields are optional until supplied."""

    id: str
    domain: str
    url: HttpUrl
    title: str
    favicon_url: HttpUrl | None = None
    highlighted_quote: str
    provider: Literal["exa"] = "exa"
    provider_request_id: str | None = None
    retrieved_at: datetime | None = None
    verification_badge: str | None = None
    selected: bool
    rejection_reason: str | None = None


# Existing DAG and Kotlin code use ExaSource. Keep this Python alias while the
# canonical wire/schema name remains ExaCitation.
ExaSource = ExaCitation


class KeychainRef(ContractModel):
    domain: str
    vault_ref: str = Field(pattern=r"^vault://")
    masked_username: str | None = None


class SubtaskNode(ContractModel):
    id: str
    task_id: str
    title: str
    status: NodeStatus
    freshness_tag: FreshnessTag
    parent_ids: list[str]
    plain_summary: str
    highlight_chips: list[str]
    inherited_context: dict[str, str]
    decision_rationale: str
    work_tasks: list[WorkTask]
    sources: list[ExaCitation]


# Existing DAG/Kotlin code calls this graph layer WorkNode. The wire contract
# names it SubtaskNode to distinguish it from the individual WorkTask records.
WorkNode = SubtaskNode


class DataBarrier(ContractModel):
    barrier_id: str
    task_id: str
    source_node_id: str
    badge_text: str | None = None
    question: str
    options: list[str] = Field(min_length=3, max_length=4)
    allow_other_voice_text: bool


class TemporalMemoryItem(ContractModel):
    id: str
    task_id: str
    key: str
    value: str
    event_start_date: date | None = None
    event_end_date: date | None = None
    created_at: datetime
    freshness_tag: FreshnessTag


class TaskGraph(ContractModel):
    """Full authoritative task snapshot sent in TASK_STATE_SYNC.payload."""

    id: str
    prompt: str
    status: NodeStatus
    nodes: list[SubtaskNode]
    barriers: list[DataBarrier] = Field(default_factory=list)
    memory: list[TemporalMemoryItem] = Field(default_factory=list)


# AGENTS.md describes the same snapshot as TaskState; retain TaskGraph for
# existing backend/Kotlin callers while giving the canonical concept a name.
TaskState = TaskGraph


class CreateTask(ContractModel):
    prompt: str = Field(min_length=1)
    attachments: list[str] = Field(default_factory=list)
    use_active_memory: bool = True
    request_id: str | None = None


class ResolveBarrier(ContractModel):
    task_id: str
    barrier_id: str
    answer: str = Field(min_length=1)
    request_id: str | None = None


class KeychainAuthComplete(ContractModel):
    task_id: str
    tab_id: str
    keychain_ref: str = Field(pattern=r"^vault://")
    request_id: str | None = None


class ExpireMemory(ContractModel):
    memory_id: str
    request_id: str | None = None


class HitlTabRequired(ContractModel):
    task_id: str
    tab_id: str
    url: HttpUrl
    target_selector: str
    tooltip_text: str
    domain: str


class CommandType(StrEnum):
    CREATE_TASK = "CREATE_TASK"
    RESOLVE_BARRIER = "RESOLVE_BARRIER"
    KEYCHAIN_AUTH_COMPLETE = "KEYCHAIN_AUTH_COMPLETE"
    EXPIRE_MEMORY = "EXPIRE_MEMORY"


class CommandResult(ContractModel):
    command_type: str
    accepted: bool
    message: str | None = None
    request_id: str | None = None


class CreateTaskCommand(CreateTask):
    type: Literal["CREATE_TASK"] = "CREATE_TASK"


class ResolveBarrierCommand(ResolveBarrier):
    type: Literal["RESOLVE_BARRIER"] = "RESOLVE_BARRIER"


class KeychainAuthCompleteCommand(KeychainAuthComplete):
    type: Literal["KEYCHAIN_AUTH_COMPLETE"] = "KEYCHAIN_AUTH_COMPLETE"


class ExpireMemoryCommand(ExpireMemory):
    type: Literal["EXPIRE_MEMORY"] = "EXPIRE_MEMORY"


ClientCommand: TypeAlias = Annotated[
    CreateTaskCommand | ResolveBarrierCommand | KeychainAuthCompleteCommand | ExpireMemoryCommand,
    Field(discriminator="type"),
]


class ServerEventType(StrEnum):
    TASK_STATE_SYNC = "TASK_STATE_SYNC"
    BARRIER_TRIGGERED = "BARRIER_TRIGGERED"
    HITL_TAB_REQUIRED = "HITL_TAB_REQUIRED"
    COMMAND_RESULT = "COMMAND_RESULT"


class ServerEvent(ContractModel):
    """Base for events on the wire; use one of the payload-typed subclasses."""

    type: ServerEventType
    task_id: str
    payload: Any


class TaskStateSyncEvent(ServerEvent):
    type: Literal["TASK_STATE_SYNC"] = "TASK_STATE_SYNC"
    payload: TaskGraph


class BarrierTriggeredEvent(ServerEvent):
    type: Literal["BARRIER_TRIGGERED"] = "BARRIER_TRIGGERED"
    payload: DataBarrier


class HitlTabRequiredEvent(ServerEvent):
    type: Literal["HITL_TAB_REQUIRED"] = "HITL_TAB_REQUIRED"
    payload: HitlTabRequired


class CommandResultEvent(ServerEvent):
    type: Literal["COMMAND_RESULT"] = "COMMAND_RESULT"
    payload: CommandResult


WireEvent: TypeAlias = (
    TaskStateSyncEvent | BarrierTriggeredEvent | HitlTabRequiredEvent | CommandResultEvent
)


class ErrorResponse(ContractModel):
    detail: str


# Internal DAG callback types: deliberately not part of the canonical wire
# EventType enum in contracts/schema.json. backend/main.py performs conversion.
class EventType(StrEnum):
    TASK_CREATED = "TASK_CREATED"
    NODE_UPDATED = "NODE_UPDATED"
    DATA_BARRIER = "DATA_BARRIER"
    HITL_BROWSER = "HITL_BROWSER"
    TASK_COMPLETED = "TASK_COMPLETED"
    ERROR = "ERROR"


class Event(ContractModel):
    type: EventType
    task_id: str
    payload: dict[str, Any]
