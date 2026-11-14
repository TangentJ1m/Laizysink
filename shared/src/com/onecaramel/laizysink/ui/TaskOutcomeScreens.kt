package com.onecaramel.laizysink

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.onecaramel.laizysink.state.DataBarrier
import com.onecaramel.laizysink.state.ExaSource
import com.onecaramel.laizysink.state.FreshnessTag
import com.onecaramel.laizysink.state.NodeStatus
import com.onecaramel.laizysink.state.TaskGraph
import com.onecaramel.laizysink.state.WorkNode
import com.onecaramel.laizysink.state.WorkTaskType

private val OutcomeBg = Color(0xFF0D0F14)
private val OutcomeSurface = Color(0xFF161922)
private val OutcomeInset = Color(0xFF10131B)
private val OutcomeBorder = Color(0xFF252A37)
private val OutcomeIndigo = Color(0xFF6366F1)
private val OutcomeGreen = Color(0xFF10B981)
private val OutcomeAmber = Color(0xFFF59E0B)
private val OutcomeText = Color(0xFFF3F4F6)
private val OutcomeMuted = Color(0xFF9CA3AF)
private val OutcomeError = Color(0xFFF87171)

/** Active task detail screen. All task claims and source content come from the live task graph. */
@Composable
fun ActiveTaskScreen(
    task: TaskGraph?,
    connectionMessage: String?,
    errorMessage: String?,
    onResolve: (taskId: String, barrierId: String, answer: String) -> Unit,
    onMap: () -> Unit,
    onOpenHitl: () -> Unit,
) {
    var previewSource by remember(task?.id) { mutableStateOf<ExaSource?>(null) }
    var showSourceReview by remember(task?.id) { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(OutcomeBg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        OutcomeHeader(
            title = task?.prompt?.takeIf(String::isNotBlank) ?: "Active task",
            eyebrow = "AUTONOMOUS TASK",
            action = "Map",
            onAction = onMap,
        )

        if (!connectionMessage.isNullOrBlank()) ConnectionNotice(connectionMessage)
        if (!errorMessage.isNullOrBlank()) NoticeCard("Task update", errorMessage, OutcomeError)

        if (task == null) {
            EmptyPanel(
                title = "Waiting for task data",
                body = "The task graph will appear here after the backend accepts a prompt. You can return Home from the app navigation.",
            )
        } else {
            val sources = task.nodes.flatMap(WorkNode::sources).distinctBy(ExaSource::id)
            val selectedSources = sources.filter(ExaSource::selected)
            val rejectedSources = sources.filterNot(ExaSource::selected)
            val blockedBrowserWork = task.nodes
                .flatMap(WorkNode::work_tasks)
                .any { it.task_type == WorkTaskType.HITL_BROWSER && it.status == NodeStatus.BLOCKED_USER }

            TaskProgress(task)

            if (task.status == NodeStatus.FAILED) {
                NoticeCard("Research stopped", "The backend marked this task as failed. Any returned findings below are partial.", OutcomeError)
            }

            if (task.barriers.isNotEmpty()) {
                task.barriers.forEach { barrier -> BarrierPanel(barrier, onResolve) }
            } else if (task.status == NodeStatus.BLOCKED_USER && !blockedBrowserWork) {
                NoticeCard(
                    "Input needed",
                    "This task is blocked, but the server has not supplied a data barrier with choices yet.",
                    OutcomeAmber,
                )
            }

            if (blockedBrowserWork) {
                Panel {
                    SectionHeading("Paused browser", "A task work item is waiting for a human step.")
                    Button(
                        onClick = onOpenHitl,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = OutcomeAmber, contentColor = Color(0xFF17130A)),
                    ) { Text("Review paused tab", fontWeight = FontWeight.SemiBold) }
                }
            }

            ResearchPanel(
                sources = sources,
                onSourceClick = { previewSource = it },
                onViewAll = { showSourceReview = true },
            )

            if (task.nodes.isNotEmpty()) {
                SectionHeading("Execution findings", "Branch summaries returned for this task.")
                task.nodes.forEach { node ->
                    NodeFindingPanel(node) { previewSource = it }
                }
            } else if (task.status != NodeStatus.FAILED) {
                EmptyPanel(
                    title = "Research is starting",
                    body = "No branch results have arrived yet. This screen will update as the task graph changes.",
                )
            }

            if (task.status == NodeStatus.COMPLETED) {
                NoticeCard("Task complete", "The backend marked this task complete. Open the map to inspect its recorded branches.", OutcomeGreen)
            }
        }
    }

    if (showSourceReview && task != null) {
        val sources = task.nodes.flatMap(WorkNode::sources).distinctBy(ExaSource::id)
        SourceReviewDialog(
            selected = sources.filter(ExaSource::selected),
            rejected = sources.filterNot(ExaSource::selected),
            onDismiss = { showSourceReview = false },
            onSourceClick = {
                showSourceReview = false
                previewSource = it
            },
        )
    }

    previewSource?.let { source ->
        CitationPreviewDialog(
            source = source,
            onDismiss = { previewSource = null },
            onOpen = { url ->
                runCatching { uriHandler.openUri(url) }
                previewSource = null
            },
        )
    }
}

/** Completed task audit screen. It does not infer a merged verdict or a transaction URL. */
@Composable
fun CompletedTaskScreen(
    task: TaskGraph?,
    onMap: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onNew: () -> Unit,
    onHandoff: (String) -> Unit,
) {
    var previewSource by remember(task?.id) { mutableStateOf<ExaSource?>(null) }
    var showAlternatives by remember(task?.id) { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(OutcomeBg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        OutcomeHeader(
            title = task?.prompt?.takeIf(String::isNotBlank) ?: "Completed task",
            eyebrow = "TASK REVIEW",
            action = "View map",
            onAction = onMap,
        )

        if (task == null) {
            EmptyPanel(
                title = "No completed task selected",
                body = "Choose a finished task from the task list to review its returned findings and citations.",
            )
            Button(onClick = onNew, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("Start a new task")
            }
        } else {
            val sources = task.nodes.flatMap(WorkNode::sources).distinctBy(ExaSource::id)
            val selectedSources = sources.filter(ExaSource::selected)
            val rejectedSources = sources.filterNot(ExaSource::selected)
            val reviewSource = selectedSources.firstOrNull()

            TaskProgress(task)

            if (task.status == NodeStatus.FAILED) {
                NoticeCard("Partial result", "The task ended with an error. The findings shown here are the data returned before it stopped.", OutcomeError)
            } else {
                NoticeCard(
                    "Recorded task status",
                    "${statusLabel(task.status)}. This audit shows the summaries and citations stored in the task graph.",
                    if (task.status == NodeStatus.COMPLETED) OutcomeGreen else OutcomeAmber,
                )
            }

            if (task.memory.isNotEmpty()) {
                Panel {
                    SectionHeading("Inherited context", "Memory items attached to this task, with their recorded freshness.")
                    task.memory.forEach { memory ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            FreshnessPill(memory.freshness_tag)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(memory.key, color = OutcomeText, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                Text(memory.value, color = OutcomeMuted, fontSize = 13.sp)
                                val dates = listOfNotNull(memory.event_start_date, memory.event_end_date)
                                if (dates.isNotEmpty()) {
                                    Text(dates.joinToString(" – "), color = OutcomeMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                }
                            }
                        }
                    }
                }
            }

            if (task.nodes.isEmpty()) {
                EmptyPanel(
                    title = "No branch findings were returned",
                    body = "The task has no node summaries to audit yet. The map remains available for its current graph state.",
                )
            } else {
                SectionHeading("Recorded findings", "Each summary is shown under the node that returned it.")
                task.nodes.forEach { node ->
                    CompletedNodePanel(node, onSourceClick = { previewSource = it })
                }
            }

            Panel {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SectionHeading("Source alternatives", "Selected evidence and considered sources are kept separate.")
                    TextButton(onClick = { showAlternatives = !showAlternatives }) {
                        Text(if (showAlternatives) "Hide" else "Review", color = OutcomeIndigo)
                    }
                }
                if (showAlternatives) {
                    SourceGroup("Selected sources", selectedSources, onClick = { previewSource = it })
                    SourceGroup("Considered and discarded", rejectedSources, onClick = { previewSource = it })
                } else {
                    Text(
                        "${selectedSources.size} selected · ${rejectedSources.size} considered and discarded",
                        color = OutcomeMuted,
                        fontSize = 13.sp,
                    )
                }
            }

            Panel {
                SectionHeading("Continue from a source", "Opening a source is a user-approved review step; this task graph contains no staged booking or payment URL.")
                if (reviewSource == null) {
                    Text("No selected source URL is available to open.", color = OutcomeMuted, fontSize = 13.sp)
                    OutlinedButton(onClick = onMap, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text("Inspect execution map", color = OutcomeText)
                    }
                } else {
                    Text(reviewSource.title, color = OutcomeText, fontWeight = FontWeight.SemiBold)
                    Text(reviewSource.domain, color = OutcomeMuted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    Button(
                        onClick = { onHandoff(reviewSource.url) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = OutcomeIndigo),
                    ) { Text("Open selected source for review") }
                    Text(
                        "No purchase, message, or payment is submitted by this screen.",
                        color = OutcomeMuted,
                        fontSize = 11.sp,
                    )
                }
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onMap, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Text("Inspect map", color = OutcomeText)
                }
                Button(onClick = onNew, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Text("New task")
                }
            }
        }
    }

    previewSource?.let { source ->
        CitationPreviewDialog(
            source = source,
            onDismiss = { previewSource = null },
            onOpen = { url ->
                onOpenUrl(url)
                previewSource = null
            },
        )
    }
}

@Composable
private fun OutcomeHeader(title: String, eyebrow: String, action: String, onAction: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(eyebrow, color = OutcomeIndigo, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
            Text(
                title,
                color = OutcomeText,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        OutlinedButton(onClick = onAction, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(action, color = OutcomeText, fontSize = 12.sp)
        }
    }
}

@Composable
private fun TaskProgress(task: TaskGraph) {
    val isComplete = task.status == NodeStatus.COMPLETED
    val hasBarrier = task.barriers.isNotEmpty()
    val running = task.nodes.any { it.status == NodeStatus.RUNNING }
    val hasNodes = task.nodes.isNotEmpty()
    val current = when {
        isComplete -> 3
        hasBarrier -> 2
        running || hasNodes -> 1
        else -> 0
    }
    val steps = listOf("Plan", "Research paths", "Needs input", "Compare & book")

    Panel {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionHeading("Task progress", "Live status from the task graph.")
            StatusPill(statusLabel(task.status), statusColor(task.status))
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            steps.forEachIndexed { index, label ->
                val done = isComplete || index < current
                val active = !isComplete && index == current
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .background(
                                color = when {
                                    done -> OutcomeGreen
                                    active && index == 2 -> OutcomeAmber
                                    active -> OutcomeIndigo
                                    else -> OutcomeInset
                                },
                                shape = CircleShape,
                            )
                            .border(1.dp, if (done || active) Color.Transparent else OutcomeBorder, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(if (done) "✓" else "${index + 1}", color = if (done) OutcomeBg else OutcomeText, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                    Text(
                        label,
                        color = when {
                            done -> OutcomeGreen
                            active && index == 2 -> OutcomeAmber
                            active -> OutcomeIndigo
                            else -> OutcomeMuted
                        },
                        fontSize = 9.sp,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 2,
                    )
                }
            }
        }
    }
}

@Composable
private fun BarrierPanel(barrier: DataBarrier, onResolve: (String, String, String) -> Unit) {
    var selected by remember(barrier.barrier_id) { mutableStateOf<String?>(null) }
    var otherText by remember(barrier.barrier_id) { mutableStateOf("") }
    val otherChoice = "__other__"
    val answer = if (selected == otherChoice) otherText.trim() else selected.orEmpty()

    Panel {
        StatusPill(barrier.badge_text ?: "Quick input needed", OutcomeAmber)
        Text(barrier.question, color = OutcomeText, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        if (barrier.options.isEmpty()) {
            Text("The server did not provide suggested choices. Enter the missing details below.", color = OutcomeMuted, fontSize = 13.sp)
        }
        barrier.options.forEach { option ->
            val isSelected = selected == option
            Surface(
                modifier = Modifier.fillMaxWidth().clickable { selected = option },
                color = if (isSelected) Color(0xFF202139) else OutcomeInset,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, if (isSelected) OutcomeIndigo else OutcomeBorder),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = isSelected, onClick = { selected = option })
                    Text(option, color = OutcomeText, fontSize = 13.sp, modifier = Modifier.weight(1f))
                }
            }
        }
        if (barrier.allow_other_voice_text) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = if (selected == otherChoice) Color(0xFF202139) else OutcomeInset,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, if (selected == otherChoice) OutcomeIndigo else OutcomeBorder),
            ) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { selected = otherChoice },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected == otherChoice, onClick = { selected = otherChoice })
                        Text("Other details", color = OutcomeText, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                    if (selected == otherChoice) {
                        TextField(
                            value = otherText,
                            onValueChange = { otherText = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("Type the dates, budget, or details…", color = OutcomeMuted) },
                            minLines = 2,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = OutcomeBg,
                                unfocusedContainerColor = OutcomeBg,
                                focusedTextColor = OutcomeText,
                                unfocusedTextColor = OutcomeText,
                                focusedIndicatorColor = OutcomeIndigo,
                                unfocusedIndicatorColor = OutcomeBorder,
                            ),
                        )
                    }
                }
            }
        }
        Button(
            onClick = { onResolve(barrier.task_id, barrier.barrier_id, answer) },
            enabled = answer.isNotBlank(),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = OutcomeIndigo),
        ) { Text("Continue task", fontWeight = FontWeight.SemiBold) }
    }
}

@Composable
private fun ResearchPanel(
    sources: List<ExaSource>,
    onSourceClick: (ExaSource) -> Unit,
    onViewAll: () -> Unit,
) {
    val selected = sources.filter(ExaSource::selected)
    val visible = (selected.ifEmpty { sources }).take(2)
    Panel {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                SectionHeading("Live Exa research", "Evidence returned by this task.")
                Text("${sources.size} source${if (sources.size == 1) "" else "s"} · ${selected.size} selected", color = OutcomeMuted, fontSize = 11.sp)
            }
            Column(horizontalAlignment = Alignment.End) {
                StatusPill(if (sources.isEmpty()) "WAITING" else "LIVE SOURCES", if (sources.isEmpty()) OutcomeMuted else OutcomeGreen)
                TextButton(onClick = onViewAll, enabled = sources.isNotEmpty()) {
                    Text("View all", color = if (sources.isNotEmpty()) OutcomeIndigo else OutcomeMuted, fontSize = 11.sp)
                }
            }
        }
        if (visible.isEmpty()) {
            Text("No citations have arrived yet. Source cards will appear when Exa results are included in the task graph.", color = OutcomeMuted, fontSize = 13.sp)
        } else {
            visible.forEach { CitationCard(it, onClick = { onSourceClick(it) }) }
        }
        if (sources.size > visible.size) {
            Text("Open each branch below to inspect the remaining ${sources.size - visible.size} source(s).", color = OutcomeMuted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun SourceReviewDialog(
    selected: List<ExaSource>,
    rejected: List<ExaSource>,
    onDismiss: () -> Unit,
    onSourceClick: (ExaSource) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = OutcomeSurface,
        titleContentColor = OutcomeText,
        textContentColor = OutcomeMuted,
        title = { Text("Exa source review", fontWeight = FontWeight.SemiBold) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text("Selected and leading sources", color = OutcomeText, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                SourceGroup("", selected, onClick = onSourceClick)
                Text("Considered and discarded sources", color = OutcomeText, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                SourceGroup("", rejected, onClick = onSourceClick)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close", color = OutcomeIndigo) } },
    )
}

@Composable
private fun NodeFindingPanel(node: WorkNode, onSourceClick: (ExaSource) -> Unit) {
    Panel {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Text(node.title, color = OutcomeText, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, modifier = Modifier.weight(1f))
            StatusPill(statusLabel(node.status), statusColor(node.status))
        }
        if (node.plain_summary.isNotBlank()) Text(node.plain_summary, color = OutcomeText, fontSize = 14.sp)
        if (node.highlight_chips.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                node.highlight_chips.take(3).forEach { MetricChip(it) }
            }
        }
        if (node.decision_rationale.isNotBlank()) {
            Text("Decision rationale", color = OutcomeMuted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Text(node.decision_rationale, color = OutcomeMuted, fontSize = 13.sp)
        }
        val nodeSources = node.sources
        if (nodeSources.isNotEmpty()) {
            Text("Branch sources", color = OutcomeMuted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            nodeSources.forEach { CitationCard(it, onClick = { onSourceClick(it) }) }
        }
    }
}

@Composable
private fun CompletedNodePanel(node: WorkNode, onSourceClick: (ExaSource) -> Unit) {
    Panel {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(node.title, color = OutcomeText, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, modifier = Modifier.weight(1f))
            StatusPill(statusLabel(node.status), statusColor(node.status))
        }
        FreshnessPill(node.freshness_tag)
        if (node.plain_summary.isBlank()) {
            Text("No synthesis was returned for this branch.", color = OutcomeMuted, fontSize = 13.sp)
        } else {
            Text(node.plain_summary, color = OutcomeText, fontSize = 14.sp)
        }
        if (node.highlight_chips.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                node.highlight_chips.take(3).forEach { MetricChip(it) }
            }
        }
        if (node.inherited_context.isNotEmpty()) {
            Text("Context used", color = OutcomeMuted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            node.inherited_context.forEach { (key, value) ->
                Text("$key · $value", color = OutcomeMuted, fontSize = 12.sp)
            }
        }
        if (node.decision_rationale.isNotBlank()) {
            Text("Decision rationale", color = OutcomeMuted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Text(node.decision_rationale, color = OutcomeMuted, fontSize = 13.sp)
        }
        if (node.sources.isNotEmpty()) {
            Text("Verified sources", color = OutcomeMuted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            node.sources.forEach { CitationCard(it, onClick = { onSourceClick(it) }) }
        }
    }
}

@Composable
private fun CitationCard(source: ExaSource, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = OutcomeInset,
        shape = RoundedCornerShape(13.dp),
        border = BorderStroke(1.dp, OutcomeBorder),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier.size(28.dp).background(Color(0xFF22263A), RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(source.domain.firstOrNull()?.uppercase() ?: "↗", color = OutcomeIndigo, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text(source.domain, color = OutcomeText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Text(source.title, color = OutcomeMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                StatusPill(if (source.selected) "SELECTED" else "CONSIDERED", if (source.selected) OutcomeGreen else OutcomeMuted)
            }
            if (source.highlighted_quote.isNotBlank()) {
                Text("“${source.highlighted_quote}”", color = OutcomeText, fontSize = 12.sp, lineHeight = 17.sp)
            }
            if (!source.verification_badge.isNullOrBlank()) {
                Text(source.verification_badge, color = OutcomeGreen, fontSize = 10.sp)
            }
            if (!source.selected && !source.rejection_reason.isNullOrBlank()) {
                Text("Considered and discarded: ${source.rejection_reason}", color = OutcomeMuted, fontSize = 11.sp)
            }
            Text(source.url, color = OutcomeIndigo, fontSize = 10.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun SourceGroup(title: String, sources: List<ExaSource>, onClick: (ExaSource) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (title.isNotBlank()) Text(title, color = OutcomeText, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        if (sources.isEmpty()) {
            Text("No sources in this group.", color = OutcomeMuted, fontSize = 12.sp)
        } else {
            sources.forEach { CitationCard(it, onClick = { onClick(it) }) }
        }
    }
}

@Composable
private fun CitationPreviewDialog(source: ExaSource, onDismiss: () -> Unit, onOpen: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = OutcomeSurface,
        titleContentColor = OutcomeText,
        textContentColor = OutcomeMuted,
        title = { Text(source.title, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    StatusPill(if (source.selected) "SELECTED" else "CONSIDERED", if (source.selected) OutcomeGreen else OutcomeMuted)
                    Text(source.domain, color = OutcomeMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
                Text(source.highlighted_quote, color = OutcomeText, fontSize = 13.sp)
                if (!source.verification_badge.isNullOrBlank()) Text(source.verification_badge, color = OutcomeGreen, fontSize = 11.sp)
                Text(source.url, color = OutcomeIndigo, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = { onOpen(source.url) }) { Text("Open source", color = OutcomeIndigo) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close", color = OutcomeMuted) }
        },
    )
}

@Composable
private fun ConnectionNotice(message: String) {
    val lower = message.lowercase()
    val unavailable = lower.contains("unavailable") || lower.contains("disconnected") || lower.contains("closed")
    NoticeCard(
        title = if (unavailable) "Backend connection interrupted" else "Backend connection",
        message = if (unavailable) "$message Updates will resume when the client reconnects." else message,
        accent = if (unavailable) OutcomeAmber else OutcomeMuted,
    )
}

@Composable
private fun NoticeCard(title: String, message: String, accent: Color) {
    Panel {
        Text(title, color = accent, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        Text(message, color = OutcomeMuted, fontSize = 13.sp)
    }
}

@Composable
private fun EmptyPanel(title: String, body: String) {
    Panel {
        Text(title, color = OutcomeText, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        Text(body, color = OutcomeMuted, fontSize = 13.sp)
    }
}

@Composable
private fun Panel(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = OutcomeSurface,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, OutcomeBorder),
    ) {
        Column(
            modifier = Modifier.padding(15.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content,
        )
    }
}

@Composable
private fun SectionHeading(title: String, subtitle: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, color = OutcomeText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        if (!subtitle.isNullOrBlank()) Text(subtitle, color = OutcomeMuted, fontSize = 11.sp)
    }
}

@Composable
private fun StatusPill(label: String, color: Color) {
    Surface(color = color.copy(alpha = 0.12f), shape = CircleShape, border = BorderStroke(1.dp, color.copy(alpha = 0.32f))) {
        Text(
            label,
            color = color,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.4.sp,
            maxLines = 1,
        )
    }
}

@Composable
private fun FreshnessPill(tag: FreshnessTag) {
    val color = when (tag) {
        FreshnessTag.USED -> OutcomeGreen
        FreshnessTag.STALE_QUERIED -> OutcomeAmber
        FreshnessTag.ENTIRELY_STALE -> OutcomeMuted
    }
    StatusPill(tag.name.replace('_', ' '), color)
}

@Composable
private fun MetricChip(label: String) {
    Surface(color = Color(0xFF202139), shape = RoundedCornerShape(9.dp), border = BorderStroke(1.dp, OutcomeBorder)) {
        Text(
            label,
            color = OutcomeText,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
        )
    }
}

private fun statusLabel(status: NodeStatus): String = when (status) {
    NodeStatus.WAITING -> "Waiting"
    NodeStatus.RUNNING -> "Working"
    NodeStatus.BLOCKED_USER -> "Needs input"
    NodeStatus.COMPLETED -> "Complete"
    NodeStatus.STALE -> "Stale"
    NodeStatus.FAILED -> "Failed"
}

private fun statusColor(status: NodeStatus): Color = when (status) {
    NodeStatus.WAITING -> OutcomeMuted
    NodeStatus.RUNNING -> OutcomeIndigo
    NodeStatus.BLOCKED_USER -> OutcomeAmber
    NodeStatus.COMPLETED -> OutcomeGreen
    NodeStatus.STALE -> OutcomeAmber
    NodeStatus.FAILED -> OutcomeError
}
