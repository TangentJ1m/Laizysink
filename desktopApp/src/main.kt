package com.onecaramel.laizysink

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.unit.dp

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Laizysink",
        state = rememberWindowState(width = 1120.dp, height = 820.dp),
    ) {
        App(backendWebSocketUrl = DesktopRuntimeConfig.backendWebSocketUrl())
    }
}
