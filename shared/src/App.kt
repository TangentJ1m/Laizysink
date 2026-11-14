package com.onecaramel.laizysink

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.onecaramel.laizysink.network.LaizysinkSocketClient
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.encodeToString
import kotlinx.coroutines.flow.collect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Obsidian = Color(0xFF0D0F14)
private val Charcoal = Color(0xFF161922)
private val Border = Color(0xFF252A37)
private val Indigo = Color(0xFF6366F1)
private val Emerald = Color(0xFF10B981)
private val Amber = Color(0xFFF59E0B)
private val PrimaryText = Color(0xFFF3F4F6)
private val Muted = Color(0xFF9CA3AF)

private enum class AppScreen(val title: String) {
    HOME("Laizysink"), TASKS("All Tasks"), ACTIVE("Active Task"), MAP("Execution Map"), KEYCHAIN("Keychain & Spotlight"), DONE("Completed Task")
}

@Composable
fun App() {
    var screen by remember { mutableStateOf(AppScreen.HOME) }
    var prompt by remember { mutableStateOf("") }
    var backendStatus by remember { mutableStateOf("Ready to research live sources") }
    val scope = rememberCoroutineScope()
    val socketClient = remember { LaizysinkSocketClient(createPlatformHttpClient()) }

    fun createTask() {
        val query = prompt.trim()
        if (query.isEmpty()) return
        screen = AppScreen.ACTIVE
        backendStatus = "Connecting to Laizysink?"
        val host = if (getPlatform().name.startsWith("Android")) "10.0.2.2" else "localhost"
        val message = JsonObject(mapOf("type" to JsonPrimitive("CREATE_TASK"), "prompt" to JsonPrimitive(query)))
        val outgoing = flowOf(Json.encodeToString(JsonObject.serializer(), message))
        scope.launch {
            try {
                socketClient.events("ws://$host:8000/ws/tasks/new", outgoing).collect { event ->
                    backendStatus = when (event.type) {
                        com.onecaramel.laizysink.state.EventType.TASK_CREATED -> "Task started ? planning research branches"
                        com.onecaramel.laizysink.state.EventType.NODE_UPDATED -> "Researching live sources ? ${event.task_id}"
                        com.onecaramel.laizysink.state.EventType.DATA_BARRIER -> "Quick input needed to continue"
                        com.onecaramel.laizysink.state.EventType.HITL_BROWSER -> "A browser tab needs your approval"
                        com.onecaramel.laizysink.state.EventType.TASK_COMPLETED -> "Research complete ? sources are ready"
                        com.onecaramel.laizysink.state.EventType.ERROR -> event.payload.toString()
                    }
                }
            } catch (error: Exception) {
                backendStatus = "Backend unavailable ? ${error.message ?: "check that FastAPI is running"}"
            }
        }
    }

    MaterialTheme(colorScheme = darkColorScheme(background = Obsidian, surface = Charcoal, primary = Indigo, onBackground = PrimaryText, onSurface = PrimaryText)) {
        Column(Modifier.fillMaxSize().background(Obsidian).padding(20.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(screen.title, fontSize = 23.sp, fontWeight = FontWeight.Bold, color = PrimaryText)
                Text("Tabs Hidden ? 2 Active", color = Emerald, fontSize = 12.sp)
            }
            Spacer(Modifier.height(20.dp))
            when (screen) {
                AppScreen.HOME -> Home(prompt, { prompt = it }, ::createTask, { screen = AppScreen.TASKS })
                AppScreen.TASKS -> Tasks({ screen = AppScreen.ACTIVE }, { screen = AppScreen.DONE })
                AppScreen.ACTIVE -> Active(backendStatus, { screen = AppScreen.MAP }, { screen = AppScreen.KEYCHAIN })
                AppScreen.MAP -> ExecutionMap({ screen = AppScreen.ACTIVE })
                AppScreen.KEYCHAIN -> Keychain({ screen = AppScreen.ACTIVE })
                AppScreen.DONE -> Completed({ screen = AppScreen.MAP })
            }
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                AppScreen.entries.forEach { destination ->
                    Text(destination.title.substringBefore(' '), color = if (destination == screen) Indigo else Muted, fontSize = 11.sp, modifier = Modifier.clickable { screen = destination }.padding(5.dp))
                }
            }
        }
    }
}

@Composable
private fun Home(prompt: String, onPrompt: (String) -> Unit, onSubmit: () -> Unit, onTasks: () -> Unit) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("The Internet Butler", fontSize = 28.sp, fontWeight = FontWeight.SemiBold, color = PrimaryText)
        Text("Tell me what you need. I’ll research the web and keep the tabs out of your way.", color = Muted)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("Cheap flights", "Summarize articles", "Track a package").forEach { Chip(it) } }
        SectionTitle("Working in the background")
        Card("Tokyo trip planning", "Comparing direct flights and stays • 4 sources", Amber) { onSubmit() }
        Card("Helsinki rent check", "Official sources collected • Ready to review", Emerald) { onTasks() }
        Text("Review all tasks  →", color = Indigo, modifier = Modifier.clickable(onClick = onTasks).padding(4.dp))
        TextField(value = prompt, onValueChange = onPrompt, modifier = Modifier.fillMaxWidth(), placeholder = { Text("Ask Laizysink anything…", color = Muted) }, colors = TextFieldDefaults.colors(focusedContainerColor = Charcoal, unfocusedContainerColor = Charcoal, focusedTextColor = PrimaryText, unfocusedTextColor = PrimaryText))
        Button(onClick = onSubmit, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Indigo)) { Text("Research this") }
    }
}

@Composable
private fun Tasks(onActive: () -> Unit, onDone: () -> Unit) {
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SectionTitle("Needs attention"); Card("Confirm travel dates", "Japan Trip • Unlocks flight and hotel comparisons", Amber, onActive)
        SectionTitle("Working in background"); Card("Helsinki Rent Increase Check", "Checking current legislation and guidance", Indigo, onActive)
        SectionTitle("Finished"); Card("Compare Tokyo neighborhoods", "Verified sources • completed", Emerald, onDone)
        SectionTitle("Active context window"); Card("Japan Trip • Nov 18–23", "4 shared facts • USED", Indigo, onActive)
    }
}

@Composable
private fun Active(status: String, onMap: () -> Unit, onHITL: () -> Unit) {
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Plan   →   Research   →   Needs input   →   Compare", color = Muted, fontSize = 12.sp)
        Card("Quick input needed", "Confirm your dates and budget to finish comparing options.", Amber)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Chip("Nov 18–23 • 2 guests"); Chip("Flexible dates") }
        Card("Live Exa research", "ana.co.jp  •  Direct London–Tokyo fare details", Emerald)
        Card("Sign in to continue", "A booking site needs your approval", Amber, onHITL)
        Button(onClick = onMap, colors = ButtonDefaults.buttonColors(containerColor = Indigo)) { Text("View execution map") }
    }
}

@Composable
private fun ExecutionMap(onResolve: () -> Unit) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Canvas(Modifier.fillMaxWidth().weight(1f).background(Charcoal, RoundedCornerShape(20.dp)).clickable(onClick = onResolve)) {
            val points = listOf(Offset(size.width * .2f, size.height * .24f), Offset(size.width * .5f, size.height * .4f), Offset(size.width * .78f, size.height * .25f), Offset(size.width * .48f, size.height * .72f))
            points.zipWithNext().forEach { (a, b) -> drawLine(Border, a, b, strokeWidth = 3f) }
            points.forEachIndexed { index, point -> drawCircle(if (index == 1) Amber else Indigo, radius = if (index == 1) 22f else 15f, center = point) }
        }
        Card("Find flights • Needs input", "Picked direct routes for Nov 18–23. ANA and JAL are leading sources.", Amber)
        Button(onClick = onResolve, colors = ButtonDefaults.buttonColors(containerColor = Indigo)) { Text("Resolve input") }
    }
}

@Composable
private fun Keychain(onDone: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SectionTitle("Human approval required")
        Card("jal.co.jp", "vault://jal.co.jp • username ••••@mail.com", Indigo)
        Text("Your credentials stay on this device. Approve sign-in to resume the task.", color = Muted)
        Button(onClick = onDone, colors = ButtonDefaults.buttonColors(containerColor = Indigo)) { Text("Autofill & Sign In with Biometrics") }
        Text("Continue manually", color = Muted, modifier = Modifier.clickable(onClick = onDone).padding(8.dp))
    }
}

@Composable
private fun Completed(onMap: () -> Unit) {
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Your research is ready", fontSize = 25.sp, fontWeight = FontWeight.Bold, color = PrimaryText)
        Card("Tokyo trip overview", "Direct flights from £640 per person. Dates and hotel context reused from Japan Trip.", Emerald)
        SectionTitle("Verified sources")
        Card("ana.co.jp • Live verified", "“Direct London Heathrow to Tokyo Haneda return…”", Emerald)
        Card("jal.co.jp • Live verified", "Fare rules and booking details", Emerald)
        Button(onClick = onMap, colors = ButtonDefaults.buttonColors(containerColor = Indigo)) { Text("Inspect execution map") }
        Button(onClick = {}, colors = ButtonDefaults.buttonColors(containerColor = Charcoal)) { Text("Open reservation tab") }
    }
}

@Composable
private fun Card(title: String, detail: String, status: Color, onClick: () -> Unit = {}) {
    Surface(color = Charcoal, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, color = PrimaryText, fontWeight = FontWeight.SemiBold)
            Text(detail, color = Muted, fontSize = 13.sp)
            Text("●  Live", color = status, fontSize = 11.sp)
        }
    }
}

@Composable
private fun Chip(label: String) {
    Surface(color = Charcoal, shape = RoundedCornerShape(24.dp)) { Text(label, color = Muted, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) }
}

@Composable
private fun SectionTitle(text: String) { Text(text, color = PrimaryText, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp)) }
