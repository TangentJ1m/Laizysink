package com.onecaramel.laizysink

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.onecaramel.laizysink.state.AppStateStore
import com.onecaramel.laizysink.state.ConnectionStatus
import com.onecaramel.laizysink.state.DataBarrier
import com.onecaramel.laizysink.state.ExaSource
import com.onecaramel.laizysink.state.NodeStatus
import com.onecaramel.laizysink.state.TaskGraph
import com.onecaramel.laizysink.state.WorkNode
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private val Obsidian = Color(0xFF0D0F14)
private val Charcoal = Color(0xFF161922)
private val Border = Color(0xFF252A37)
private val Indigo = Color(0xFF6366F1)
private val Emerald = Color(0xFF10B981)
private val Amber = Color(0xFFF59E0B)
private val PrimaryText = Color(0xFFF3F4F6)
private val Muted = Color(0xFF9CA3AF)
private val ErrorRed = Color(0xFFF87171)

private enum class AppScreen(val title: String) {
    HOME("Laizysink"), TASKS("All Tasks"), ACTIVE("Active Task"), MAP("Execution Map"),
    KEYCHAIN("Keychain & Spotlight"), DONE("Completed Task"),
}

@Composable
fun App(
    platformActions: AppPlatformActions = AppPlatformActions(),
    backendWebSocketUrl: String = getPlatform().backendWebSocketUrl,
) {
    val store = remember(backendWebSocketUrl) {
        AppStateStore(createPlatformHttpClient(), backendWebSocketUrl)
    }
    DisposableEffect(store) {
        onDispose { store.close() }
    }

    val tasks by store.taskDagStore.tasks.collectAsState()
    val selectedTaskId by store.taskDagStore.selectedTaskId.collectAsState()
    val connection by store.connection.collectAsState()
    val hiddenTabs by store.backgroundWebViewPool.tabs.collectAsState()
    val memoryItems by store.temporalMemoryStore.items.collectAsState()
    val hitlRequest by store.activeHitlRequest.collectAsState()
    val lastError by store.lastError.collectAsState()

    var screen by remember { mutableStateOf(AppScreen.HOME) }
    var prompt by remember { mutableStateOf("") }
    val selectedTask = selectedTaskId?.let(tasks::get)

    LaunchedEffect(hitlRequest?.tabId) {
        val request = hitlRequest ?: return@LaunchedEffect
        screen = AppScreen.KEYCHAIN
        platformActions.onHitlRequested(request) { keychainRef ->
            if (keychainRef == null) {
                store.dismissHitl(request.tabId)
                screen = AppScreen.ACTIVE
            }
            else {
                store.completeKeychainAuth(request.tabId, keychainRef)
                screen = AppScreen.ACTIVE
            }
        }
    }

    fun submitPrompt() {
        val query = prompt.trim()
        if (query.isEmpty()) return
        store.createTask(query)
        prompt = ""
        screen = AppScreen.ACTIVE
    }

    fun openTask(task: TaskGraph) {
        store.taskDagStore.select(task.id)
        screen = if (task.status == NodeStatus.COMPLETED) AppScreen.DONE else AppScreen.ACTIVE
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Obsidian,
            surface = Charcoal,
            primary = Indigo,
            onBackground = PrimaryText,
            onSurface = PrimaryText,
        ),
    ) {
        Column(Modifier.fillMaxSize().background(Obsidian).padding(horizontal = 18.dp, vertical = 14.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(screen.title, fontSize = 21.sp, fontWeight = FontWeight.Bold, color = PrimaryText)
                Text(
                    if (hiddenTabs.isEmpty()) "Tabs Hidden" else "Tabs Hidden • ${hiddenTabs.size} Active",
                    color = if (hiddenTabs.isEmpty()) Muted else Amber,
                    fontSize = 11.sp,
                    modifier = Modifier.clickable { screen = AppScreen.TASKS }.padding(6.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth().weight(1f)) {
                when (screen) {
                    AppScreen.HOME -> Home(
                        prompt = prompt,
                        tasks = tasks.values.toList(),
                        connectionStatus = connection.status,
                        connectionMessage = connection.message,
                        onPromptChange = { prompt = it },
                        onSubmit = ::submitPrompt,
                        onQuickPrompt = { prompt = it },
                        onTasks = { screen = AppScreen.TASKS },
                        onTaskClick = ::openTask,
                    )
                    AppScreen.TASKS -> Tasks(
                        tasks = tasks.values.toList(),
                        memoryItems = memoryItems,
                        onNew = { screen = AppScreen.HOME },
                        onTaskClick = ::openTask,
                    )
                    AppScreen.ACTIVE -> Active(
                        task = selectedTask,
                        connectionMessage = connection.message,
                        errorMessage = lastError,
                        onMap = { screen = AppScreen.MAP },
                        onResolve = store::resolveBarrier,
                        onOpenUrl = platformActions.openExternalUrl,
                    )
                    AppScreen.MAP -> ExecutionMap(
                        task = selectedTask,
                        onBack = { screen = AppScreen.ACTIVE },
                        onOpenUrl = platformActions.openExternalUrl,
                    )
                    AppScreen.KEYCHAIN -> Keychain(
                        request = hitlRequest,
                        authenticate = platformActions.authenticateHitl,
                        onAuthenticated = { request, keyRef ->
                            store.completeKeychainAuth(request.tabId, keyRef)
                            screen = AppScreen.ACTIVE
                        },
                        onManualComplete = { request -> store.dismissHitl(request.tabId); screen = AppScreen.ACTIVE },
                    )
                    AppScreen.DONE -> Completed(
                        task = selectedTask,
                        onMap = { screen = AppScreen.MAP },
                        onOpenUrl = platformActions.openExternalUrl,
                        onNew = { screen = AppScreen.HOME },
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                AppScreen.entries.forEach { destination ->
                    Text(
                        destination.title.substringBefore(' '),
                        color = if (destination == screen) Indigo else Muted,
                        fontSize = 10.sp,
                        modifier = Modifier.clickable { screen = destination }.padding(horizontal = 4.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun Home(
    prompt: String,
    tasks: List<TaskGraph>,
    connectionStatus: ConnectionStatus,
    connectionMessage: String,
    onPromptChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onQuickPrompt: (String) -> Unit,
    onTasks: () -> Unit,
    onTaskClick: (TaskGraph) -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(13.dp)) {
        Text("The Internet Butler", fontSize = 27.sp, fontWeight = FontWeight.SemiBold, color = PrimaryText)
        Text("Tell me what you need. I’ll research live sources and keep the tabs out of your way.", color = Muted)
        StatusLine(connectionStatus, connectionMessage)
        SectionTitle("Start with a prompt")
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            listOf("Research cheap flights", "Compare options", "Summarize open articles").forEach { starter ->
                Chip(starter, Modifier.clickable { onQuickPrompt(starter) })
            }
        }
        TextField(
            value = prompt,
            onValueChange = onPromptChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Ask Laizysink anything…", color = Muted) },
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Charcoal,
                unfocusedContainerColor = Charcoal,
                focusedTextColor = PrimaryText,
                unfocusedTextColor = PrimaryText,
                cursorColor = Indigo,
            ),
        )
        Button(
            onClick = onSubmit,
            enabled = prompt.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Indigo),
        ) { Text("Research this") }

        SectionTitle("Your tasks")
        val previews = prioritizedTasks(tasks).take(2)
        if (previews.isEmpty()) {
            EmptyState("Tasks you create will appear here with their live research status.")
        } else {
            previews.forEach { task -> TaskCard(task, onClick = { onTaskClick(task) }) }
        }
        Text("Review all tasks  →", color = Indigo, modifier = Modifier.clickable(onClick = onTasks).padding(vertical = 4.dp))
    }
}

@Composable
private fun Tasks(
    tasks: List<TaskGraph>,
    memoryItems: List<com.onecaramel.laizysink.state.TemporalMemoryItem>,
    onNew: () -> Unit,
    onTaskClick: (TaskGraph) -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = onNew, colors = ButtonDefaults.buttonColors(containerColor = Indigo)) { Text("+ New task") }
        val attention = tasks.filter { it.status == NodeStatus.BLOCKED_USER || it.status == NodeStatus.FAILED }
        val working = tasks.filter { it.status == NodeStatus.RUNNING || it.status == NodeStatus.WAITING || it.status == NodeStatus.STALE }
        val finished = tasks.filter { it.status == NodeStatus.COMPLETED }
        TaskGroup("Needs attention", attention, onTaskClick)
        TaskGroup("Working in background", working, onTaskClick)
        TaskGroup("Finished", finished, onTaskClick)
        SectionTitle("Active context")
        if (memoryItems.isEmpty()) {
            EmptyState("No shared context has been recorded yet.")
        } else {
            memoryItems.forEach { item ->
                Surface(color = Charcoal, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(item.key, color = PrimaryText, fontWeight = FontWeight.SemiBold)
                            Text(item.freshness_tag.name, color = Indigo, fontSize = 10.sp)
                        }
                        Text(item.value, color = Muted, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun TaskGroup(title: String, tasks: List<TaskGraph>, onTaskClick: (TaskGraph) -> Unit) {
    SectionTitle(title)
    if (tasks.isEmpty()) EmptyState("Nothing here yet.")
    else tasks.asReversed().forEach { task -> TaskCard(task, onClick = { onTaskClick(task) }) }
}

@Composable
private fun Active(
    task: TaskGraph?,
    connectionMessage: String,
    errorMessage: String?,
    onMap: () -> Unit,
    onResolve: (String, String, String) -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    if (task == null) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            EmptyState("Submit a prompt to start a live research task.")
            Text(connectionMessage, color = Muted, fontSize = 12.sp)
        }
        return
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(task.prompt.ifBlank { "Research task" }, color = PrimaryText, fontWeight = FontWeight.SemiBold)
                StatusBadge(task.status)
            }
            Button(onClick = onMap, colors = ButtonDefaults.buttonColors(containerColor = Indigo)) { Text("Map") }
        }
        if (errorMessage != null) Text(errorMessage, color = ErrorRed, fontSize = 12.sp)
        Text(connectionMessage, color = Muted, fontSize = 11.sp)

        if (task.barriers.isNotEmpty()) {
            SectionTitle("Needs your input")
            task.barriers.forEach { barrier ->
                BarrierCard(barrier) { answer -> onResolve(task.id, barrier.barrier_id, answer) }
            }
        }

        SectionTitle("Research branches")
        if (task.nodes.isEmpty()) EmptyState("The backend is preparing research branches.")
        task.nodes.forEach { node ->
            NodeCard(node, onOpenUrl)
        }

    }
}

@Composable
private fun BarrierCard(barrier: DataBarrier, onResolve: (String) -> Unit) {
    var selected by remember(barrier.barrier_id) { mutableStateOf<String?>(null) }
    var customAnswer by remember(barrier.barrier_id) { mutableStateOf("") }
    Surface(color = Charcoal, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(barrier.badge_text ?: "Quick input needed", color = Amber, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Text(barrier.question, color = PrimaryText)
            barrier.options.forEach { option ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { selected = option; customAnswer = "" }) {
                    RadioButton(selected = selected == option, onClick = { selected = option; customAnswer = "" })
                    Text(option, color = Muted, fontSize = 12.sp)
                }
            }
            if (barrier.allow_other_voice_text) {
                Text("Or add details", color = Muted, fontSize = 11.sp)
                TextField(
                    value = customAnswer,
                    onValueChange = { customAnswer = it; selected = null },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Type your answer", color = Muted) },
                    colors = TextFieldDefaults.colors(focusedContainerColor = Obsidian, unfocusedContainerColor = Obsidian, focusedTextColor = PrimaryText, unfocusedTextColor = PrimaryText),
                )
            }
            val answer = customAnswer.trim().ifBlank { selected.orEmpty() }
            Button(onClick = { onResolve(answer) }, enabled = answer.isNotBlank(), colors = ButtonDefaults.buttonColors(containerColor = Indigo)) {
                Text("Send answer")
            }
        }
    }
}

@Composable
private fun NodeCard(node: WorkNode, onOpenUrl: (String) -> Unit) {
    Surface(color = Charcoal, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(node.title, color = PrimaryText, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                StatusBadge(node.status)
            }
            Text(node.plain_summary, color = Muted, fontSize = 12.sp)
            if (node.highlight_chips.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { node.highlight_chips.forEach { Chip(it) } }
            }
            if (node.decision_rationale.isNotBlank()) Text(node.decision_rationale, color = Muted, fontSize = 11.sp)
            node.work_tasks.forEach { work ->
                Text("${work.label} • ${work.status.name}", color = Muted, fontSize = 10.sp)
            }
            if (node.sources.isNotEmpty()) {
                SectionTitle("Sources")
                node.sources.forEach { SourceCard(it, onOpenUrl) }
            } else if (node.status == NodeStatus.RUNNING) {
                Text("Waiting for live Exa results…", color = Muted, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun ExecutionMap(task: TaskGraph?, onBack: () -> Unit, onOpenUrl: (String) -> Unit) {
    if (task == null) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            EmptyState("Choose an active task to inspect its execution map.")
            Button(onClick = onBack) { Text("Back to task") }
        }
        return
    }
    var expandedNodeId by remember(task.id) {
        mutableStateOf(task.nodes.firstOrNull { it.status == NodeStatus.BLOCKED_USER || it.status == NodeStatus.RUNNING }?.id ?: task.nodes.firstOrNull()?.id)
    }
    var mapScale by remember(task.id) { mutableStateOf(1f) }
    var mapPan by remember(task.id) { mutableStateOf(Offset.Zero) }
    val selectedIndex = task.nodes.indexOfFirst { it.id == expandedNodeId }.coerceAtLeast(0)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(11.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(task.prompt.ifBlank { "Execution graph" }, color = PrimaryText, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Button(onClick = onBack, colors = ButtonDefaults.buttonColors(containerColor = Charcoal)) { Text("Simple") }
        }
        if (task.nodes.isEmpty()) {
            EmptyState("The backend has not returned graph nodes yet.")
        } else {
            BoxWithConstraints(
                Modifier.fillMaxWidth().height(280.dp).background(Charcoal, RoundedCornerShape(18.dp)),
            ) {
                val density = androidx.compose.ui.platform.LocalDensity.current
                val widthPx = with(density) { maxWidth.toPx() }
                val heightPx = with(density) { maxHeight.toPx() }
                val center = Offset(widthPx / 2f, heightPx / 2f)
                val rawPoints = task.nodes.mapIndexed { index, _ -> mapPoint(index, selectedIndex, task.nodes.size, widthPx, heightPx) }
                val points = rawPoints.map { point -> center + (point - center) * mapScale + mapPan }

                Canvas(
                    Modifier.fillMaxSize().pointerInput(task.nodes.size) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            mapScale = (mapScale * zoom).coerceIn(0.65f, 1.7f)
                            mapPan += pan
                        }
                    },
                ) {
                    task.nodes.forEachIndexed { childIndex, child ->
                        child.parent_ids.forEach { parentId ->
                            val parentIndex = task.nodes.indexOfFirst { it.id == parentId }
                            if (parentIndex >= 0) drawLine(Border, points[parentIndex], points[childIndex], strokeWidth = 2f)
                        }
                    }
                    points.forEachIndexed { index, point ->
                        val expanded = task.nodes[index].id == expandedNodeId
                        drawCircle(statusColor(task.nodes[index].status), radius = if (expanded) 17f else 11f, center = point)
                    }
                }

                task.nodes.forEachIndexed { index, node ->
                    val point = points[index]
                    Surface(
                        color = if (node.id == expandedNodeId) Border else Charcoal,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .offset { IntOffset((point.x - 60.dp.toPx(density)).roundToInt(), (point.y - 22.dp.toPx(density)).roundToInt()) }
                            .widthIn(max = 128.dp)
                            .clickable { expandedNodeId = node.id }
                            .padding(horizontal = 8.dp, vertical = 5.dp),
                    ) {
                        Column(Modifier.padding(horizontal = 5.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(node.title, color = PrimaryText, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
                            Text(node.status.name, color = statusColor(node.status), fontSize = 8.sp)
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { mapScale = (mapScale * 1.15f).coerceAtMost(1.7f) }, colors = ButtonDefaults.buttonColors(containerColor = Charcoal)) { Text("+") }
                Button(onClick = { mapScale = (mapScale / 1.15f).coerceAtLeast(0.65f) }, colors = ButtonDefaults.buttonColors(containerColor = Charcoal)) { Text("−") }
                Text("Drag to pan • Pinch to zoom", color = Muted, fontSize = 10.sp, modifier = Modifier.align(Alignment.CenterVertically))
            }
            task.nodes.forEach { node ->
                if (node.id == expandedNodeId) NodeInspector(node, onOpenUrl, onResolveBlocked = if (node.status == NodeStatus.BLOCKED_USER) onBack else null)
                else Surface(
                    color = Charcoal,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().clickable { expandedNodeId = node.id }.padding(12.dp),
                ) {
                    Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(node.title, color = PrimaryText)
                        Text(node.status.name, color = statusColor(node.status), fontSize = 10.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun NodeInspector(node: WorkNode, onOpenUrl: (String) -> Unit, onResolveBlocked: (() -> Unit)?) {
    Surface(color = Charcoal, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(node.title, color = PrimaryText, fontWeight = FontWeight.Bold)
                StatusBadge(node.status)
            }
            Text(node.plain_summary, color = Muted)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { node.highlight_chips.take(3).forEach { Chip(it) } }
            SectionTitle("Work tasks")
            if (node.work_tasks.isEmpty()) Text("No work-task details were returned.", color = Muted, fontSize = 11.sp)
            node.work_tasks.forEach { Text("${it.label} • ${it.status.name}", color = Muted, fontSize = 11.sp) }
            SectionTitle("Inherited context")
            if (node.inherited_context.isEmpty()) Text("No inherited context.", color = Muted, fontSize = 11.sp)
            node.inherited_context.forEach { (key, value) -> Text("$key: $value", color = Muted, fontSize = 11.sp) }
            SectionTitle("Decision rationale")
            Text(node.decision_rationale.ifBlank { "No decision rationale was returned." }, color = Muted, fontSize = 11.sp)
            if (onResolveBlocked != null) {
                Button(onClick = onResolveBlocked, colors = ButtonDefaults.buttonColors(containerColor = Amber)) { Text("Resolve input") }
            }
            SectionTitle("Verified sources")
            if (node.sources.isEmpty()) Text("No citations were returned for this branch.", color = Muted, fontSize = 11.sp)
            node.sources.forEach { SourceCard(it, onOpenUrl) }
        }
    }
}

@Composable
private fun Keychain(
    request: HitlTabRequest?,
    authenticate: suspend (HitlTabRequest) -> String?,
    onAuthenticated: (HitlTabRequest, String) -> Unit,
    onManualComplete: (HitlTabRequest) -> Unit,
) {
    var working by remember(request?.tabId) { mutableStateOf(false) }
    var message by remember(request?.tabId) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle("Human approval required")
        if (request == null) {
            EmptyState("No browser sign-in request is active.")
            return@Column
        }
        Text(request.domain.ifBlank { request.url }, color = PrimaryText, fontWeight = FontWeight.SemiBold)
        Text(request.url, color = Indigo, fontSize = 11.sp)
        if (request.targetSelector.isNotBlank()) Text("Target: ${request.targetSelector}", color = Muted, fontSize = 10.sp)
        if (request.tooltipText.isNotBlank()) Text(request.tooltipText, color = Muted)
        Text("Your credentials stay on this device. Continue in the revealed browser, or use a saved credential after local authentication.", color = Muted)
        if (message != null) Text(message!!, color = Amber, fontSize = 11.sp)
        Button(
            onClick = {
                scope.launch {
                    working = true
                    val keychainRef = authenticate(request)
                    working = false
                    if (keychainRef != null && keychainRef.startsWith("vault://")) onAuthenticated(request, keychainRef)
                    else message = "Authentication was not completed."
                }
            },
            enabled = !working,
            colors = ButtonDefaults.buttonColors(containerColor = Indigo),
        ) { Text(if (working) "Waiting for device authentication…" else "Autofill with saved keychain") }
        Button(
            onClick = { onManualComplete(request) },
            colors = ButtonDefaults.buttonColors(containerColor = Charcoal),
        ) { Text("I finished in the browser") }
    }
}

@Composable
private fun Completed(task: TaskGraph?, onMap: () -> Unit, onOpenUrl: (String) -> Unit, onNew: () -> Unit) {
    if (task == null) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            EmptyState("Select a completed task to review its sources and decisions.")
            Button(onClick = onNew) { Text("New task") }
        }
        return
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(11.dp)) {
        Text(task.prompt.ifBlank { "Research result" }, color = PrimaryText, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        StatusBadge(task.status)
        if (task.nodes.isEmpty()) EmptyState("No research nodes were returned.")
        task.nodes.forEach { node ->
            Text(node.title, color = PrimaryText, fontWeight = FontWeight.SemiBold)
            Text(node.plain_summary, color = Muted, fontSize = 12.sp)
            node.sources.forEach { SourceCard(it, onOpenUrl) }
        }
        Button(onClick = onMap, colors = ButtonDefaults.buttonColors(containerColor = Indigo)) { Text("Inspect execution map") }
        Button(onClick = onNew, colors = ButtonDefaults.buttonColors(containerColor = Charcoal)) { Text("Start another task") }
    }
}

@Composable
private fun SourceCard(source: ExaSource, onOpenUrl: (String) -> Unit) {
    Surface(color = Obsidian, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(11.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(source.domain, color = PrimaryText, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                Text(if (source.selected) "Selected" else "Considered", color = if (source.selected) Emerald else Muted, fontSize = 10.sp)
            }
            Text(source.title, color = Muted, fontSize = 11.sp)
            if (source.verification_badge != null) Text(source.verification_badge, color = Emerald, fontSize = 9.sp)
            if (source.highlighted_quote.isNotBlank()) Text("“${source.highlighted_quote}”", color = PrimaryText, fontSize = 11.sp)
            if (!source.selected && !source.rejection_reason.isNullOrBlank()) {
                Text("Why not selected: ${source.rejection_reason}", color = Muted, fontSize = 10.sp)
            }
            Text(
                source.url,
                color = Indigo,
                fontSize = 10.sp,
                modifier = Modifier.clickable { onOpenUrl(source.url) },
            )
        }
    }
}

@Composable
private fun TaskCard(task: TaskGraph, onClick: () -> Unit) {
    Surface(color = Charcoal, shape = RoundedCornerShape(15.dp), modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(task.prompt.ifBlank { "Research task" }, color = PrimaryText, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                StatusBadge(task.status)
            }
            val sourceCount = task.nodes.sumOf { it.sources.size }
            Text("${task.nodes.size} research branches • $sourceCount citations", color = Muted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun StatusLine(status: ConnectionStatus, message: String) {
    Text(message, color = when (status) { ConnectionStatus.CONNECTED -> Emerald; ConnectionStatus.ERROR -> ErrorRed; else -> Muted }, fontSize = 11.sp)
}

@Composable
private fun StatusBadge(status: NodeStatus) {
    Surface(color = statusColor(status).copy(alpha = 0.14f), shape = RoundedCornerShape(20.dp)) {
        Text(status.name.replace('_', ' '), color = statusColor(status), fontSize = 9.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp))
    }
}

@Composable
private fun Chip(label: String, modifier: Modifier = Modifier) {
    Surface(color = Charcoal, shape = RoundedCornerShape(22.dp), modifier = modifier) {
        Text(label, color = Muted, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 9.dp, vertical = 7.dp))
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, color = PrimaryText, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 3.dp))
}

@Composable
private fun EmptyState(message: String) {
    Surface(color = Charcoal, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        Text(message, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(14.dp))
    }
}

private fun prioritizedTasks(tasks: List<TaskGraph>): List<TaskGraph> {
    val priority = mapOf(NodeStatus.BLOCKED_USER to 0, NodeStatus.FAILED to 0, NodeStatus.RUNNING to 1, NodeStatus.WAITING to 1, NodeStatus.STALE to 1, NodeStatus.COMPLETED to 2)
    return tasks.asReversed().sortedBy { priority[it.status] ?: 3 }
}

private fun statusColor(status: NodeStatus): Color = when (status) {
    NodeStatus.COMPLETED -> Emerald
    NodeStatus.BLOCKED_USER -> Amber
    NodeStatus.FAILED -> ErrorRed
    NodeStatus.RUNNING, NodeStatus.WAITING, NodeStatus.STALE -> Indigo
}

private fun mapPoint(index: Int, expandedIndex: Int, count: Int, width: Float, height: Float): Offset {
    if (index == expandedIndex || count <= 1) return Offset(width * 0.5f, height * 0.48f)
    val siblingIndex = if (index < expandedIndex) index else index - 1
    val siblingCount = (count - 1).coerceAtLeast(1)
    val angle = (-PI / 2.0) + (2.0 * PI * siblingIndex / siblingCount)
    return Offset(
        (width * 0.5f + cos(angle).toFloat() * width * 0.34f).coerceIn(45f, width - 45f),
        (height * 0.48f + sin(angle).toFloat() * height * 0.32f).coerceIn(38f, height - 38f),
    )
}

private fun androidx.compose.ui.unit.Dp.toPx(density: androidx.compose.ui.unit.Density): Float = with(density) { this@toPx.toPx() }
