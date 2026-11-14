package com.onecaramel.laizysink

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets

import android.os.Build

class AndroidPlatform : Platform {
    override val name: String = "Android ${Build.VERSION.SDK_INT}"
    override val backendWebSocketUrl: String =
        System.getProperty("laizysink.backendWebSocketUrl")?.takeIf { it.isNotBlank() }?.trim()
            ?: System.getenv("LAIZYSINK_BACKEND_WS_URL")?.takeIf { it.isNotBlank() }?.trim()
            ?: "ws://10.0.2.2:8000/ws/tasks/new"
}

actual fun getPlatform(): Platform = AndroidPlatform()



actual fun createPlatformHttpClient(): HttpClient = HttpClient(OkHttp) {
    install(WebSockets)
}

