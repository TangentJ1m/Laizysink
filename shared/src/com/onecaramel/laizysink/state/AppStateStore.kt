package com.onecaramel.laizysink.state

import com.onecaramel.laizysink.getPlatform
import com.onecaramel.laizysink.network.LaizysinkSocketClient
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class HitlTabRequest(
    val taskId: String,
    val tabId: String,
    val url: String,
    val targetSelector: String,
    val tooltipText: String,
    val domain: String,
)

data class BackgroundTabInfo(
    val taskId: String,
    val tabId: String,
    val url: String,
    val status: String,
)

enum class ConnectionStatus { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

data class ConnectionState(
    val status: ConnectionStatus = ConnectionStatus.DISCONNECTED,
    val message: String = "Connect to the Laizysink backend to research live sources.",
)

/** Reactive task graph projection, updated exclusively from server events. */
class TaskDagStore {
    private val _tasks = MutableStateFlow<Map<String, TaskGraph>>(emptyMap())
    val tasks: StateFlow<Map<String, TaskGraph>> = _tasks.asStateFlow()

    private val _selectedTaskId = MutableStateFlow<String?>(null)
    val selectedTaskId: StateFlow<String?> = _selectedTaskId.asStateFlow()

    fun select(taskId: String?) {
        _selectedTaskId.value = taskId
    }

    fun apply(event: Event, json: Json): HitlTabRequest? {
        val current = _tasks.value[event.task_id]
        when (event.type) {
            EventType.TASK_CREATED -> {
                val prompt = event.payload.string("prompt").orEmpty()
                val graph = TaskGraph(
                    id = event.task_id,
                    prompt = prompt,
                    status = NodeStatus.RUNNING,
                )
                put(graph)
                if (_selectedTaskId.value == null) _selectedTaskId.value = event.task_id
            }

            EventType.NODE_UPDATED -> {
                val nodeJson = event.payload["node"] ?: return null
                val node = runCatching { json.decodeFromJsonElement<WorkNode>(nodeJson) }.getOrNull() ?: return null
                val graph = current ?: TaskGraph(id = event.task_id, prompt = "", status = node.status)
                put(graph.copy(nodes = graph.nodes.filterNot { it.id == node.id } + node))
            }

            EventType.DATA_BARRIER -> {
                val barrierJson = event.payload["barrier"] ?: return null
                val barrier = runCatching { json.decodeFromJsonElement<DataBarrier>(barrierJson) }.getOrNull() ?: return null
                val graph = current ?: TaskGraph(id = event.task_id, prompt = "", status = NodeStatus.BLOCKED_USER)
                put(graph.copy(status = NodeStatus.BLOCKED_USER, barriers = graph.barriers.filterNot { it.barrier_id == barrier.barrier_id } + barrier))
            }

            EventType.HITL_BROWSER -> return event.hitlRequest()

            EventType.TASK_COMPLETED -> {
                val graphJson = event.payload["task_graph"] ?: return null
                val graph = runCatching { json.decodeFromJsonElement<TaskGraph>(graphJson) }.getOrNull() ?: return null
                put(graph)
            }

            EventType.ERROR -> Unit
        }
        return null
    }

    private fun put(graph: TaskGraph) {
        _tasks.value = _tasks.value + (graph.id to graph)
    }
}

/** Tracks only tabs for which the backend has emitted a real HITL request. */
class BackgroundWebViewPool {
    private val _tabs = MutableStateFlow<Map<String, BackgroundTabInfo>>(emptyMap())
    val tabs: StateFlow<Map<String, BackgroundTabInfo>> = _tabs.asStateFlow()

    fun pause(request: HitlTabRequest) {
        _tabs.value = _tabs.value + (request.tabId to BackgroundTabInfo(request.taskId, request.tabId, request.url, "BLOCKED_USER"))
    }

    fun resume(tabId: String) {
        _tabs.value = _tabs.value - tabId
    }
}

/** Memory is projected from server-owned task graphs; the client does not invent facts. */
class TemporalMemoryStore(taskStore: TaskDagStore) {
    private val _items = MutableStateFlow<List<TemporalMemoryItem>>(emptyList())
    val items: StateFlow<List<TemporalMemoryItem>> = _items.asStateFlow()

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            taskStore.tasks.collect { tasks ->
                _items.value = tasks.values.flatMap { it.memory }.distinctBy { it.id }.sortedByDescending { it.created_at }
            }
        }
    }
}

/** Owns the live WebSocket session and fans canonical server events into shared stores. */
class AppStateStore(
    httpClient: HttpClient,
    private val webSocketUrl: String = getPlatform().backendWebSocketUrl,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val json = Json { ignoreUnknownKeys = true }
    private val socketClient = LaizysinkSocketClient(httpClient, json)
    private val outgoing = Channel<String>(Channel.BUFFERED)
    private var sessionJob: Job? = null

    val taskDagStore = TaskDagStore()
    val backgroundWebViewPool = BackgroundWebViewPool()
    val temporalMemoryStore = TemporalMemoryStore(taskDagStore)

    private val _connection = MutableStateFlow(ConnectionState())
    val connection: StateFlow<ConnectionState> = _connection.asStateFlow()

    private val _activeHitlRequest = MutableStateFlow<HitlTabRequest?>(null)
    val activeHitlRequest: StateFlow<HitlTabRequest?> = _activeHitlRequest.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    fun createTask(prompt: String, attachments: List<String> = emptyList(), useActiveMemory: Boolean = true) {
        val cleaned = prompt.trim()
        if (cleaned.isEmpty()) return
        taskDagStore.select(null)
        ensureConnected()
        send(JsonObject(mapOf(
            "type" to JsonPrimitive("CREATE_TASK"),
            "prompt" to JsonPrimitive(cleaned),
            "attachments" to JsonArray(attachments.map(::JsonPrimitive)),
            "use_active_memory" to JsonPrimitive(useActiveMemory),
        )))
    }

    fun resolveBarrier(taskId: String, barrierId: String, answer: String) {
        if (answer.isBlank()) return
        ensureConnected()
        send(JsonObject(mapOf(
            "type" to JsonPrimitive("RESOLVE_BARRIER"),
            "task_id" to JsonPrimitive(taskId),
            "barrier_id" to JsonPrimitive(barrierId),
            "answer" to JsonPrimitive(answer.trim()),
        )))
    }

    /** Sends only an opaque local vault reference; raw credentials never enter this store. */
    fun completeKeychainAuth(tabId: String, keychainRef: String) {
        if (!keychainRef.startsWith("vault://")) return
        val request = _activeHitlRequest.value ?: return
        ensureConnected()
        send(JsonObject(mapOf(
            "type" to JsonPrimitive("KEYCHAIN_AUTH_COMPLETE"),
            "task_id" to JsonPrimitive(request.taskId),
            "tab_id" to JsonPrimitive(tabId),
            "keychain_ref" to JsonPrimitive(keychainRef),
        )))
        backgroundWebViewPool.resume(tabId)
        _activeHitlRequest.value = null
    }

    fun dismissHitl(tabId: String) {
        backgroundWebViewPool.resume(tabId)
        _activeHitlRequest.value = null
    }

    private fun ensureConnected() {
        if (sessionJob?.isActive == true) return
        sessionJob = scope.launch {
            while (isActive) {
                _connection.value = ConnectionState(ConnectionStatus.CONNECTING, "Connecting to the Laizysink backend…")
                try {
                    socketClient.events(webSocketUrl, outgoing.receiveAsFlow()).collect { event ->
                        _connection.value = ConnectionState(ConnectionStatus.CONNECTED, "Connected to the Laizysink backend.")
                        _lastError.value = null
                        taskDagStore.apply(event, json)?.let { request ->
                            backgroundWebViewPool.pause(request)
                            _activeHitlRequest.value = request
                        }
                        if (event.type == EventType.ERROR) {
                            _lastError.value = event.payload.string("message") ?: "The backend could not complete this action."
                        }
                    }
                    _connection.value = ConnectionState(ConnectionStatus.DISCONNECTED, "Backend connection closed.")
                } catch (error: Exception) {
                    val message = error.message?.takeIf { it.isNotBlank() } ?: "Check that the backend is running."
                    _connection.value = ConnectionState(ConnectionStatus.ERROR, "Backend unavailable: $message")
                    delay(2_000)
                }
            }
        }
    }

    private fun send(message: JsonObject) {
        val result = outgoing.trySend(json.encodeToString(JsonObject.serializer(), message))
        if (result.isFailure) _lastError.value = "Unable to queue the request. Please try again."
    }
}

private fun Event.hitlRequest(): HitlTabRequest? {
    val tabId = payload.string("tab_id") ?: return null
    val url = payload.string("url") ?: return null
    return HitlTabRequest(
        taskId = task_id,
        tabId = tabId,
        url = url,
        targetSelector = payload.string("target_selector").orEmpty(),
        tooltipText = payload.string("tooltip_text").orEmpty(),
        domain = payload.string("domain").orEmpty(),
    )
}

private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.content
