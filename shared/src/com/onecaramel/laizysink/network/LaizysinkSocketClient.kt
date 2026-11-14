package com.onecaramel.laizysink.network

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.send
import io.ktor.websocket.readText
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import com.onecaramel.laizysink.state.Event

/** Transport for the shared event contract. The platform supplies an HttpClient with its native engine. */
class LaizysinkSocketClient(
    private val httpClient: HttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    fun events(url: String, outgoing: Flow<String>): Flow<Event> = flow {
        httpClient.webSocket(urlString = url) {
            val session = this
            kotlinx.coroutines.coroutineScope {
                val sender = launch {
                    outgoing.collect { message -> session.send(Frame.Text(message)) }
                }
                try {
                    for (frame in session.incoming) {
                        if (frame is Frame.Text) emit(json.decodeFromString<Event>(frame.readText()))
                    }
                } finally {
                    sender.cancel()
                    session.close()
                }
            }
        }
    }
}



