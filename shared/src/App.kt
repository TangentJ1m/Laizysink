package com.onecaramel.laizysink

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.onecaramel.laizysink.state.AppStateStore
import com.onecaramel.laizysink.state.BackgroundTabInfo
import com.onecaramel.laizysink.state.ConnectionStatus
import com.onecaramel.laizysink.state.NodeStatus
import com.onecaramel.laizysink.state.TaskGraph
import com.onecaramel.laizysink.state.TemporalMemoryItem
import kotlinx.coroutines.launch

private val Obsidian = Color(0xFF0D0F14)
private val Charcoal = Color(0xFF161922)
private val Border = Color(0xFF252A37)
private val Indigo = Color(0xFF6366F1)
private val Emerald = Color(0xFF10B981)
private val Amber = Color(0xFFF59E0B)
private val PrimaryText = Color(0xFFF3F4F6)
private val Muted = Color(0xFF9CA3AF)
private val ErrorRed = Color(0xFFF87171)

private enum class AppScreen { HOME, TASKS, ACTIVE, MAP, KEYCHAIN, DONE }
private enum class TaskHubFilter { ALL, ACTIVE_TABS }

@Composable
fun App(
    platformActions: AppPlatformActions = AppPlatformActions(),
    backendWebSocketUrl: String = getPlatform().backendWebSocketUrl,
    onVoiceInput: (((String) -> Unit) -> Unit)? = null,
    onPickAttachments: (((List<String>) -> Unit) -> Unit)? = null,
) {
    val store = remember(backendWebSocketUrl) { AppStateStore(createPlatformHttpClient(), backendWebSocketUrl) }
    DisposableEffect(store) { onDispose { store.close() } }
    val tasks by store.taskDagStore.tasks.collectAsState()
    val selectedTaskId by store.taskDagStore.selectedTaskId.collectAsState()
    val connection by store.connection.collectAsState()
    val tabs by store.backgroundWebViewPool.tabs.collectAsState()
    val memories by store.temporalMemoryStore.items.collectAsState()
    val hitl by store.activeHitlRequest.collectAsState()
    val error by store.lastError.collectAsState()

    var screen by remember { mutableStateOf(AppScreen.HOME) }
    var filter by remember { mutableStateOf(TaskHubFilter.ALL) }
    var prompt by remember { mutableStateOf("") }
    var attachments by remember { mutableStateOf(emptyList<String>()) }
    var useMemory by remember { mutableStateOf(true) }
    var notice by remember { mutableStateOf<String?>(null) }
    var readOnlyMap by remember { mutableStateOf(false) }
    var focusPrompt by remember { mutableStateOf(false) }
    val selectedTask = selectedTaskId?.let(tasks::get)
    val taskList = tasks.values.toList()

    fun submit(text: String = prompt) {
        val query = text.trim()
        if (query.isEmpty()) return
        store.createTask(query, attachments, useMemory)
        prompt = ""
        attachments = emptyList()
        notice = null
        store.taskDagStore.select(null)
        screen = AppScreen.ACTIVE
    }
    fun openTask(task: TaskGraph) {
        filter = TaskHubFilter.ALL
        store.taskDagStore.select(task.id)
        screen = when {
            task.status == NodeStatus.COMPLETED -> AppScreen.DONE
            hitl?.taskId == task.id -> AppScreen.KEYCHAIN
            else -> AppScreen.ACTIVE
        }
    }
    fun openTab(tab: BackgroundTabInfo) {
        store.taskDagStore.select(tab.taskId)
        screen = if (hitl?.tabId == tab.tabId) AppScreen.KEYCHAIN
        else if (tasks[tab.taskId]?.status == NodeStatus.COMPLETED) AppScreen.DONE else AppScreen.ACTIVE
    }
    fun openHitl() {
        if (hitl == null) screen = AppScreen.ACTIVE
        else {
            store.taskDagStore.select(hitl!!.taskId)
            screen = AppScreen.KEYCHAIN
        }
    }

    LaunchedEffect(hitl?.tabId) {
        val request = hitl ?: return@LaunchedEffect
        store.taskDagStore.select(request.taskId)
        screen = AppScreen.KEYCHAIN
        platformActions.onHitlRequested(request) { reference ->
            if (reference == null) store.dismissHitl(request.tabId)
            else store.completeKeychainAuth(request.tabId, reference)
            screen = AppScreen.ACTIVE
        }
    }

    MaterialTheme(colorScheme = darkColorScheme(background = Obsidian, surface = Charcoal, primary = Indigo, onBackground = PrimaryText, onSurface = PrimaryText)) {
        Surface(Modifier.fillMaxSize(), color = Obsidian) {
            when (screen) {
                AppScreen.HOME -> HomeScreen(
                    prompt, attachments, taskList, tabs.size, connection.status, connection.message, error, notice,
                    useMemory, onVoiceInput != null, onPickAttachments != null,
                    onPromptChange = { prompt = it },
                    onSubmit = ::submit,
                    onQuickPrompt = { prompt = it },
                    onVoice = {
                        val voice = onVoiceInput
                        if (voice == null) notice = "Voice input is not connected on this device."
                        else {
                            notice = "Listening…"
                            voice { transcript ->
                                val clean = transcript.trim()
                                if (clean.isBlank()) notice = "No speech was detected. Try again or type your request."
                                else { prompt = clean; submit(clean) }
                            }
                        }
                    },
                    onPick = {
                        val picker = onPickAttachments
                        if (picker == null) notice = "File selection is not connected on this device."
                        else picker { selected -> attachments = (attachments + selected).distinct(); notice = null }
                    },
                    onRemoveAttachment = { attachments = attachments - it },
                    onReviewAll = { filter = TaskHubFilter.ALL; screen = AppScreen.TASKS },
                    onReviewTabs = { filter = TaskHubFilter.ACTIVE_TABS; screen = AppScreen.TASKS },
                    onTaskClick = ::openTask,
                    onMemoryChange = { useMemory = it },
                    focusPromptOnOpen = focusPrompt,
                    onPromptFocusConsumed = { focusPrompt = false },
                )
                AppScreen.TASKS -> TasksScreen(
                    taskList, memories, tabs.values.toList(), filter, useMemory, connection.status, connection.message,
                    onBack = { screen = AppScreen.HOME },
                    onNew = { focusPrompt = true; screen = AppScreen.HOME },
                    onTaskClick = ::openTask,
                    onTabClick = ::openTab,
                    onMemoryChange = { useMemory = it },
                    onFilterChange = { filter = it },
                )
                AppScreen.ACTIVE -> ActiveTaskScreen(
                    task = selectedTask,
                    connectionMessage = connection.message,
                    errorMessage = error,
                    onResolve = store::resolveBarrier,
                    onMap = { readOnlyMap = false; screen = AppScreen.MAP },
                    onOpenHitl = ::openHitl,
                )
                AppScreen.MAP -> ExecutionMapScreen(
                    task = selectedTask,
                    readOnly = readOnlyMap,
                    onBack = { screen = if (readOnlyMap) AppScreen.DONE else AppScreen.ACTIVE },
                    onOpenUrl = platformActions.openExternalUrl,
                    onResolveBarrier = { taskId, _ -> store.taskDagStore.select(taskId); readOnlyMap = false; screen = AppScreen.ACTIVE },
                    onOpenHitl = ::openHitl,
                )
                AppScreen.KEYCHAIN -> KeychainScreen(
                    hitl,
                    platformActions.authenticateHitl,
                    onBack = { screen = AppScreen.ACTIVE },
                    onAuthenticated = { request, reference -> store.completeKeychainAuth(request.tabId, reference); screen = AppScreen.ACTIVE },
                    onManualComplete = { request -> store.dismissHitl(request.tabId); screen = AppScreen.ACTIVE },
                )
                AppScreen.DONE -> CompletedTaskScreen(
                    task = selectedTask,
                    onMap = { readOnlyMap = true; screen = AppScreen.MAP },
                    onOpenUrl = platformActions.openExternalUrl,
                    onNew = { screen = AppScreen.HOME },
                    onHandoff = { url -> if (hitl != null) openHitl() else platformActions.openExternalUrl(url) },
                )
            }
        }
    }
}

@Composable
private fun HomeScreen(
    prompt: String, attachments: List<String>, tasks: List<TaskGraph>, hiddenTabs: Int,
    status: ConnectionStatus, statusMessage: String, error: String?, notice: String?,
    useMemory: Boolean, voiceAvailable: Boolean, pickerAvailable: Boolean,
    onPromptChange: (String) -> Unit, onSubmit: (String) -> Unit, onQuickPrompt: (String) -> Unit,
    onVoice: () -> Unit, onPick: () -> Unit, onRemoveAttachment: (String) -> Unit,
    onReviewAll: () -> Unit, onReviewTabs: () -> Unit, onTaskClick: (TaskGraph) -> Unit,
    onMemoryChange: (Boolean) -> Unit,
    focusPromptOnOpen: Boolean, onPromptFocusConsumed: () -> Unit,
) {
    val promptFocusRequester = remember { FocusRequester() }
    LaunchedEffect(focusPromptOnOpen) {
        if (focusPromptOnOpen) {
            promptFocusRequester.requestFocus()
            onPromptFocusConsumed()
        }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Laizysink", color = PrimaryText, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                Text(" ✦", color = Indigo)
            }
            Surface(
                color = Charcoal, shape = CircleShape, border = BorderStroke(1.dp, Border),
                modifier = Modifier.clickable(onClick = onReviewTabs).sizeIn(minHeight = 48.dp),
            ) {
                Text("Tabs Hidden · $hiddenTabs Active", color = if (hiddenTabs > 0) Emerald else Muted, modifier = Modifier.padding(horizontal = 12.dp, vertical = 14.dp), fontSize = 11.sp)
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 5.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("AUTONOMOUS AGENT TASKS")
            TextButton(onClick = onReviewAll) { Text("Review all", color = Indigo) }
        }
        if (tasks.isEmpty()) Text("No background tasks yet.", color = Muted, fontSize = 12.sp)
        else prioritizedTasks(tasks).take(2).forEach { task -> TaskCard(task) { onTaskClick(task) } }

        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            AgentOrb()
            Spacer(Modifier.height(12.dp))
            Text("What should I handle for you?", color = PrimaryText, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
            Text("I’ll research live sources and prepare the next step while your tabs stay hidden.", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("Research cheap flights", "Summarize open articles", "Track package delivery").forEach { action ->
                    Surface(
                        color = Charcoal, shape = CircleShape, border = BorderStroke(1.dp, Border),
                        modifier = Modifier.weight(1f).clickable { onQuickPrompt(action) }.sizeIn(minHeight = 48.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp)) {
                            Text(action, color = PrimaryText, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            if (error != null) Text(error, color = ErrorRed, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp))
            else ConnectionLine(status, statusMessage, Modifier.padding(top = 12.dp))
            notice?.let { Text(it, color = Amber, fontSize = 11.sp, modifier = Modifier.padding(top = 5.dp)) }
        }

        if (attachments.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(bottom = 5.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            attachments.forEach { uri ->
                Surface(color = Charcoal, shape = CircleShape, border = BorderStroke(1.dp, Border)) {
                    Row(Modifier.padding(horizontal = 8.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(uri.substringAfterLast('/').ifBlank { uri }, color = Muted, fontSize = 10.sp, maxLines = 1)
                        Text("×", color = PrimaryText, modifier = Modifier.clickable { onRemoveAttachment(uri) }.padding(start = 7.dp))
                    }
                }
            }
        }
        Surface(color = Charcoal, shape = RoundedCornerShape(22.dp), border = BorderStroke(1.dp, Border), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(8.dp)) {
                OutlinedTextField(
                    value = prompt, onValueChange = onPromptChange, modifier = Modifier.fillMaxWidth().focusRequester(promptFocusRequester),
                    placeholder = { Text("Ask Laizysink anything…", color = Muted) }, minLines = 1, maxLines = 4,
                    shape = RoundedCornerShape(18.dp),
                )
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Button(onClick = onPick, colors = ButtonDefaults.buttonColors(containerColor = Obsidian), modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) {
                            Text(if (pickerAvailable) "＋ File" else "＋", color = Muted)
                        }
                        Button(onClick = onVoice, colors = ButtonDefaults.buttonColors(containerColor = if (voiceAvailable) Indigo else Obsidian), modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) {
                            Text("Mic", color = PrimaryText)
                        }
                    }
                    Button(onClick = { onSubmit(prompt) }, enabled = prompt.isNotBlank(), colors = ButtonDefaults.buttonColors(containerColor = Indigo), modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) {
                        Text("↑")
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(if (useMemory) "Active context is available to this task" else "This task will not reuse active context", color = Muted, fontSize = 10.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = { onMemoryChange(!useMemory) }) { Text(if (useMemory) "Memory on" else "Memory off", color = Indigo, fontSize = 11.sp) }
        }
    }
}

@Composable
private fun TasksScreen(
    tasks: List<TaskGraph>, memories: List<TemporalMemoryItem>, tabs: List<BackgroundTabInfo>,
    filter: TaskHubFilter, useMemory: Boolean, status: ConnectionStatus, statusMessage: String,
    onBack: () -> Unit, onNew: () -> Unit, onTaskClick: (TaskGraph) -> Unit, onTabClick: (BackgroundTabInfo) -> Unit,
    onMemoryChange: (Boolean) -> Unit, onFilterChange: (TaskHubFilter) -> Unit,
) {
    var manage by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ Back", color = Muted) }
            Text("All Tasks", color = PrimaryText, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
            Button(onClick = onNew, colors = ButtonDefaults.buttonColors(containerColor = Indigo), modifier = Modifier.sizeIn(minHeight = 48.dp)) { Text("+ New") }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip("All tasks", filter == TaskHubFilter.ALL) { onFilterChange(TaskHubFilter.ALL) }
            FilterChip("Hidden tabs (" + tabs.size + ")", filter == TaskHubFilter.ACTIVE_TABS) { onFilterChange(TaskHubFilter.ACTIVE_TABS) }
        }
        val latest = memories.firstOrNull()
        val sourceTask = latest?.let { item -> tasks.firstOrNull { it.id == item.task_id } }
        Surface(color = Charcoal, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, Border), modifier = Modifier.fillMaxWidth().clickable { manage = true }) {
            Row(Modifier.padding(13.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(sourceTask?.prompt?.takeIf(String::isNotBlank)?.let { "Active context: " + it } ?: "Active context window", color = PrimaryText, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (memories.isEmpty()) "No shared facts active" else memories.size.toString() + " shared facts available", color = Muted, fontSize = 11.sp)
                }
                Text("Manage", color = Indigo, fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp))
            }
        }
        if (filter == TaskHubFilter.ACTIVE_TABS) {
            SectionLabel("PAUSED BROWSER TABS (" + tabs.size + ")", Modifier.padding(top = 15.dp, bottom = 6.dp))
            if (tabs.isEmpty()) EmptyCard("No browser tabs are waiting for you.")
            else tabs.forEach { tab -> PausedTabCard(tab) { onTabClick(tab) } }
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            if (filter == TaskHubFilter.ALL) {
                val tabTasks = tabs.map { it.taskId }.toSet()
                val attention = tasks.filter { (it.status == NodeStatus.BLOCKED_USER || it.status == NodeStatus.FAILED) && it.id !in tabTasks }
                val working = tasks.filter { it.status == NodeStatus.RUNNING || it.status == NodeStatus.WAITING || it.status == NodeStatus.STALE }
                val finished = tasks.filter { it.status == NodeStatus.COMPLETED }
                if (tabs.isNotEmpty()) {
                    TaskGroup("NEEDS ATTENTION · PAUSED TABS (" + tabs.size + ")", emptyList(), onTaskClick)
                    tabs.forEach { tab -> PausedTabCard(tab) { onTabClick(tab) } }
                }
                TaskGroup("NEEDS ATTENTION (" + attention.size + ")", attention, onTaskClick)
                TaskGroup("WORKING IN BACKGROUND (" + working.size + ")", working, onTaskClick)
                TaskGroup("FINISHED (" + finished.size + ")", finished, onTaskClick)
            }
            if (tasks.isEmpty() && tabs.isEmpty()) EmptyCard("Tasks you start will appear here with their live status and sources.")
        }
        ConnectionLine(status, statusMessage, Modifier.padding(top = 8.dp))
    }
    if (manage) MemoryManagerDialog(memories, useMemory, onMemoryChange) { manage = false }
}

@Composable
private fun MemoryManagerDialog(items: List<TemporalMemoryItem>, useMemory: Boolean, onMemoryChange: (Boolean) -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(color = Charcoal, shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, Border), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Shared context", color = PrimaryText, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text("Freshness labels are reported by each source task.", color = Muted, fontSize = 12.sp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Use context for new tasks", color = PrimaryText, fontSize = 13.sp)
                        Text("Turn off to exclude shared facts from the next request.", color = Muted, fontSize = 11.sp)
                    }
                    Switch(checked = useMemory, onCheckedChange = onMemoryChange)
                }
                if (items.isEmpty()) EmptyCard("No context facts have been received yet.")
                else Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items.forEach { item ->
                        Surface(color = Obsidian, shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, Border)) {
                            Column(Modifier.fillMaxWidth().padding(9.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(item.key, color = PrimaryText, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                                    val tint = when (item.freshness_tag.name) { "USED" -> Emerald; "STALE_QUERIED" -> Amber; else -> Muted }
                                    StatusPill(item.freshness_tag.name.replace('_', ' '), tint)
                                }
                                Text(item.value, color = Muted, fontSize = 12.sp)
                            }
                        }
                    }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("Done", color = Indigo) }
            }
        }
    }
}

@Composable
private fun TaskGroup(title: String, tasks: List<TaskGraph>, onTaskClick: (TaskGraph) -> Unit) {
    SectionLabel(title, Modifier.padding(top = 15.dp, bottom = 6.dp))
    if (tasks.isEmpty()) Text("Nothing here yet.", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 4.dp))
    else prioritizedTasks(tasks).forEach { task -> TaskCard(task) { onTaskClick(task) } }
}

@Composable
private fun TaskCard(task: TaskGraph, onClick: () -> Unit) {
    val tint = when (task.status) {
        NodeStatus.BLOCKED_USER -> Amber
        NodeStatus.COMPLETED -> Emerald
        NodeStatus.FAILED -> ErrorRed
        else -> Indigo
    }
    val detail = when {
        task.barriers.isNotEmpty() -> task.barriers.first().question
        task.nodes.isEmpty() -> "Preparing the task plan…"
        else -> task.nodes.size.toString() + " branches · " + task.nodes.sumOf { it.sources.size } + " citations"
    }
    Surface(color = Charcoal, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, Border), modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).clickable(onClick = onClick).sizeIn(minHeight = 60.dp)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Surface(color = tint, shape = CircleShape, modifier = Modifier.sizeIn(minWidth = 10.dp, minHeight = 10.dp)) {}
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(task.prompt.ifBlank { "Research task" }, color = PrimaryText, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(detail, color = if (task.status == NodeStatus.BLOCKED_USER) Amber else Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            StatusPill(when (task.status) {
                NodeStatus.BLOCKED_USER -> "Your turn"
                NodeStatus.COMPLETED -> "Finished"
                NodeStatus.FAILED -> "Error"
                else -> "Working"
            }, tint)
        }
    }
}

@Composable
private fun PausedTabCard(tab: BackgroundTabInfo, onClick: () -> Unit) {
    Surface(color = Charcoal, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, Border), modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).clickable(onClick = onClick).sizeIn(minHeight = 58.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(tab.url.substringAfter("://").substringBefore('/'), color = PrimaryText, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Text("Paused browser tab · " + tab.status.replace('_', ' '), color = Amber, fontSize = 11.sp)
        }
    }
}

@Composable
private fun KeychainScreen(
    request: HitlTabRequest?,
    authenticate: suspend (HitlTabRequest) -> String?,
    onBack: () -> Unit,
    onAuthenticated: (HitlTabRequest, String) -> Unit,
    onManualComplete: (HitlTabRequest) -> Unit,
) {
    var working by remember(request?.tabId) { mutableStateOf(false) }
    var message by remember(request?.tabId) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        TextButton(onClick = onBack) { Text("‹ Back to task", color = Muted) }
        SectionLabel("HUMAN APPROVAL")
        if (request == null) {
            EmptyCard("No browser sign-in request is active.")
            return
        }
        Text(request.domain.ifBlank { request.url }, color = PrimaryText, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
        Text(request.url, color = Indigo, fontSize = 11.sp)
        if (request.tooltipText.isNotBlank()) Text(request.tooltipText, color = Muted)
        Text("Credentials stay on this device. Continue in the revealed browser or authenticate locally for keychain autofill.", color = Muted, fontSize = 12.sp)
        if (message != null) Text(message!!, color = Amber, fontSize = 11.sp)
        Button(
            onClick = {
                scope.launch {
                    working = true
                    val reference = runCatching { authenticate(request) }.getOrNull()
                    working = false
                    if (reference != null && reference.startsWith("vault://")) onAuthenticated(request, reference)
                    else message = "Authentication did not complete. You can retry or continue manually."
                }
            },
            enabled = !working,
            colors = ButtonDefaults.buttonColors(containerColor = Indigo),
            modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp),
        ) { Text(if (working) "Waiting for device authentication…" else "Autofill with saved keychain") }
        Button(onClick = { onManualComplete(request) }, colors = ButtonDefaults.buttonColors(containerColor = Charcoal), modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp)) {
            Text("I finished in the browser")
        }
    }
}

@Composable
private fun AgentOrb() {
    Box(Modifier.sizeIn(minWidth = 100.dp, minHeight = 100.dp), contentAlignment = Alignment.Center) {
        Surface(color = Indigo.copy(alpha = .12f), shape = CircleShape, modifier = Modifier.sizeIn(minWidth = 94.dp, minHeight = 94.dp)) {}
        Surface(color = Indigo.copy(alpha = .25f), shape = CircleShape, modifier = Modifier.sizeIn(minWidth = 62.dp, minHeight = 62.dp)) {}
        Text("✦", color = PrimaryText, fontSize = 30.sp)
    }
}

@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, color = Muted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, modifier = modifier)
}

@Composable
private fun StatusPill(text: String, tint: Color) {
    Surface(color = tint.copy(alpha = .15f), shape = CircleShape) {
        Text(text, color = tint, fontSize = 9.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp))
    }
}

@Composable
private fun FilterChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (selected) Indigo.copy(alpha = .2f) else Charcoal,
        shape = CircleShape,
        border = BorderStroke(1.dp, if (selected) Indigo else Border),
        modifier = Modifier.clickable(onClick = onClick).sizeIn(minHeight = 40.dp),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
            Text(text, color = if (selected) PrimaryText else Muted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun EmptyCard(message: String) {
    Surface(color = Charcoal, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, Border), modifier = Modifier.fillMaxWidth()) {
        Text(message, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(13.dp))
    }
}

@Composable
private fun ConnectionLine(status: ConnectionStatus, message: String, modifier: Modifier = Modifier) {
    val color = when (status) {
        ConnectionStatus.CONNECTED -> Emerald
        ConnectionStatus.CONNECTING -> Amber
        ConnectionStatus.ERROR -> ErrorRed
        ConnectionStatus.DISCONNECTED -> Muted
    }
    Text(message, color = color, fontSize = 11.sp, modifier = modifier)
}

private fun prioritizedTasks(tasks: List<TaskGraph>): List<TaskGraph> =
    tasks.withIndex().sortedWith(compareBy<IndexedValue<TaskGraph>>({ taskPriority(it.value.status) }, { -it.index })).map { it.value }

private fun taskPriority(status: NodeStatus): Int = when (status) {
    NodeStatus.BLOCKED_USER, NodeStatus.FAILED -> 0
    NodeStatus.RUNNING, NodeStatus.WAITING, NodeStatus.STALE -> 1
    NodeStatus.COMPLETED -> 2
}
