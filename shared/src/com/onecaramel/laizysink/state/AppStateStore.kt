package com.onecaramel.laizysink.state

import com.onecaramel.laizysink.HitlTabRequest
import com.onecaramel.laizysink.getPlatform
import com.onecaramel.laizysink.network.CommandOutcome as TransportCommandOutcome
import com.onecaramel.laizysink.network.CommandOutcomeStatus as TransportCommandOutcomeStatus
import com.onecaramel.laizysink.network.LaizysinkSocketClient
import com.onecaramel.laizysink.network.ServerEvent
import com.onecaramel.laizysink.network.TaskConnectionState
import com.onecaramel.laizysink.network.TaskConnectionStatus
import com.onecaramel.laizysink.network.TaskSocketSession
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlin.random.Random

data class BackgroundTabInfo(
    val taskId: String,
    val tabId: String,
    val url: String,
    val status: String,
)

enum class ConnectionStatus { DISCONNECTED, CONNECTING, CONNECTED, ERROR, CLOSED }

data class ConnectionState(
    val status: ConnectionStatus = ConnectionStatus.DISCONNECTED,
    val message: String = "Connect to the Laizysink backend to research live sources.",
)

enum class ClientCommandStatus { QUEUED, ACCEPTED, REJECTED, DELIVERY_UNKNOWN }

data class ClientCommandOutcome(
    val requestId: String,
    val commandType: String,
    val status: ClientCommandStatus,
    val message: String? = null,
    val taskId: String? = null,
)

enum class PromptSubmissionStatus { IDLE, QUEUED, ACCEPTED, TASK_CREATED, REJECTED, DELIVERY_UNKNOWN }

data class PromptSubmissionState(
    val requestId: String? = null,
    val status: PromptSubmissionStatus = PromptSubmissionStatus.IDLE,
    val taskId: String? = null,
    val message: String? = null,
)

enum class TranscriptionStatus { IDLE, UPLOADING, SUCCEEDED, FAILED }

data class TranscriptionState(
    val status: TranscriptionStatus = TranscriptionStatus.IDLE,
    val text: String? = null,
    val message: String? = null,
)

/** Reactive task graph projection. Full server snapshots are authoritative and replace each task. */
class TaskDagStore {
    private val _tasks = MutableStateFlow<Map<String, TaskGraph>>(emptyMap())
    val tasks: StateFlow<Map<String, TaskGraph>> = _tasks.asStateFlow()

    private val _selectedTaskId = MutableStateFlow<String?>(null)
    val selectedTaskId: StateFlow<String?> = _selectedTaskId.asStateFlow()

    /** Raw payloads keep newer snapshots inspectable when an older Kotlin model cannot decode them. */
    private val _snapshotPayloads = MutableStateFlow<Map<String, JsonObject>>(emptyMap())
    val snapshotPayloads: StateFlow<Map<String, JsonObject>> = _snapshotPayloads.asStateFlow()

    /** Compatibility issues are surfaced while the last valid typed graph remains available. */
    private val _snapshotIssues = MutableStateFlow<Map<String, String>>(emptyMap())
    val snapshotIssues: StateFlow<Map<String, String>> = _snapshotIssues.asStateFlow()

    private val _eventIssues = MutableStateFlow<Map<String, String>>(emptyMap())
    val eventIssues: StateFlow<Map<String, String>> = _eventIssues.asStateFlow()

    private val _activeBarriers = MutableStateFlow<Map<String, List<DataBarrier>>>(emptyMap())
    val activeBarriers: StateFlow<Map<String, List<DataBarrier>>> = _activeBarriers.asStateFlow()

    private val _citations = MutableStateFlow<Map<String, List<ExaSource>>>(emptyMap())
    val citations: StateFlow<Map<String, List<ExaSource>>> = _citations.asStateFlow()

    fun select(taskId: String?) {
        _selectedTaskId.value = taskId
    }

    fun apply(event: ServerEvent, json: Json): HitlTabRequest? = when (event.type) {
        "TASK_STATE_SYNC" -> {
            applySnapshot(event, json)
            null
        }

        "BARRIER_TRIGGERED" -> {
            applyBarrier(event, json)
            null
        }

        "HITL_TAB_REQUIRED" -> event.hitlRequest()
        else -> null
    }

    fun barrierFor(taskId: String): List<DataBarrier> = _activeBarriers.value[taskId].orEmpty()

    fun memoryOwnerTaskId(memoryId: String): String? =
        _tasks.value.values.asSequence()
            .flatMap { it.memory.asSequence() }
            .firstOrNull { it.id == memoryId }
            ?.task_id

    private fun applySnapshot(event: ServerEvent, json: Json) {
        val snapshot = (event.payload["task_graph"] as? JsonObject) ?: event.payload
        _snapshotPayloads.update { it + (event.task_id to snapshot) }

        val parsed = runCatching { json.decodeFromJsonElement<TaskGraph>(snapshot) }
        val graph = parsed.getOrNull()
        val issue = when {
            event.task_id.isBlank() -> "Task snapshot did not include a task ID."
            graph == null -> "Task snapshot could not be decoded by this client; update the shared model to match the server schema."
            graph.id != event.task_id -> "Task snapshot ID did not match its WebSocket task ID."
            else -> null
        }

        if (issue != null) {
            _snapshotIssues.update { it + (event.task_id to issue) }
            return
        }

        val acceptedGraph = graph ?: return
        _snapshotIssues.update { it - event.task_id }
        put(acceptedGraph)
    }

    private fun applyBarrier(event: ServerEvent, json: Json) {
        val barrier = runCatching { json.decodeFromJsonElement<DataBarrier>(event.payload) }.getOrNull()
        if (barrier == null) {
            _eventIssues.update { it + (event.task_id to "The backend sent an incomplete data barrier.") }
            return
        }
        if (barrier.task_id != event.task_id) {
            _eventIssues.update { it + (event.task_id to "Data barrier task ID did not match its WebSocket task ID.") }
            return
        }
        _eventIssues.update { it - event.task_id }

        val graph = _tasks.value[event.task_id]
        val next = graph?.barriers.orEmpty()
            .filterNot { it.barrier_id == barrier.barrier_id } + barrier
        _activeBarriers.update { it + (event.task_id to next) }
        if (graph != null) put(graph.copy(barriers = next))
    }

    private fun put(graph: TaskGraph) {
        _tasks.update { it + (graph.id to graph) }
        _activeBarriers.update { it + (graph.id to graph.barriers) }
        _citations.update { current ->
            current + (graph.id to graph.nodes.flatMap { it.sources })
        }
    }
}

/** Tracks tabs for which the backend has emitted a real HITL request. */
class BackgroundWebViewPool {
    private val _tabs = MutableStateFlow<Map<String, BackgroundTabInfo>>(emptyMap())
    val tabs: StateFlow<Map<String, BackgroundTabInfo>> = _tabs.asStateFlow()

    fun pause(request: HitlTabRequest) {
        _tabs.update {
            it + (request.tabId to BackgroundTabInfo(request.taskId, request.tabId, request.url, "BLOCKED_USER"))
        }
    }

    fun resume(tabId: String) {
        _tabs.update { it - tabId }
    }
}

/** Memory items remain server-owned; stale values stay visible with their explicit freshness tag. */
class TemporalMemoryStore(taskStore: TaskDagStore, scope: CoroutineScope) {
    private val _items = MutableStateFlow<List<TemporalMemoryItem>>(emptyList())
    val items: StateFlow<List<TemporalMemoryItem>> = _items.asStateFlow()

    private val _reusableItems = MutableStateFlow<List<TemporalMemoryItem>>(emptyList())
    val reusableItems: StateFlow<List<TemporalMemoryItem>> = _reusableItems.asStateFlow()

    private val _requiresRefresh = MutableStateFlow<List<TemporalMemoryItem>>(emptyList())
    val requiresRefresh: StateFlow<List<TemporalMemoryItem>> = _requiresRefresh.asStateFlow()

    private val _entirelyStale = MutableStateFlow<List<TemporalMemoryItem>>(emptyList())
    val entirelyStale: StateFlow<List<TemporalMemoryItem>> = _entirelyStale.asStateFlow()

    init {
        scope.launch {
            taskStore.tasks.collect { tasks ->
                val latestById = tasks.values
                    .flatMap { it.memory }
                    .groupBy { it.id }
                    .mapNotNull { (_, candidates) -> candidates.maxByOrNull { it.created_at } }
                    .sortedByDescending { it.created_at }
                _items.value = latestById
                _reusableItems.value = latestById.filter { it.freshness_tag == FreshnessTag.USED }
                _requiresRefresh.value = latestById.filter { it.freshness_tag == FreshnessTag.STALE_QUERIED }
                _entirelyStale.value = latestById.filter { it.freshness_tag == FreshnessTag.ENTIRELY_STALE }
            }
        }
    }

    fun taskIdFor(memoryId: String): String? = _items.value.firstOrNull { it.id == memoryId }?.task_id
}

/**
 * Owns task-specific WebSocket sessions and projects canonical server snapshots into shared state.
 * Audio capture/upload is owned by the platform/UI; this store only reports its real status/result.
 */
class AppStateStore(
    httpClient: HttpClient,
    private val webSocketUrl: String = getPlatform().backendWebSocketUrl,
) {
    private val httpClient = httpClient
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val json = Json { ignoreUnknownKeys = true }
    private val socketClient = LaizysinkSocketClient(httpClient, json)
    private val registryMutex = Mutex()
    private val taskSessions = mutableMapOf<String, TaskSessionRecord>()
    private val pendingCreateSessions = mutableMapOf<String, TaskSessionRecord>()
    private val allSessions = mutableMapOf<String, TaskSessionRecord>()

    val taskDagStore = TaskDagStore()
    val backgroundWebViewPool = BackgroundWebViewPool()
    val temporalMemoryStore = TemporalMemoryStore(taskDagStore, scope)

    private val _connection = MutableStateFlow(ConnectionState())
    val connection: StateFlow<ConnectionState> = _connection.asStateFlow()

    private val _taskConnections = MutableStateFlow<Map<String, ConnectionState>>(emptyMap())
    val taskConnections: StateFlow<Map<String, ConnectionState>> = _taskConnections.asStateFlow()

    private val _stagedHitlRequests = MutableStateFlow<Map<String, HitlTabRequest>>(emptyMap())
    val stagedHitlRequests: StateFlow<Map<String, HitlTabRequest>> = _stagedHitlRequests.asStateFlow()

    private val _activeHitlRequest = MutableStateFlow<HitlTabRequest?>(null)
    val activeHitlRequest: StateFlow<HitlTabRequest?> = _activeHitlRequest.asStateFlow()

    private val _commandOutcomes = MutableStateFlow<Map<String, ClientCommandOutcome>>(emptyMap())
    val commandOutcomes: StateFlow<Map<String, ClientCommandOutcome>> = _commandOutcomes.asStateFlow()

    private val _promptStatuses = MutableStateFlow<Map<String, PromptSubmissionState>>(emptyMap())
    val promptStatuses: StateFlow<Map<String, PromptSubmissionState>> = _promptStatuses.asStateFlow()

    private val _promptStatus = MutableStateFlow(PromptSubmissionState())
    val promptStatus: StateFlow<PromptSubmissionState> = _promptStatus.asStateFlow()

    private val _transcription = MutableStateFlow(TranscriptionState())
    val transcription: StateFlow<TranscriptionState> = _transcription.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _commandEffects = MutableStateFlow<Map<String, CommandEffect>>(emptyMap())

    fun close() {
        allSessions.values.toList().forEach { it.session.close() }
        scope.cancel()
        httpClient.close()
    }

    /** Starts one independent `/new` session per prompt; no local or demo TaskGraph is created. */
    fun createTask(prompt: String, attachments: List<String> = emptyList(), useActiveMemory: Boolean = true) {
        val cleaned = prompt.trim()
        if (cleaned.isEmpty()) {
            _lastError.value = "Enter a prompt before starting a task."
            return
        }

        val requestId = newRequestId()
        taskDagStore.select(null)
        val command = JsonObject(
            mapOf(
                "type" to JsonPrimitive("CREATE_TASK"),
                "request_id" to JsonPrimitive(requestId),
                "prompt" to JsonPrimitive(cleaned),
                "attachments" to JsonArray(attachments.map(::JsonPrimitive)),
                "use_active_memory" to JsonPrimitive(useActiveMemory),
            ),
        )
        updateCommand(ClientCommandOutcome(requestId, "CREATE_TASK", ClientCommandStatus.QUEUED))
        updatePrompt(PromptSubmissionState(requestId, PromptSubmissionStatus.QUEUED))

        scope.launch {
            runCatching {
                val record = openSession(taskId = null, creationRequestId = requestId)
                send(record, command, requestId, "CREATE_TASK")
            }.onFailure { error ->
                val message = error.message?.takeIf { it.isNotBlank() } ?: "Could not start a task connection."
                setCommandOutcome(requestId, ClientCommandStatus.REJECTED, message)
                updatePromptForRequest(requestId, PromptSubmissionStatus.REJECTED, message = message)
                _lastError.value = message
            }
        }
    }

    /** Opens or restores a task-specific socket and selects the task for the UI. */
    fun openTask(taskId: String) {
        if (taskId.isBlank()) return
        taskDagStore.select(taskId)
        scope.launch {
            runCatching { openSession(taskId, creationRequestId = null) }
                .onFailure { error ->
                    val message = error.message?.takeIf { it.isNotBlank() } ?: "Could not restore this task."
                    _lastError.value = message
                }
        }
    }

    fun selectTask(taskId: String?) {
        taskDagStore.select(taskId)
        if (!taskId.isNullOrBlank()) openTask(taskId)
    }

    fun resolveBarrier(taskId: String, barrierId: String, answer: String) {
        if (taskId.isBlank() || barrierId.isBlank() || answer.isBlank()) return
        sendTaskCommand(
            taskId = taskId,
            type = "RESOLVE_BARRIER",
            fields = mapOf(
                "task_id" to JsonPrimitive(taskId),
                "barrier_id" to JsonPrimitive(barrierId),
                "answer" to JsonPrimitive(answer.trim()),
            ),
        )
    }

    fun expireMemory(memoryId: String) {
        if (memoryId.isBlank()) return
        val taskId = temporalMemoryStore.taskIdFor(memoryId) ?: taskDagStore.selectedTaskId.value
        sendTaskCommand(
            taskId = taskId,
            type = "EXPIRE_MEMORY",
            fields = mapOf("memory_id" to JsonPrimitive(memoryId)),
        )
    }

    fun overrideMemory(memoryId: String, taskId: String, userValue: String) {
        if (memoryId.isBlank() || taskId.isBlank() || userValue.isBlank()) return
        sendTaskCommand(
            taskId = taskId,
            type = "OVERRIDE_MEMORY",
            fields = mapOf(
                "task_id" to JsonPrimitive(taskId),
                "memory_id" to JsonPrimitive(memoryId),
                "value" to JsonPrimitive(userValue.trim()),
            ),
        )
    }

    /** Requests a server-side live refresh. A verified value must come from A2/provider orchestration. */
    fun refreshMemory(memoryId: String, taskId: String) {
        if (memoryId.isBlank() || taskId.isBlank()) return
        sendTaskCommand(
            taskId = taskId,
            type = "REFRESH_MEMORY",
            fields = mapOf(
                "task_id" to JsonPrimitive(taskId),
                "memory_id" to JsonPrimitive(memoryId),
            ),
        )
    }

    /** Sends only an opaque local vault reference; the browser request remains staged until accepted. */
    fun completeKeychainAuth(tabId: String, keychainRef: String) {
        if (!keychainRef.startsWith("vault://")) {
            _lastError.value = "Keychain completion requires an opaque vault:// reference."
            return
        }
        val request = _stagedHitlRequests.value[tabId]
        if (request == null) {
            _lastError.value = "The staged browser request is no longer available."
            return
        }

        val requestId = newRequestId()
        updateCommand(ClientCommandOutcome(requestId, "KEYCHAIN_AUTH_COMPLETE", ClientCommandStatus.QUEUED, taskId = request.taskId))
        _commandEffects.update { it + (requestId to CommandEffect.CompleteHitl(tabId)) }
        val command = JsonObject(
            mapOf(
                "type" to JsonPrimitive("KEYCHAIN_AUTH_COMPLETE"),
                "request_id" to JsonPrimitive(requestId),
                "task_id" to JsonPrimitive(request.taskId),
                "tab_id" to JsonPrimitive(tabId),
                "keychain_ref" to JsonPrimitive(keychainRef),
            ),
        )
        scope.launch { sendTaskCommandToSession(request.taskId, command, requestId, "KEYCHAIN_AUTH_COMPLETE") }
    }

    /** Hides the active browser surface while retaining the server-staged request for later review. */
    fun dismissHitl(tabId: String) {
        if (_activeHitlRequest.value?.tabId == tabId) _activeHitlRequest.value = null
    }

    fun activateHitlRequest(tabId: String) {
        val request = _stagedHitlRequests.value[tabId] ?: return
        _activeHitlRequest.value = request
        taskDagStore.select(request.taskId)
        openTask(request.taskId)
    }

    /** UI calls these around a real transcription transport; this store never fabricates transcript text. */
    fun reportTranscriptionUploading() {
        _transcription.value = TranscriptionState(status = TranscriptionStatus.UPLOADING)
    }

    fun reportTranscriptionSucceeded(text: String) {
        val transcript = text.trim()
        _transcription.value = if (transcript.isEmpty()) {
            TranscriptionState(
                status = TranscriptionStatus.FAILED,
                message = "No speech was recognized. Record again or type your prompt.",
            )
        } else {
            TranscriptionState(status = TranscriptionStatus.SUCCEEDED, text = transcript)
        }
    }

    fun reportTranscriptionFailed(message: String) {
        _transcription.value = TranscriptionState(
            status = TranscriptionStatus.FAILED,
            message = message.trim().ifEmpty { "Transcription is unavailable. Type your prompt and try again later." },
        )
    }

    fun clearTranscriptionStatus() {
        _transcription.value = TranscriptionState()
    }

    private fun sendTaskCommand(taskId: String?, type: String, fields: Map<String, JsonElement>) {
        val requestId = newRequestId()
        updateCommand(ClientCommandOutcome(requestId, type, ClientCommandStatus.QUEUED, taskId = taskId))
        val command = JsonObject(fields + mapOf(
            "type" to JsonPrimitive(type),
            "request_id" to JsonPrimitive(requestId),
        ))
        scope.launch { sendTaskCommandToSession(taskId, command, requestId, type) }
    }

    private suspend fun sendTaskCommandToSession(
        taskId: String?,
        command: JsonObject,
        requestId: String,
        type: String,
    ) {
        if (taskId.isNullOrBlank()) {
            setCommandOutcome(requestId, ClientCommandStatus.REJECTED, "Open a task before sending this command.")
            _lastError.value = "Open a task before sending this command."
            return
        }
        runCatching {
            val record = openSession(taskId, creationRequestId = null)
            send(record, command, requestId, type)
        }.onFailure { error ->
            val message = error.message?.takeIf { it.isNotBlank() } ?: "Could not send $type."
            setCommandOutcome(requestId, ClientCommandStatus.REJECTED, message)
            _lastError.value = message
        }
    }

    private suspend fun openSession(taskId: String?, creationRequestId: String?): TaskSessionRecord {
        val (record, created) = registryMutex.withLock {
            if (taskId != null) {
                taskSessions[taskId]?.let { return@withLock it to false }
            }
            if (creationRequestId != null) {
                pendingCreateSessions[creationRequestId]?.let { return@withLock it to false }
            }

            val key = taskId?.let { "task:$it" } ?: "create:${creationRequestId ?: newRequestId()}"
            val session = socketClient.openTaskSession(webSocketUrl, taskId)
            val opened = TaskSessionRecord(key, taskId, creationRequestId, session)
            allSessions[key] = opened
            if (taskId != null) taskSessions[taskId] = opened
            if (creationRequestId != null) pendingCreateSessions[creationRequestId] = opened
            opened to true
        }
        if (created) observe(record)
        return record
    }

    private fun observe(record: TaskSessionRecord) {
        record.observerJobs += scope.launch {
            record.session.connectionState.collect { state -> publishConnection(record.key, state) }
        }
        record.observerJobs += scope.launch {
            record.session.events.collect { event -> handleEvent(record, event) }
        }
        record.observerJobs += scope.launch {
            record.session.commandOutcomes.collect { outcome -> handleCommandOutcome(record, outcome) }
        }
    }

    private suspend fun send(record: TaskSessionRecord, command: JsonObject, requestId: String, type: String) {
        val submission = record.session.send(command)
        if (!submission.queued) {
            val message = submission.message ?: "The backend command could not be queued."
            setCommandOutcome(requestId, ClientCommandStatus.REJECTED, message, record.taskId)
            if (type == "CREATE_TASK") updatePromptForRequest(requestId, PromptSubmissionStatus.REJECTED, message = message)
            _lastError.value = message
        }
    }

    private suspend fun handleEvent(record: TaskSessionRecord, event: ServerEvent) {
        val hitlRequest = taskDagStore.apply(event, json)
        when (event.type) {
            "TASK_STATE_SYNC" -> {
                if (event.task_id.isNotBlank()) bindTask(record, event.task_id)
                val issue = taskDagStore.snapshotIssues.value[event.task_id]
                if (issue != null) {
                    _lastError.value = issue
                    if (record.creationRequestId != null && event.task_id.isNotBlank()) {
                        taskDagStore.select(event.task_id)
                        updatePromptForRequest(
                            record.creationRequestId,
                            PromptSubmissionStatus.TASK_CREATED,
                            taskId = event.task_id,
                            message = issue,
                        )
                    }
                } else {
                    if (record.creationRequestId != null) {
                        taskDagStore.select(event.task_id)
                        updatePromptForRequest(
                            record.creationRequestId,
                            PromptSubmissionStatus.TASK_CREATED,
                            taskId = event.task_id,
                        )
                    } else if (taskDagStore.selectedTaskId.value == null) {
                        taskDagStore.select(event.task_id)
                    }
                    _lastError.value = null
                }
            }

            "HITL_TAB_REQUIRED" -> {
                if (hitlRequest == null) {
                    _lastError.value = "The backend sent an incomplete browser handoff request."
                } else {
                    stageHitl(hitlRequest)
                }
            }

            "BARRIER_TRIGGERED" -> {
                taskDagStore.eventIssues.value[event.task_id]?.let { _lastError.value = it }
            }

            "TRANSPORT_ERROR", "TASK_ISOLATION_ERROR", "ERROR" -> {
                val message = event.payload.string("message") ?: "The backend could not complete this action."
                _lastError.value = message
                if (event.type == "TRANSPORT_ERROR" || event.type == "TASK_ISOLATION_ERROR") {
                    _connection.value = ConnectionState(ConnectionStatus.ERROR, message)
                }
            }
        }
    }

    private suspend fun bindTask(record: TaskSessionRecord, taskId: String) {
        if (taskId.isBlank()) return
        registryMutex.withLock {
            record.taskId = taskId
            taskSessions[taskId] = record
            record.creationRequestId?.let { pendingCreateSessions.remove(it) }
        }
    }

    private fun handleCommandOutcome(record: TaskSessionRecord, outcome: TransportCommandOutcome) {
        val status = when (outcome.status) {
            TransportCommandOutcomeStatus.ACCEPTED -> ClientCommandStatus.ACCEPTED
            TransportCommandOutcomeStatus.REJECTED -> ClientCommandStatus.REJECTED
            TransportCommandOutcomeStatus.DELIVERY_UNKNOWN -> ClientCommandStatus.DELIVERY_UNKNOWN
        }
        setCommandOutcome(outcome.requestId, status, outcome.message, record.taskId, outcome.commandType)

        if (outcome.commandType == "CREATE_TASK") {
            val promptStatus = when (status) {
                ClientCommandStatus.ACCEPTED -> PromptSubmissionStatus.ACCEPTED
                ClientCommandStatus.REJECTED -> PromptSubmissionStatus.REJECTED
                ClientCommandStatus.DELIVERY_UNKNOWN -> PromptSubmissionStatus.DELIVERY_UNKNOWN
                ClientCommandStatus.QUEUED -> PromptSubmissionStatus.QUEUED
            }
            updatePromptForRequest(outcome.requestId, promptStatus, taskId = record.taskId, message = outcome.message)
        }

        val effect = _commandEffects.value[outcome.requestId]
        if (status == ClientCommandStatus.ACCEPTED && effect is CommandEffect.CompleteHitl) {
            completeStagedHitl(effect.tabId)
        }
        if (status != ClientCommandStatus.QUEUED) _commandEffects.update { it - outcome.requestId }
        if (status == ClientCommandStatus.REJECTED || status == ClientCommandStatus.DELIVERY_UNKNOWN) {
            _lastError.value = outcome.message ?: "The backend did not confirm this command."
        }
    }

    private fun publishConnection(sessionKey: String, state: TaskConnectionState) {
        val mappedStatus = when (state.status) {
            TaskConnectionStatus.CONNECTING -> ConnectionStatus.CONNECTING
            TaskConnectionStatus.CONNECTED -> ConnectionStatus.CONNECTED
            TaskConnectionStatus.DISCONNECTED -> ConnectionStatus.DISCONNECTED
            TaskConnectionStatus.ERROR -> ConnectionStatus.ERROR
            TaskConnectionStatus.CLOSED -> ConnectionStatus.CLOSED
        }
        _taskConnections.update {
            it + (sessionKey to ConnectionState(mappedStatus, state.message))
        }
        val values = _taskConnections.value.values
        val best = values.firstOrNull { it.status == ConnectionStatus.CONNECTED }
            ?: values.firstOrNull { it.status == ConnectionStatus.CONNECTING }
            ?: values.firstOrNull { it.status == ConnectionStatus.ERROR }
            ?: values.firstOrNull { it.status == ConnectionStatus.DISCONNECTED }
            ?: values.firstOrNull()
            ?: ConnectionState()
        _connection.value = best
    }

    private fun stageHitl(request: HitlTabRequest) {
        _stagedHitlRequests.update { it + (request.tabId to request) }
        backgroundWebViewPool.pause(request)
        if (_activeHitlRequest.value == null || _activeHitlRequest.value?.tabId == request.tabId) {
            _activeHitlRequest.value = request
            taskDagStore.select(request.taskId)
        }
    }

    private fun completeStagedHitl(tabId: String) {
        val wasActive = _activeHitlRequest.value?.tabId == tabId
        _stagedHitlRequests.update { it - tabId }
        backgroundWebViewPool.resume(tabId)
        if (wasActive) {
            val next = _stagedHitlRequests.value.values.firstOrNull()
            _activeHitlRequest.value = next
            next?.let { openTask(it.taskId) }
        }
    }

    private fun updateCommand(outcome: ClientCommandOutcome) {
        _commandOutcomes.update { it + (outcome.requestId to outcome) }
    }

    private fun setCommandOutcome(
        requestId: String,
        status: ClientCommandStatus,
        message: String? = null,
        taskId: String? = null,
        commandType: String? = null,
    ) {
        val previous = _commandOutcomes.value[requestId]
        val resolvedType = commandType ?: previous?.commandType ?: "UNKNOWN"
        updateCommand(ClientCommandOutcome(requestId, resolvedType, status, message, taskId ?: previous?.taskId))
    }

    private fun updatePrompt(state: PromptSubmissionState) {
        state.requestId?.let { requestId ->
            _promptStatuses.update { it + (requestId to state) }
        }
        _promptStatus.value = state
    }

    private fun updatePromptForRequest(
        requestId: String,
        status: PromptSubmissionStatus,
        taskId: String? = null,
        message: String? = null,
    ) {
        val current = _promptStatuses.value[requestId] ?: PromptSubmissionState(requestId)
        val next = when {
            current.status == PromptSubmissionStatus.TASK_CREATED -> current
            else -> current.copy(status = status, taskId = taskId ?: current.taskId, message = message)
        }
        _promptStatuses.update { it + (requestId to next) }
        if (_promptStatus.value.requestId == requestId) _promptStatus.value = next
    }

    private fun newRequestId(): String = "client-${Random.nextLong().toString(16)}"

    private data class TaskSessionRecord(
        val key: String,
        var taskId: String?,
        val creationRequestId: String?,
        val session: TaskSocketSession,
        val observerJobs: MutableList<Job> = mutableListOf(),
    )

    private sealed interface CommandEffect {
        data class CompleteHitl(val tabId: String) : CommandEffect
    }
}

private fun ServerEvent.hitlRequest(): HitlTabRequest? {
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
