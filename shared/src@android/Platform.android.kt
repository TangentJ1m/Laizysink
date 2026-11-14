package com.onecaramel.laizysink

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp

import android.os.Build

class AndroidPlatform : Platform {
    override val name: String = "Android ${Build.VERSION.SDK_INT}"
}

actual fun getPlatform(): Platform = AndroidPlatform()



actual fun createPlatformHttpClient(): HttpClient = HttpClient(OkHttp)

