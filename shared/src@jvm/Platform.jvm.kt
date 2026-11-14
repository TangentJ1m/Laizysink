package com.onecaramel.laizysink

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO

class JVMPlatform: Platform {
    override val name: String = "Java ${System.getProperty("java.version")}"
    override val backendWebSocketUrl: String = System.getenv("LAIZYSINK_BACKEND_WS_URL")
        ?.takeIf { it.isNotBlank() }
        ?: "ws://localhost:8000/ws/tasks/new"
}

actual fun getPlatform(): Platform = JVMPlatform()



actual fun createPlatformHttpClient(): HttpClient = HttpClient(CIO)

