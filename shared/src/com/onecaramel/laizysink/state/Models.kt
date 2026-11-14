package com.onecaramel.laizysink.state

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
enum class NodeStatus { WAITING, RUNNING, BLOCKED_USER, COMPLETED, STALE, FAILED }

@Serializable
enum class FreshnessTag { USED, STALE_QUERIED, ENTIRELY_STALE }

@Serializable
enum class WorkTaskType { DECOMPOSE, EXA_SEARCH, DATA_BARRIER, MERGE, HITL_BROWSER, KEYCHAIN_REF }

@Serializable
data class WorkTask(val id: String, val parent_node_id: String, val task_type: WorkTaskType, val label: String, val status: NodeStatus)

@Serializable
data class ExaSource(
    val id: String,
    val domain: String,
    val url: String,
    val title: String,
    val favicon_url: String? = null,
    val highlighted_quote: String,
    val verification_badge: String? = null,
    val selected: Boolean,
    val rejection_reason: String? = null,
)

@Serializable
data class KeychainRef(val domain: String, val vault_ref: String, val masked_username: String? = null)

@Serializable
data class WorkNode(
    val id: String,
    val task_id: String,
    val title: String,
    val status: NodeStatus,
    val freshness_tag: FreshnessTag,
    val parent_ids: List<String>,
    val plain_summary: String,
    val highlight_chips: List<String>,
    val inherited_context: Map<String, String>,
    val decision_rationale: String,
    val work_tasks: List<WorkTask>,
    val sources: List<ExaSource>,
)

@Serializable
data class DataBarrier(
    val barrier_id: String,
    val task_id: String,
    val source_node_id: String,
    val badge_text: String? = null,
    val question: String,
    val options: List<String>,
    val allow_other_voice_text: Boolean,
)

@Serializable
data class TemporalMemoryItem(
    val id: String,
    val task_id: String,
    val key: String,
    val value: String,
    val event_start_date: String? = null,
    val event_end_date: String? = null,
    val created_at: String,
    val freshness_tag: FreshnessTag,
)

@Serializable
data class TaskGraph(
    val id: String,
    val prompt: String,
    val status: NodeStatus,
    val nodes: List<WorkNode> = emptyList(),
    val barriers: List<DataBarrier> = emptyList(),
    val memory: List<TemporalMemoryItem> = emptyList(),
)

@Serializable
enum class EventType { TASK_CREATED, NODE_UPDATED, DATA_BARRIER, HITL_BROWSER, TASK_COMPLETED, ERROR }

@Serializable
data class Event(val type: EventType, val task_id: String, val payload: JsonObject = JsonObject(emptyMap()))


