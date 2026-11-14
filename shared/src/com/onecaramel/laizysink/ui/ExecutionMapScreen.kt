package com.onecaramel.laizysink.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalDensity
import com.onecaramel.laizysink.state.ExaSource
import com.onecaramel.laizysink.state.NodeStatus
import com.onecaramel.laizysink.state.TaskGraph
import com.onecaramel.laizysink.state.WorkNode
import com.onecaramel.laizysink.state.WorkTask
import com.onecaramel.laizysink.state.WorkTaskType
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private val MapObsidian = Color(0xFF0D0F14)
private val MapCharcoal = Color(0xFF161922)
private val MapBorder = Color(0xFF252A37)
private val MapIndigo = Color(0xFF6366F1)
private val MapEmerald = Color(0xFF10B981)
private val MapAmber = Color(0xFFF59E0B)
private val MapPrimary = Color(0xFFF3F4F6)
private val MapMuted = Color(0xFF9CA3AF)
private val MapError = Color(0xFFF87171)
private val MapGrid = Color(0xFF252A37).copy(alpha = 0.72f)

/**
 * A two-layer view over the graph currently available in the task snapshot.
 * WorkNode is the graph-node type in the current client contract; its child
 * WorkTasks are shown as satellites while the selected node is expanded.
 */
@Composable
fun ExecutionMapScreen(
    task: TaskGraph?,
    readOnly: Boolean,
    onBack: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onResolveBarrier: (taskId: String, barrierId: String) -> Unit,
    onOpenHitl: () -> Unit,
) {
    var expandedNodeId by remember(task?.id) {
        mutableStateOf(task?.nodes?.firstOrNull(::isActionableNode)?.id ?: task?.nodes?.firstOrNull()?.id)
    }
    var mapScale by remember(task?.id) { mutableStateOf(1f) }
    var mapPan by remember(task?.id) { mutableStateOf(Offset.Zero) }

    LaunchedEffect(task?.id, task?.nodes?.map { it.id }) {
        val nodes = task?.nodes.orEmpty()
        if (expandedNodeId !in nodes.map { it.id }) {
            expandedNodeId = nodes.firstOrNull(::isActionableNode)?.id ?: nodes.firstOrNull()?.id
        }
    }

    if (task == null) {
        Column(
            Modifier.fillMaxSize().background(MapObsidian).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("EXECUTION MAP", color = MapIndigo, fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            EmptyMapCard("Choose a task to inspect its execution map.", "The graph will appear here when a task is selected.")
            Button(onClick = onBack, colors = ButtonDefaults.buttonColors(containerColor = MapCharcoal)) {
                Text("Back to task", color = MapPrimary)
            }
        }
        return
    }

    val focusedNode = task.nodes.firstOrNull { it.id == expandedNodeId }
    val failedNodeCount = task.nodes.count { it.status == NodeStatus.FAILED }
    val failedWorkCount = task.nodes.sumOf { node -> node.work_tasks.count { it.status == NodeStatus.FAILED } }
    val failedCount = failedNodeCount + failedWorkCount
    val hasPartialResults = failedCount > 0 && task.nodes.any { it.status != NodeStatus.FAILED }

    Column(
        Modifier.fillMaxSize().background(MapObsidian).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().height(46.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("EXECUTION MAP", color = MapIndigo, fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                Text(
                    task.prompt.ifBlank { "Research task" },
                    color = MapPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (readOnly) StatusPill("AUDIT", MapEmerald)
            Button(
                onClick = onBack,
                colors = ButtonDefaults.buttonColors(containerColor = MapCharcoal),
                shape = RoundedCornerShape(12.dp),
            ) { Text("Simple", color = MapPrimary, fontSize = 12.sp) }
        }

        if (task.status == NodeStatus.FAILED) {
            StateNotice("This task failed", "The map shows the graph state returned before it stopped.", MapError)
        } else if (hasPartialResults) {
            StateNotice("Partial graph", "$failedCount work path${if (failedCount == 1) "" else "s"} failed; other returned paths remain available.", MapAmber)
        }

        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .weight(0.56f)
                .clip(RoundedCornerShape(20.dp))
                .background(MapCanvas)
                .pointerInput(task.id) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        mapScale = (mapScale * zoom).coerceIn(0.65f, 1.8f)
                        mapPan += pan
                    }
                },
        ) {
            val density = LocalDensity.current
            val widthPx = constraints.maxWidth.toFloat()
            val heightPx = constraints.maxHeight.toFloat()
            val nodes = task.nodes
            val selectedId = expandedNodeId
            val basePoints = nodePositions(nodes, selectedId, widthPx, heightPx, density)
            val workTasks = focusedNode?.work_tasks.orEmpty()
            val satellitePositions = satellitePositions(workTasks.size.coerceAtMost(6), widthPx, heightPx, density)
            val center = Offset(widthPx / 2f, heightPx / 2f)
            val points = basePoints.mapValues { (_, point) -> center + (point - center) * mapScale + mapPan }
            val satellites = satellitePositions.map { point -> center + (point - center) * mapScale + mapPan }

            Canvas(Modifier.fillMaxSize()) {
                val gridStep = 24.dp.toPx()
                var gridY = gridStep / 2f
                while (gridY < size.height) {
                    var gridX = gridStep / 2f
                    while (gridX < size.width) {
                        drawCircle(MapGrid, radius = 1.1.dp.toPx(), center = Offset(gridX, gridY))
                        gridX += gridStep
                    }
                    gridY += gridStep
                }

                nodes.forEach { child ->
                    val childPoint = points[child.id] ?: return@forEach
                    child.parent_ids.forEach { parentId ->
                        val parentPoint = points[parentId] ?: return@forEach
                        val isFocusedEdge = child.id == selectedId || parentId == selectedId
                        drawLine(
                            color = if (isFocusedEdge) MapIndigo.copy(alpha = 0.82f) else MapBorder,
                            start = parentPoint,
                            end = childPoint,
                            strokeWidth = if (isFocusedEdge) 2.dp.toPx() else 1.3.dp.toPx(),
                        )
                    }
                }

                focusedNode?.let { node ->
                    val focusPoint = points[node.id]
                    if (focusPoint != null) {
                        workTasks.take(satellites.size).forEachIndexed { index, _ ->
                            drawLine(
                                color = MapIndigo.copy(alpha = 0.54f),
                                start = focusPoint,
                                end = satellites[index],
                                strokeWidth = 1.3.dp.toPx(),
                            )
                        }
                    }
                }

                nodes.forEach { node ->
                    val point = points[node.id] ?: return@forEach
                    val selected = node.id == selectedId
                    drawCircle(
                        color = if (selected) MapIndigo.copy(alpha = 0.18f) else MapStatusColor(node.status).copy(alpha = 0.17f),
                        radius = if (selected) 22.dp.toPx() else 15.dp.toPx(),
                        center = point,
                    )
                    drawCircle(
                        color = if (selected) MapIndigo else MapStatusColor(node.status),
                        radius = if (selected) 6.dp.toPx() else 4.dp.toPx(),
                        center = point,
                    )
                }
            }

            nodes.forEach { node ->
                val point = points[node.id] ?: return@forEach
                val selected = node.id == selectedId
                val cardWidth = if (selected) 148.dp else siblingCardWidth(nodes.size)
                val cardHeight = if (selected) 58.dp else 52.dp
                Surface(
                    color = if (selected) MapIndigo.copy(alpha = 0.24f) else MapCharcoal,
                    shape = RoundedCornerShape(if (selected) 15.dp else 12.dp),
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                (point.x - with(density) { cardWidth.toPx() / 2f }).roundToInt(),
                                (point.y - with(density) { cardHeight.toPx() / 2f }).roundToInt(),
                            )
                        }
                        .width(cardWidth)
                        .height(cardHeight)
                        .border(if (selected) 1.dp else 0.7.dp, if (selected) MapIndigo else MapBorder, RoundedCornerShape(if (selected) 15.dp else 12.dp))
                        .clickable { expandedNodeId = node.id },
                ) {
                    if (selected) {
                        Column(
                            Modifier.fillMaxSize().padding(horizontal = 9.dp, vertical = 6.dp),
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(node.title, color = MapPrimary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(node.status.name.replace('_', ' '), color = MapStatusColor(node.status), fontSize = 8.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
                        }
                    } else {
                        Column(
                            Modifier.fillMaxSize().padding(horizontal = 7.dp, vertical = 5.dp),
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Box(Modifier.size(6.dp).clip(CircleShape).background(MapStatusColor(node.status)))
                                Text(node.title, color = MapPrimary, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Text("${node.work_tasks.size} items", color = MapMuted, fontSize = 8.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
                        }
                    }
                }
            }

            workTasks.take(satellites.size).forEachIndexed { index, workTask ->
                val point = satellites[index]
                val satelliteWidth = 94.dp
                val satelliteHeight = 38.dp
                Surface(
                    color = MapObsidian,
                    shape = RoundedCornerShape(11.dp),
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                (point.x - with(density) { satelliteWidth.toPx() / 2f }).roundToInt(),
                                (point.y - with(density) { satelliteHeight.toPx() / 2f }).roundToInt(),
                            )
                        }
                        .width(satelliteWidth)
                        .height(satelliteHeight)
                        .border(1.dp, MapBorder, RoundedCornerShape(11.dp)),
                ) {
                    Column(
                        Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(workTask.label, color = MapPrimary, fontSize = 8.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(workTask.status.name.replace('_', ' '), color = MapStatusColor(workTask.status), fontSize = 7.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
                    }
                }
            }

            if (task.nodes.isEmpty()) {
                EmptyMapCard(
                    title = if (task.status == NodeStatus.RUNNING || task.status == NodeStatus.WAITING) "Building the execution graph" else "No graph nodes returned",
                    detail = if (task.status == NodeStatus.RUNNING || task.status == NodeStatus.WAITING) "The map will fill in as the backend publishes task branches." else "This task snapshot does not include node details.",
                    modifier = Modifier.align(Alignment.Center).padding(18.dp),
                )
            }

            Row(
                Modifier.align(Alignment.BottomStart).padding(9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                StatusPill("${task.nodes.size} NODES", MapMuted)
                if (task.nodes.none { node -> node.parent_ids.any { id -> task.nodes.any { it.id == id } } } && task.nodes.size > 1) {
                    Text("Links unavailable", color = MapMuted, fontSize = 8.sp)
                }
            }
            Column(
                Modifier.align(Alignment.BottomEnd).padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                ZoomButton("+") { mapScale = (mapScale * 1.15f).coerceAtMost(1.8f) }
                ZoomButton("−") { mapScale = (mapScale / 1.15f).coerceAtLeast(0.65f) }
            }
        }

        InspectorSheet(
            task = task,
            node = focusedNode,
            readOnly = readOnly,
            onOpenUrl = onOpenUrl,
            onResolveBarrier = onResolveBarrier,
            onOpenHitl = onOpenHitl,
            modifier = Modifier.weight(0.44f),
        )
    }
}

@Composable
private fun InspectorSheet(
    task: TaskGraph,
    node: WorkNode?,
    readOnly: Boolean,
    onOpenUrl: (String) -> Unit,
    onResolveBarrier: (taskId: String, barrierId: String) -> Unit,
    onOpenHitl: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth().border(1.dp, MapBorder, RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)),
        color = MapCharcoal,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
    ) {
        Column(Modifier.fillMaxSize()) {
            if (node == null) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("NODE INSPECTOR", color = MapIndigo, fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    Text("Waiting for task details", color = MapPrimary, fontWeight = FontWeight.SemiBold)
                    Text("Select a node to inspect its work, inherited context, decisions, and live sources.", color = MapMuted, fontSize = 11.sp)
                    if (task.status == NodeStatus.FAILED) Text("The task stopped before a graph node was returned.", color = MapError, fontSize = 10.sp)
                }
                return@Surface
            }

            Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("NODE INSPECTOR", color = MapIndigo, fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    StatusPill(node.status.name.replace('_', ' '), MapStatusColor(node.status))
                }
                Text(node.title, color = MapPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    node.plain_summary.ifBlank { "No summary was returned for this node." },
                    color = MapMuted,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (node.highlight_chips.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        node.highlight_chips.take(3).forEach { MetricChip(it) }
                    }
                }
            }

            HorizontalDivider(color = MapBorder)

            Column(
                Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 9.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (node.work_tasks.isNotEmpty()) {
                    InspectorSectionTitle("WORK TASKS")
                    node.work_tasks.forEach { workTask -> WorkTaskRow(workTask) }
                }

                InspectorSectionTitle("INHERITED CONTEXT")
                if (node.inherited_context.isEmpty()) {
                    Text("No inherited context was attached to this node.", color = MapMuted, fontSize = 10.sp)
                } else {
                    node.inherited_context.forEach { (key, value) ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(key, color = MapMuted, fontSize = 10.sp, modifier = Modifier.width(90.dp))
                            Text(value, color = MapPrimary, fontSize = 10.sp, fontFamily = if (value.startsWith("vault://")) FontFamily.Monospace else FontFamily.Default)
                        }
                    }
                }

                InspectorSectionTitle("DECISION RATIONALE")
                Text(
                    node.decision_rationale.ifBlank { "No decision rationale was returned for this node." },
                    color = MapMuted,
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
                )

                InspectorSectionTitle("VERIFIED EXA SOURCES")
                if (node.sources.isEmpty()) {
                    Text(
                        if (node.status == NodeStatus.RUNNING) "Waiting for source results from this branch." else "No source citations were returned for this node.",
                        color = MapMuted,
                        fontSize = 10.sp,
                    )
                } else {
                    node.sources.forEach { source -> SourceCitationCard(source, onOpenUrl) }
                }

                val barrier = task.barriers.firstOrNull { it.source_node_id == node.id }
                val hasHitlWork = node.work_tasks.any { it.task_type == WorkTaskType.HITL_BROWSER }
                val isBlocked = node.status == NodeStatus.BLOCKED_USER || node.work_tasks.any { it.status == NodeStatus.BLOCKED_USER }
                if (isBlocked || hasHitlWork) {
                    InspectorSectionTitle("NEXT STEP")
                    if (readOnly) {
                        Text("This completed graph is in read-only audit mode.", color = MapMuted, fontSize = 10.sp)
                    } else if (barrier != null) {
                        Button(
                            onClick = { onResolveBarrier(task.id, barrier.barrier_id) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MapAmber, contentColor = MapObsidian),
                            shape = RoundedCornerShape(13.dp),
                        ) { Text("Resolve input · ${barrier.question}", maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 11.sp) }
                    } else if (hasHitlWork) {
                        Button(
                            onClick = onOpenHitl,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MapAmber, contentColor = MapObsidian),
                            shape = RoundedCornerShape(13.dp),
                        ) { Text("Open paused browser tab", fontSize = 11.sp) }
                    } else {
                        Text("Waiting for the task to publish the matching input barrier.", color = MapMuted, fontSize = 10.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkTaskRow(workTask: WorkTask) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(MapObsidian).padding(horizontal = 9.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(MapStatusColor(workTask.status)))
        Text(workTask.label, color = MapPrimary, fontSize = 10.sp, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(workTask.task_type.name.replace('_', ' '), color = MapMuted, fontSize = 8.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
        Text(workTask.status.name.replace('_', ' '), color = MapStatusColor(workTask.status), fontSize = 8.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
    }
}

@Composable
private fun SourceCitationCard(source: ExaSource, onOpenUrl: (String) -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable { onOpenUrl(source.url) },
        color = MapObsidian,
        shape = RoundedCornerShape(13.dp),
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Box(
                    Modifier.size(25.dp).clip(CircleShape).background(MapIndigo.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(source.domain.take(1).uppercase(), color = MapIndigo, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(source.domain, color = MapPrimary, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(source.title, color = MapMuted, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                StatusPill(if (source.selected) "LEADING" else "CONSIDERED", if (source.selected) MapEmerald else MapMuted)
            }
            if (!source.verification_badge.isNullOrBlank()) {
                Text(source.verification_badge, color = MapEmerald, fontSize = 8.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (source.highlighted_quote.isNotBlank()) {
                Text("“${source.highlighted_quote}”", color = MapPrimary, fontSize = 10.sp, lineHeight = 14.sp)
            }
            if (!source.selected && !source.rejection_reason.isNullOrBlank()) {
                Text("Considered · ${source.rejection_reason}", color = MapMuted, fontSize = 9.sp)
            }
            Text(source.url, color = MapIndigo, fontSize = 8.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun InspectorSectionTitle(label: String) {
    Text(label, color = MapIndigo, fontSize = 9.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
}

@Composable
private fun MetricChip(label: String) {
    Surface(color = MapObsidian, shape = RoundedCornerShape(18.dp)) {
        Text(label, color = MapPrimary, fontSize = 9.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp), maxLines = 1)
    }
}

@Composable
private fun StateNotice(title: String, detail: String, tint: Color) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(tint.copy(alpha = 0.1f)).padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(tint))
        Text(title, color = tint, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
        Text(detail, color = MapMuted, fontSize = 8.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun StatusPill(label: String, tint: Color) {
    Surface(color = tint.copy(alpha = 0.13f), shape = RoundedCornerShape(18.dp)) {
        Text(label, color = tint, fontSize = 8.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp), maxLines = 1)
    }
}

@Composable
private fun ZoomButton(label: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.size(38.dp).clickable(onClick = onClick),
        color = MapCharcoal,
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MapBorder),
    ) {
        Box(contentAlignment = Alignment.Center) { Text(label, color = MapPrimary, fontSize = 18.sp, fontWeight = FontWeight.Medium) }
    }
}

@Composable
private fun EmptyMapCard(title: String, detail: String, modifier: Modifier = Modifier) {
    Surface(modifier = modifier.fillMaxWidth(), color = MapCharcoal, shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, color = MapPrimary, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
            Text(detail, color = MapMuted, fontSize = 10.sp)
        }
    }
}

private fun nodePositions(
    nodes: List<WorkNode>,
    focusedId: String?,
    widthPx: Float,
    heightPx: Float,
    density: androidx.compose.ui.unit.Density,
): Map<String, Offset> {
    if (nodes.isEmpty()) return emptyMap()
    val focused = nodes.firstOrNull { it.id == focusedId } ?: nodes.first()
    val peers = nodes.filterNot { it.id == focused.id }
    val focusPoint = Offset(widthPx * 0.5f, heightPx * 0.24f)
    val result = mutableMapOf(focused.id to focusPoint)
    if (peers.isEmpty()) return result

    val rowSpacing = with(density) { 57.dp.toPx() }
    val columns = min(3, peers.size)
    val rows = ceil(peers.size.toFloat() / columns).toInt()
    val firstRowY = max(heightPx * 0.66f, heightPx - (rows - 1) * rowSpacing - with(density) { 42.dp.toPx() })

    peers.forEachIndexed { index, node ->
        val row = index / columns
        val column = index % columns
        val columnsInRow = min(columns, peers.size - row * columns)
        val x = widthPx * (column + 0.5f) / columnsInRow
        val y = (firstRowY + row * rowSpacing).coerceAtMost(heightPx - with(density) { 29.dp.toPx() })
        result[node.id] = Offset(x, y)
    }
    return result
}

private fun satellitePositions(count: Int, widthPx: Float, heightPx: Float, density: androidx.compose.ui.unit.Density): List<Offset> {
    if (count <= 0) return emptyList()
    val columns = min(3, count)
    val rowSpacing = with(density) { 42.dp.toPx() }
    val startY = heightPx * 0.39f
    return List(count) { index ->
        val row = index / columns
        val column = index % columns
        val columnsInRow = min(columns, count - row * columns)
        val x = widthPx * (column + 0.5f) / columnsInRow
        val y = (startY + row * rowSpacing).coerceAtMost(heightPx * 0.62f)
        Offset(x, y)
    }
}

private fun siblingCardWidth(nodeCount: Int): Dp = when {
    nodeCount > 6 -> 92.dp
    nodeCount > 3 -> 102.dp
    else -> 108.dp
}

private fun isActionableNode(node: WorkNode): Boolean =
    node.status == NodeStatus.BLOCKED_USER || node.work_tasks.any { it.status == NodeStatus.BLOCKED_USER || it.task_type == WorkTaskType.HITL_BROWSER }

private fun MapStatusColor(status: NodeStatus): Color = when (status) {
    NodeStatus.COMPLETED -> MapEmerald
    NodeStatus.BLOCKED_USER -> MapAmber
    NodeStatus.FAILED -> MapError
    NodeStatus.RUNNING, NodeStatus.WAITING, NodeStatus.STALE -> MapIndigo
}

private val MapCanvas = Color(0xFF10131B)
