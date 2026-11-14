from datetime import date, datetime
from enum import StrEnum
from typing import Any

from pydantic import BaseModel, ConfigDict, Field, HttpUrl


class ContractModel(BaseModel):
    model_config = ConfigDict(extra="forbid")


class NodeStatus(StrEnum):
    WAITING = "WAITING"
    RUNNING = "RUNNING"
    BLOCKED_USER = "BLOCKED_USER"
    COMPLETED = "COMPLETED"
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


class ExaSource(ContractModel):
    id: str
    domain: str
    url: HttpUrl
    title: str
    favicon_url: HttpUrl | None = None
    highlighted_quote: str
    verification_badge: str | None = None
    selected: bool
    rejection_reason: str | None = None


class KeychainRef(ContractModel):
    domain: str
    vault_ref: str = Field(pattern=r"^vault://")
    masked_username: str | None = None


class WorkNode(ContractModel):
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
    sources: list[ExaSource]


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
    id: str
    prompt: str
    status: NodeStatus
    nodes: list[WorkNode]
    barriers: list[DataBarrier] = Field(default_factory=list)
    memory: list[TemporalMemoryItem] = Field(default_factory=list)


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

