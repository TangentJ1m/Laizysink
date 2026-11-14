package com.onecaramel.laizysink

import io.ktor.client.HttpClient

interface Platform {
    val name: String
    /** Creation WebSocket URL, ending in `/ws/tasks/new`; the transport substitutes task IDs on attach. */
    val backendWebSocketUrl: String
}

expect fun getPlatform(): Platform



expect fun createPlatformHttpClient(): HttpClient
