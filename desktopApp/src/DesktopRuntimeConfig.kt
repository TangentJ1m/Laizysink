package com.onecaramel.laizysink

import java.net.URI

/** Desktop-only connection settings. Provider credentials always remain on the backend. */
internal object DesktopRuntimeConfig {
    private const val DEFAULT_BACKEND_WEBSOCKET_URL = "ws://localhost:8000/ws/tasks/new"
    private const val WEBSOCKET_URL_PROPERTY = "laizysink.backendWebSocketUrl"
    private const val WEBSOCKET_URL_ENV = "LAIZYSINK_BACKEND_WS_URL"

    fun backendWebSocketUrl(): String {
        val configured = System.getProperty(WEBSOCKET_URL_PROPERTY)
            ?.takeIf(String::isNotBlank)
            ?: System.getenv(WEBSOCKET_URL_ENV)?.takeIf(String::isNotBlank)
            ?: DEFAULT_BACKEND_WEBSOCKET_URL
        val uri = runCatching { URI(configured.trim()) }.getOrElse {
            throw IllegalArgumentException("Invalid backend WebSocket URL in $WEBSOCKET_URL_PROPERTY or $WEBSOCKET_URL_ENV", it)
        }
        require(uri.scheme in setOf("ws", "wss") && !uri.host.isNullOrBlank() && uri.rawUserInfo == null) {
            "Backend WebSocket URL must use ws:// or wss:// and must not contain credentials"
        }
        return uri.toASCIIString()
    }
}
