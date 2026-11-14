package com.onecaramel.laizysink

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO

class JVMPlatform: Platform {
    override val name: String = "Java ${System.getProperty("java.version")}"
}

actual fun getPlatform(): Platform = JVMPlatform()



actual fun createPlatformHttpClient(): HttpClient = HttpClient(CIO)

