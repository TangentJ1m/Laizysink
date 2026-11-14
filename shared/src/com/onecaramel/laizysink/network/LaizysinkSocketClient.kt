package com.onecaramel.laizysink.network

import com.onecaramel.laizysink.state.Event
import com.onecaramel.laizysink.state.EventType
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.random.Random

/** Canonical server envelope. The type stays a string so newer server events do not break decoding. */
data class ServerEvent(
    val type: String,
    val task_id: String,
    val payload: JsonObject = JsonObject(emptyMap()),
)

enum class TaskConnectionStatus { CONNECTING, CONNECTED, DISCONNECTED, ERROR, CLOSED }

data class TaskConnectionState(
    val status: TaskConnectionStatus = TaskConnectionStatus.DISCONNECTED,
    val taskId: String? = null,
    val reconnectAttempt: Int = 0,
    val message: String = "Not connected",
)

enum class CommandOutcomeStatus { ACCEPTED, REJECTED, DELIVERY_UNKNOWN }

/** Local delivery state and the server's COMMAND_RESULT acknowledgement. */
data class CommandOutcome(
    val requestId: String,
    val commandType: String,
    val status: CommandOutcomeStatus,
    val message: String? = null,
)

data class CommandSubmission(
    val requestId: String,
    val queued: Boolean,
    val message: String? = null,
)

/**
 * Shared WebSocket transport. New callers should use [openTaskSession] and consume its canonical
 * string-typed events. [events] remains as a migration bridge for the existing legacy state store.
 */
class LaizysinkSocketClient(
    private val httpClient: HttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    /**
     * Opens a task-specific session. With no [taskId], the URL's last path segment is replaced by
     * `new`; after creation's first TASK_STATE_SYNC, the session reconnects to the returned task ID
     * and receives the authoritative attach snapshot. With a task ID, it attaches directly.
     */
    fun openTaskSession(webSocketUrl: String, taskId: String? = null): TaskSocketSession {
        val route = taskId ?: "new"
        val url = taskUrl(webSocketUrl, route)
        return TaskSocketSession(httpClient, json, webSocketUrl, url, taskId).also { it.start() }
    }

    /** Convenience event flow for callers which use the canonical protocol directly. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun serverEvents(
        webSocketUrl: String,
        outgoing: Flow<String>,
        taskId: String? = null,
    ): Flow<ServerEvent> = callbackFlow {
        val session = openTaskSession(webSocketUrl, taskId)
        val sender = launch {
            outgoing.collect { command -> session.send(command) }
        }
        val events = launch {
            session.events.collect { event -> send(event) }
        }
        val outcomes = launch {
            session.commandOutcomes.collect { outcome ->
                if (outcome.status == CommandOutcomeStatus.REJECTED ||
                    outcome.status == CommandOutcomeStatus.DELIVERY_UNKNOWN
                ) {
                    val type = if (outcome.status == CommandOutcomeStatus.REJECTED) {
                        "COMMAND_RESULT"
                    } else {
                        "COMMAND_OUTCOME_UNKNOWN"
                    }
                    val payload = buildJsonObject(
                        "command_type" to JsonPrimitive(outcome.commandType),
                        "accepted" to JsonPrimitive(false),
                        "request_id" to JsonPrimitive(outcome.requestId),
                        "message" to JsonPrimitive(outcome.message.orEmpty()),
                    )
                    send(ServerEvent(type, session.connectionState.value.taskId.orEmpty(), payload))
                }
            }
        }
        kotlinx.coroutines.channels.awaitClose {
            sender.cancel()
            events.cancel()
            outcomes.cancel()
            session.close()
        }
    }

    /**
     * Compatibility bridge for the current TaskDagStore. Canonical TASK_STATE_SYNC events are
     * projected into its older create/node/barrier/completion events while its owner migrates.
     */
    fun events(url: String, outgoing: Flow<String>): Flow<Event> = serverEvents(url, outgoing).transform { event ->
        event.toLegacyEvents().forEach { emit(it) }
    }
}

/** A reconnecting, single-task connection with observable command delivery outcomes. */
class TaskSocketSession internal constructor(
    private val httpClient: HttpClient,
    private val json: Json,
    private val baseUrl: String,
    initialUrl: String,
    initialTaskId: String?,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val outbound = Channel<QueuedCommand>(OUTBOUND_CAPACITY)
    private val eventChannel = Channel<ServerEvent>(Channel.BUFFERED)
    private val outcomeChannel = Channel<CommandOutcome>(Channel.UNLIMITED)
    private val pendingMutex = Mutex()
    private val pendingCommands = linkedMapOf<String, String>()
    private val mutableConnectionState = kotlinx.coroutines.flow.MutableStateFlow(
        TaskConnectionState(taskId = initialTaskId),
    )
    private var sessionJob: Job? = null
    private var targetUrl: String = initialUrl
    private var taskId: String? = initialTaskId

    val events: Flow<ServerEvent> = eventChannel.receiveAsFlow()
    val commandOutcomes: Flow<CommandOutcome> = outcomeChannel.receiveAsFlow()
    val connectionState: kotlinx.coroutines.flow.StateFlow<TaskConnectionState> = mutableConnectionState

    internal fun start() {
        if (sessionJob?.isActive == true) return
        sessionJob = scope.launch { reconnectingLoop() }
    }

    /**
     * Queues a flat command JSON object. Commands can be queued while offline; they are sent once a
     * connection is available. A command whose send may have reached the server but lacks an ack
     * is reported as DELIVERY_UNKNOWN and is not replayed automatically.
     */
    fun send(commandJson: String): CommandSubmission {
        val localId = newRequestId()
        if (connectionState.value.status == TaskConnectionStatus.CLOSED) {
            val message = "The task session is closed."
            publishOutcome(CommandOutcome(localId, "UNKNOWN", CommandOutcomeStatus.REJECTED, message))
            return CommandSubmission(localId, false, message)
        }
        val parsed = runCatching { json.parseToJsonElement(commandJson).jsonObject }.getOrNull()
        if (parsed == null) {
            val message = "Command must be a JSON object."
            publishOutcome(CommandOutcome(localId, "UNKNOWN", CommandOutcomeStatus.REJECTED, message))
            return CommandSubmission(localId, false, message)
        }

        val commandType = parsed.string("type")
        if (commandType.isNullOrBlank()) {
            val message = "Command is missing its type."
            publishOutcome(CommandOutcome(localId, "UNKNOWN", CommandOutcomeStatus.REJECTED, message))
            return CommandSubmission(localId, false, message)
        }
        val requestId = parsed.string("request_id")?.takeIf { it.isNotBlank() } ?: localId
        val validationError = validateCredentialPayload(commandType, parsed)
        if (validationError != null) {
            publishOutcome(CommandOutcome(requestId, commandType, CommandOutcomeStatus.REJECTED, validationError))
            return CommandSubmission(requestId, false, validationError)
        }

        val enriched = JsonObject(parsed + ("request_id" to JsonPrimitive(requestId)))
        val queued = QueuedCommand(
            json.encodeToString(JsonObject.serializer(), enriched),
            requestId,
            commandType,
            enriched,
        )
        val result = outbound.trySend(queued)
        if (result.isFailure) {
            val message = "The command queue is full or the task session is closed."
            publishOutcome(CommandOutcome(requestId, commandType, CommandOutcomeStatus.REJECTED, message))
            return CommandSubmission(requestId, false, message)
        }
        return CommandSubmission(requestId, true)
    }

    fun send(command: JsonObject): CommandSubmission =
        send(json.encodeToString(JsonObject.serializer(), command))

    fun close() {
        if (mutableConnectionState.value.status == TaskConnectionStatus.CLOSED) return
        mutableConnectionState.value = mutableConnectionState.value.copy(
            status = TaskConnectionStatus.CLOSED,
            message = "Task connection closed",
        )
        sessionJob?.cancel()
        scope.launch {
            markPendingUnknown("The task session closed before the server acknowledged the command.")
            while (true) {
                val queued = outbound.tryReceive().getOrNull() ?: break
                publishOutcome(
                    CommandOutcome(
                        queued.requestId,
                        queued.type,
                        CommandOutcomeStatus.REJECTED,
                        "The task session closed before the command was sent.",
                    ),
                )
            }
            outbound.close()
            eventChannel.close()
            outcomeChannel.close()
            scope.cancel()
        }
    }

    private suspend fun reconnectingLoop() {
        var reconnectAttempt = 0
        while (scope.isActive) {
            val connectedToTaskId = taskId
            var reattachImmediately = false
            var createRejected = false
            mutableConnectionState.value = TaskConnectionState(
                status = TaskConnectionStatus.CONNECTING,
                taskId = connectedToTaskId,
                reconnectAttempt = reconnectAttempt,
                message = if (connectedToTaskId == null) "Creating a task connection" else "Attaching to task $connectedToTaskId",
            )

            try {
                httpClient.webSocket(urlString = targetUrl) {
                    val socketSession = this
                    mutableConnectionState.value = TaskConnectionState(
                        status = TaskConnectionStatus.CONNECTED,
                        taskId = connectedToTaskId,
                        reconnectAttempt = reconnectAttempt,
                        message = "Connected",
                    )

                    val sender = launch {
                        if (connectedToTaskId == null) {
                            // A /new socket is used only for CREATE_TASK. Keep later commands in the
                            // queue until the returned task ID has been attached on a fresh socket.
                            while (true) {
                                val command = outbound.receive()
                                if (command.type != "CREATE_TASK") {
                                    reject(command, "Wait for task creation to finish before sending this command.")
                                    continue
                                }
                                transmit(socketSession, null, command)
                                awaitCancellation()
                            }
                        } else {
                            for (command in outbound) transmit(socketSession, connectedToTaskId, command)
                        }
                    }

                    try {
                        for (frame in incoming) {
                            if (frame !is Frame.Text) continue
                            val event = parseServerEvent(frame.readText())
                            if (event == null) {
                                emitLocalEvent(
                                    "TRANSPORT_ERROR",
                                    connectedToTaskId,
                                    "Ignored a malformed server event.",
                                )
                                continue
                            }

                            if (connectedToTaskId == null) {
                                if (event.type == "TASK_STATE_SYNC") {
                                    if (event.task_id.isBlank()) {
                                        emitLocalEvent("TRANSPORT_ERROR", null, "Task snapshot did not include a task ID.")
                                        continue
                                    }
                                    taskId = event.task_id
                                    targetUrl = taskUrl(baseUrl, event.task_id)
                                    mutableConnectionState.value = mutableConnectionState.value.copy(
                                        taskId = event.task_id,
                                        message = "Task created; restoring its authoritative snapshot",
                                    )
                                    eventChannel.send(event)
                                    reattachImmediately = true
                                    break
                                }
                                if (event.type == "COMMAND_RESULT" && event.accepted() == false) {
                                    handleCommandResult(event)
                                    eventChannel.send(event)
                                    createRejected = event.commandType() == "CREATE_TASK"
                                    if (createRejected) break
                                    continue
                                }
                                if (event.type != "COMMAND_RESULT") {
                                    emitLocalEvent(
                                        "TRANSPORT_ERROR",
                                        event.task_id,
                                        "Received ${event.type} before the server assigned a task ID.",
                                    )
                                    continue
                                }
                            } else if (event.task_id != connectedToTaskId) {
                                emitLocalEvent(
                                    "TASK_ISOLATION_ERROR",
                                    connectedToTaskId,
                                    "Ignored ${event.type} for a different task ID.",
                                )
                                continue
                            }

                            if (event.type == "COMMAND_RESULT") handleCommandResult(event)
                            eventChannel.send(event)
                        }
                    } finally {
                        sender.cancelAndJoin()
                        markPendingUnknown("The connection closed before the server acknowledged the command.")
                    }
                }

                if (reattachImmediately) {
                    reconnectAttempt = 0
                    continue
                }
                mutableConnectionState.value = TaskConnectionState(
                    status = if (createRejected) TaskConnectionStatus.ERROR else TaskConnectionStatus.DISCONNECTED,
                    taskId = taskId,
                    reconnectAttempt = reconnectAttempt,
                    message = if (createRejected) "The server rejected task creation." else "Connection closed; reconnecting",
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                markPendingUnknown("The connection ended before the server acknowledged the command.")
                val message = error.message?.takeIf { it.isNotBlank() } ?: "Check the backend connection."
                mutableConnectionState.value = TaskConnectionState(
                    status = TaskConnectionStatus.ERROR,
                    taskId = taskId,
                    reconnectAttempt = reconnectAttempt,
                    message = "Backend unavailable: $message",
                )
                emitLocalEvent("TRANSPORT_ERROR", taskId, "Backend unavailable: $message")
            }

            if (!scope.isActive) break
            reconnectAttempt = (reconnectAttempt + 1).coerceAtMost(MAX_RECONNECT_ATTEMPT)
            val backoffMs = minOf(
                MAX_RECONNECT_DELAY_MS,
                INITIAL_RECONNECT_DELAY_MS * (1 shl (reconnectAttempt - 1)).toLong(),
            )
            delay(backoffMs)
        }
    }

    private suspend fun transmit(
        session: DefaultClientWebSocketSession,
        connectedTaskId: String?,
        command: QueuedCommand,
    ) {
        if (connectedTaskId == null && command.type != "CREATE_TASK") {
            reject(command, "A task ID is required before sending ${command.type}.")
            return
        }
        if (connectedTaskId != null && command.type == "CREATE_TASK") {
            reject(command, "CREATE_TASK must use a new task connection.")
            return
        }
        val commandTaskId = command.json.string("task_id")
        if (connectedTaskId != null && commandTaskId != null && commandTaskId != connectedTaskId) {
            reject(command, "Command task_id does not match this WebSocket task.")
            return
        }
        if (connectedTaskId != null && command.type in setOf("RESOLVE_BARRIER", "KEYCHAIN_AUTH_COMPLETE") && commandTaskId != connectedTaskId) {
            reject(command, "${command.type} must include this WebSocket's task_id.")
            return
        }

        pendingMutex.withLock { pendingCommands[command.requestId] = command.type }
        try {
            session.send(Frame.Text(command.wireJson))
        } catch (error: Exception) {
            pendingMutex.withLock { pendingCommands.remove(command.requestId) }
            publishOutcome(
                CommandOutcome(
                    command.requestId,
                    command.type,
                    CommandOutcomeStatus.DELIVERY_UNKNOWN,
                    "The socket closed while sending; retry only after checking task state.",
                ),
            )
            throw error
        }
    }

    private suspend fun handleCommandResult(event: ServerEvent) {
        val requestId = event.payload.string("request_id") ?: return
        val commandType = event.commandType() ?: pendingMutex.withLock { pendingCommands[requestId] } ?: "UNKNOWN"
        pendingMutex.withLock { pendingCommands.remove(requestId) }
        val accepted = event.accepted()
        if (accepted == null) return
        publishOutcome(
            CommandOutcome(
                requestId,
                commandType,
                if (accepted) CommandOutcomeStatus.ACCEPTED else CommandOutcomeStatus.REJECTED,
                event.payload.string("message"),
            ),
        )
    }

    private suspend fun reject(command: QueuedCommand, message: String) {
        publishOutcome(CommandOutcome(command.requestId, command.type, CommandOutcomeStatus.REJECTED, message))
    }

    private suspend fun markPendingUnknown(message: String) {
        val pending = pendingMutex.withLock {
            val copy = pendingCommands.toMap()
            pendingCommands.clear()
            copy
        }
        pending.forEach { (requestId, type) ->
            publishOutcome(CommandOutcome(requestId, type, CommandOutcomeStatus.DELIVERY_UNKNOWN, message))
        }
    }

    private suspend fun emitLocalEvent(type: String, eventTaskId: String?, message: String) {
        eventChannel.send(
            ServerEvent(
                type,
                eventTaskId ?: taskId.orEmpty(),
                buildJsonObject("message" to JsonPrimitive(message)),
            ),
        )
    }

    private fun publishOutcome(outcome: CommandOutcome) {
        outcomeChannel.trySend(outcome)
    }

    private data class QueuedCommand(
        val wireJson: String,
        val requestId: String,
        val type: String,
        val json: JsonObject,
    )

    companion object {
        private const val OUTBOUND_CAPACITY = 64
        private const val INITIAL_RECONNECT_DELAY_MS = 500L
        private const val MAX_RECONNECT_DELAY_MS = 30_000L
        private const val MAX_RECONNECT_ATTEMPT = 7

        private fun newRequestId(): String = "req_${Random.nextLong().toULong().toString(16)}"
    }
}

private fun parseServerEvent(raw: String): ServerEvent? = runCatching {
    val envelope = Json.parseToJsonElement(raw).jsonObject
    val type = envelope.string("type") ?: return null
    val taskId = envelope.string("task_id") ?: return null
    val payload = envelope["payload"] as? JsonObject ?: JsonObject(emptyMap())
    ServerEvent(type, taskId, payload)
}.getOrNull()

private fun ServerEvent.toLegacyEvents(): List<Event> = when (type) {
    "TASK_STATE_SYNC" -> snapshotToLegacyEvents()
    "BARRIER_TRIGGERED" -> listOf(
        Event(EventType.DATA_BARRIER, task_id, buildJsonObject("barrier" to payload)),
    )
    "HITL_TAB_REQUIRED" -> listOf(Event(EventType.HITL_BROWSER, task_id, payload))
    "COMMAND_RESULT" -> if (payload.boolean("accepted") == false) {
        listOf(Event(EventType.ERROR, task_id, buildJsonObject("message" to JsonPrimitive(payload.string("message") ?: "The backend rejected the command."))))
    } else {
        emptyList()
    }
    "COMMAND_OUTCOME_UNKNOWN", "TRANSPORT_ERROR", "TASK_ISOLATION_ERROR" -> listOf(
        Event(EventType.ERROR, task_id, buildJsonObject("message" to JsonPrimitive(payload.string("message") ?: "The transport could not complete the request."))),
    )
    "TASK_CREATED", "NODE_UPDATED", "DATA_BARRIER", "HITL_BROWSER", "TASK_COMPLETED", "ERROR" ->
        runCatching { listOf(Event(EventType.valueOf(type), task_id, payload)) }.getOrElse {
            listOf(Event(EventType.ERROR, task_id, buildJsonObject("message" to JsonPrimitive("Could not decode ${type} event."))))
        }
    else -> listOf(Event(EventType.ERROR, task_id, buildJsonObject("message" to JsonPrimitive("Unsupported backend event: $type"))))
}

private fun ServerEvent.snapshotToLegacyEvents(): List<Event> {
    val snapshot = (payload["task_graph"] as? JsonObject) ?: payload
    val prompt = snapshot.string("prompt") ?: ""
    val result = mutableListOf(
        Event(EventType.TASK_CREATED, task_id, buildJsonObject("prompt" to JsonPrimitive(prompt))),
    )
    val nodes = snapshot["nodes"] as? JsonArray ?: JsonArray(emptyList())
    nodes.forEach { node ->
        if (node is JsonObject) result += Event(EventType.NODE_UPDATED, task_id, buildJsonObject("node" to node))
    }
    val barriers = snapshot["barriers"] as? JsonArray ?: JsonArray(emptyList())
    barriers.forEach { barrier ->
        if (barrier is JsonObject) result += Event(EventType.DATA_BARRIER, task_id, buildJsonObject("barrier" to barrier))
    }
    if (snapshot.string("status") == "COMPLETED") {
        result += Event(EventType.TASK_COMPLETED, task_id, buildJsonObject("task_graph" to snapshot))
    } else if (snapshot.string("status") == "PARTIAL") {
        result += Event(
            EventType.ERROR,
            task_id,
            buildJsonObject("message" to JsonPrimitive("This client state model does not yet support PARTIAL task snapshots.")),
        )
    }
    return result
}

private fun validateCredentialPayload(type: String, command: JsonObject): String? {
    if (command.containsCredentialField()) {
        return if (type == "KEYCHAIN_AUTH_COMPLETE") {
            "KEYCHAIN_AUTH_COMPLETE may send only an opaque keychain_ref, never credential fields."
        } else {
            "Commands cannot contain raw credential fields."
        }
    }
    if (type != "KEYCHAIN_AUTH_COMPLETE") return null
    val keychainRef = command.string("keychain_ref")
    if (keychainRef == null || !keychainRef.startsWith("vault://")) {
        return "KEYCHAIN_AUTH_COMPLETE requires an opaque vault:// keychain_ref."
    }
    return null
}

private fun JsonObject.containsCredentialField(): Boolean = (this as JsonElement).containsCredentialField()

private fun JsonElement.containsCredentialField(): Boolean = when (this) {
    is JsonObject -> {
        val forbidden = setOf(
            "password", "raw_password", "credential", "credentials", "secret", "username",
            "passphrase", "access_token", "refresh_token", "authorization",
        )
        keys.any { it.lowercase() in forbidden } || values.any { it.containsCredentialField() }
    }
    is JsonArray -> any { it.containsCredentialField() }
    else -> false
}

private fun taskUrl(baseUrl: String, taskId: String): String {
    require(taskId.matches(Regex("[A-Za-z0-9._~-]+"))) { "Task ID contains characters unsafe for a URL path segment." }
    val suffixStart = listOf(baseUrl.indexOf('?'), baseUrl.indexOf('#'))
        .filter { it >= 0 }
        .minOrNull() ?: baseUrl.length
    val route = baseUrl.substring(0, suffixStart)
    val suffix = baseUrl.substring(suffixStart)
    require(route.contains("/ws/tasks/")) { "Backend WebSocket URL must end in /ws/tasks/{task_id}." }
    val prefix = route.substring(0, route.lastIndexOf('/') + 1)
    return "$prefix$taskId$suffix"
}

private fun buildJsonObject(vararg pairs: Pair<String, JsonElement>): JsonObject = JsonObject(mapOf(*pairs))

private fun JsonObject.string(key: String): String? = this[key]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
private fun JsonObject.boolean(key: String): Boolean? = this[key]?.let { runCatching { it.jsonPrimitive.booleanOrNull }.getOrNull() }
private fun ServerEvent.accepted(): Boolean? = payload.boolean("accepted")
private fun ServerEvent.commandType(): String? = payload.string("command_type")

