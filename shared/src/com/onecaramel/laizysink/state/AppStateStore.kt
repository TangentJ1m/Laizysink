package com.onecaramel.laizysink.state

import com.onecaramel.laizysink.getPlatform
import com.onecaramel.laizysink.HitlTabRequest
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
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive

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

enum class CommandStatus { QUEUED, FAILED }

/** Describes local queueing only; the current wire protocol has no command acknowledgement. */
data class CommandOutcome(
    val commandId: Long,
    val type: String,
    val taskId: String? = null,
    val status: CommandStatus,
    val message: String,
)

enum class PromptStatus { IDLE, SUBMITTING, SUBMITTED, ACCEPTED, FAILED }

data class PromptSubmissionState(
    val prompt: String = "",
    val taskId: String? = null,
    val status: PromptStatus = PromptStatus.IDLE,
    val message: String? = null,
)

enum class TranscriptionStatus { IDLE, TRANSCRIBING, COMPLETED, UNAVAILABLE, FAILED }

data class TranscriptionState(
    val status: TranscriptionStatus = TranscriptionStatus.IDLE,
    val text: String? = null,
    val message: String? = null,
)

enum class MemoryActionType { EXPIRE, OVERRIDE, REFRESH }
enum class MemoryActionStatus { QUEUED, UNSUPPORTED, FAILED }

data class MemoryActionOutcome(
    val memoryId: String,
    val type: MemoryActionType,
    val status: MemoryActionStatus,
    val message: String,
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
                // Existing-task attachment sends its complete TaskGraph in this payload.
                // Preserve it instead of replacing it with an empty RUNNING graph.
                val graph = snapshot(event.payload, json)
                    ?: TaskGraph(
                        id = event.task_id,
                        prompt = event.payload.string("prompt").orEmpty(),
                        status = NodeStatus.RUNNING,
                    )
                put(graph)
                if (_selectedTaskId.value == null) _selectedTaskId.value = graph.id
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
                val graph = snapshot(event.payload, json)
                    ?: current?.copy(status = NodeStatus.COMPLETED)
                    ?: return null
                put(graph)
            }

            EventType.ERROR -> Unit
        }
        return null
    }

    private fun put(graph: TaskGraph) {
        _tasks.value = _tasks.value + (graph.id to graph)
    }

    private fun snapshot(payload: JsonObject, json: Json): TaskGraph? {
        val candidate = payload["task_graph"]
            ?: payload["task_state"]
            ?: payload.takeIf { "id" in it && "nodes" in it }
            ?: return null
        return runCatching { json.decodeFromJsonElement<TaskGraph>(candidate) }.getOrNull()
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
class TemporalMemoryStore(taskStore: TaskDagStore, scope: CoroutineScope) {
    private val _items = MutableStateFlow<List<TemporalMemoryItem>>(emptyList())
    val items: StateFlow<List<TemporalMemoryItem>> = _items.asStateFlow()

    init {
        scope.launch {
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
    private val httpClient = httpClient
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val json = Json { ignoreUnknownKeys = true }
    private val socketClient = LaizysinkSocketClient(httpClient, json)
    private val outgoing = Channel<String>(Channel.BUFFERED)
    private var sessionJob: Job? = null
    private var nextCommandId = 0L

    val taskDagStore = TaskDagStore()
    val backgroundWebViewPool = BackgroundWebViewPool()
    val temporalMemoryStore = TemporalMemoryStore(taskDagStore, scope)

    private val _connection = MutableStateFlow(ConnectionState())
    val connection: StateFlow<ConnectionState> = _connection.asStateFlow()

    private val _activeHitlRequest = MutableStateFlow<HitlTabRequest?>(null)
    val activeHitlRequest: StateFlow<HitlTabRequest?> = _activeHitlRequest.asStateFlow()

    private val _stagedHitlRequests = MutableStateFlow<Map<String, HitlTabRequest>>(emptyMap())
    val stagedHitlRequests: StateFlow<Map<String, HitlTabRequest>> = _stagedHitlRequests.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _commandOutcome = MutableStateFlow<CommandOutcome?>(null)
    val commandOutcome: StateFlow<CommandOutcome?> = _commandOutcome.asStateFlow()

    private val _promptSubmission = MutableStateFlow(PromptSubmissionState())
    val promptSubmission: StateFlow<PromptSubmissionState> = _promptSubmission.asStateFlow()

    private val _transcription = MutableStateFlow(TranscriptionState())
    val transcription: StateFlow<TranscriptionState> = _transcription.asStateFlow()

    private val _memoryActionOutcome = MutableStateFlow<MemoryActionOutcome?>(null)
    val memoryActionOutcome: StateFlow<MemoryActionOutcome?> = _memoryActionOutcome.asStateFlow()

    fun close() {
        sessionJob?.cancel()
        scope.coroutineContext[Job]?.cancel()
        outgoing.close()
        httpClient.close()
    }

    fun createTask(prompt: String, attachments: List<String> = emptyList(), useActiveMemory: Boolean = true) {
        val cleaned = prompt.trim()
        if (cleaned.isEmpty()) {
            _promptSubmission.value = PromptSubmissionState(status = PromptStatus.FAILED, message = "Enter a prompt to start a task.")
            return
        }
        taskDagStore.select(null)
        _promptSubmission.value = PromptSubmissionState(prompt = cleaned, status = PromptStatus.SUBMITTING)
        ensureConnected()
        val outcome = send(JsonObject(mapOf(
            "type" to JsonPrimitive("CREATE_TASK"),
            "prompt" to JsonPrimitive(cleaned),
            "attachments" to JsonArray(attachments.map(::JsonPrimitive)),
            "use_active_memory" to JsonPrimitive(useActiveMemory),
        )), commandType = "CREATE_TASK")
        if (_promptSubmission.value.status == PromptStatus.SUBMITTING) {
            _promptSubmission.value = PromptSubmissionState(
                prompt = cleaned,
                status = if (outcome.status == CommandStatus.QUEUED) PromptStatus.SUBMITTED else PromptStatus.FAILED,
                message = outcome.message,
            )
        }
    }

    fun resolveBarrier(taskId: String, barrierId: String, answer: String) {
        if (answer.isBlank()) return
        ensureConnected()
        send(JsonObject(mapOf(
            "type" to JsonPrimitive("RESOLVE_BARRIER"),
            "task_id" to JsonPrimitive(taskId),
            "barrier_id" to JsonPrimitive(barrierId),
            "answer" to JsonPrimitive(answer.trim()),
        )), commandType = "RESOLVE_BARRIER", taskId = taskId)
    }

    /** Sends the documented memory-expiration command and leaves items unchanged until server state confirms it. */
    fun expireMemory(memoryId: String) {
        if (memoryId.isBlank()) return
        ensureConnected()
        val outcome = send(JsonObject(mapOf(
            "type" to JsonPrimitive("EXPIRE_MEMORY"),
            "memory_id" to JsonPrimitive(memoryId),
        )), commandType = "EXPIRE_MEMORY")
        _memoryActionOutcome.value = MemoryActionOutcome(
            memoryId = memoryId,
            type = MemoryActionType.EXPIRE,
            status = if (outcome.status == CommandStatus.QUEUED) MemoryActionStatus.QUEUED else MemoryActionStatus.FAILED,
            message = outcome.message,
        )
    }

    /** Records a requested override without fabricating an updated server-owned memory item. */
    fun overrideMemory(memoryId: String, value: String) {
        if (memoryId.isBlank() || value.isBlank()) return
        _memoryActionOutcome.value = MemoryActionOutcome(
            memoryId = memoryId,
            type = MemoryActionType.OVERRIDE,
            status = MemoryActionStatus.UNSUPPORTED,
            message = "Memory overrides are not part of the current backend command contract.",
        )
    }

    /** Records a requested refresh; the backend contract currently has no refresh command. */
    fun refreshMemory(memoryId: String) {
        if (memoryId.isBlank()) return
        _memoryActionOutcome.value = MemoryActionOutcome(
            memoryId = memoryId,
            type = MemoryActionType.REFRESH,
            status = MemoryActionStatus.UNSUPPORTED,
            message = "Memory refresh is not part of the current backend command contract.",
        )
    }

    /** State hooks for the voice client; the transcription endpoint returns only real provider text. */
    fun transcriptionStarted() {
        _transcription.value = TranscriptionState(status = TranscriptionStatus.TRANSCRIBING)
    }

    fun transcriptionCompleted(text: String) {
        val cleaned = text.trim()
        _transcription.value = if (cleaned.isEmpty()) {
            TranscriptionState(status = TranscriptionStatus.FAILED, message = "Transcription returned no text. Try recording again.")
        } else {
            TranscriptionState(status = TranscriptionStatus.COMPLETED, text = cleaned)
        }
    }

    fun transcriptionUnavailable(message: String) {
        _transcription.value = TranscriptionState(
            status = TranscriptionStatus.UNAVAILABLE,
            message = message.takeIf { it.isNotBlank() } ?: "Voice transcription is unavailable. Type your prompt instead.",
        )
    }

    fun transcriptionFailed(message: String) {
        _transcription.value = TranscriptionState(
            status = TranscriptionStatus.FAILED,
            message = message.takeIf { it.isNotBlank() } ?: "Transcription failed. Try again or type your prompt.",
        )
    }

    /** Sends only an opaque local vault reference; raw credentials never enter this store. */
    fun completeKeychainAuth(tabId: String, keychainRef: String) {
        if (!keychainRef.startsWith("vault://")) return
        val request = _activeHitlRequest.value ?: return
        if (tabId != request.tabId) return
        ensureConnected()
        val outcome = send(JsonObject(mapOf(
            "type" to JsonPrimitive("KEYCHAIN_AUTH_COMPLETE"),
            "task_id" to JsonPrimitive(request.taskId),
            "tab_id" to JsonPrimitive(tabId),
            "keychain_ref" to JsonPrimitive(keychainRef),
        )), commandType = "KEYCHAIN_AUTH_COMPLETE", taskId = request.taskId)
        if (outcome.status == CommandStatus.QUEUED) {
            backgroundWebViewPool.resume(tabId)
            removeHitlRequest(tabId)
        }
    }

    fun dismissHitl(tabId: String) {
        backgroundWebViewPool.resume(tabId)
        removeHitlRequest(tabId)
    }

    private fun removeHitlRequest(tabId: String) {
        _stagedHitlRequests.value = _stagedHitlRequests.value - tabId
        if (_activeHitlRequest.value?.tabId == tabId) {
            _activeHitlRequest.value = _stagedHitlRequests.value.values.firstOrNull()
        }
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
                            _stagedHitlRequests.value = _stagedHitlRequests.value + (request.tabId to request)
                            if (_activeHitlRequest.value == null) _activeHitlRequest.value = request
                        }
                        if (event.type == EventType.TASK_CREATED || event.type == EventType.TASK_COMPLETED) {
                            val graph = taskDagStore.tasks.value[event.task_id]
                            val pendingPrompt = _promptSubmission.value
                            if (graph != null && pendingPrompt.status == PromptStatus.SUBMITTED && graph.prompt == pendingPrompt.prompt) {
                                _promptSubmission.value = pendingPrompt.copy(
                                    taskId = graph.id,
                                    status = PromptStatus.ACCEPTED,
                                    message = "Task received by the backend.",
                                )
                            }
                        }
                        if (event.type == EventType.ERROR) {
                            val message = event.payload.string("message")
                                ?: event.payload.string("detail")
                                ?: "The backend could not complete this action."
                            _lastError.value = message
                            val prompt = _promptSubmission.value
                            if (prompt.status == PromptStatus.SUBMITTED && prompt.taskId == null) {
                                _promptSubmission.value = prompt.copy(status = PromptStatus.FAILED, message = message)
                            }
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

    private fun send(message: JsonObject, commandType: String, taskId: String? = null): CommandOutcome {
        val commandId = ++nextCommandId
        val result = outgoing.trySend(json.encodeToString(JsonObject.serializer(), message))
        val outcome = if (result.isSuccess) {
            CommandOutcome(commandId, commandType, taskId, CommandStatus.QUEUED, "Request queued locally; waiting for backend confirmation.")
        } else {
            val error = "Unable to queue the request. Reconnect and try again."
            _lastError.value = error
            CommandOutcome(commandId, commandType, taskId, CommandStatus.FAILED, error)
        }
        _commandOutcome.value = outcome
        return outcome
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
