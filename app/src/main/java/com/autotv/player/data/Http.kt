package com.autotv.player.data

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object Http {
    const val UA_IPTV = "VLC/3.0.20 LibVLC/3.0.20"
    const val UA_WEB = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0"

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    /** Bloqueante: chame fora da thread principal. */
    fun getText(url: String): String {
        val req = Request.Builder().url(url).header("User-Agent", UA_IPTV).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code} ao baixar a lista")
            return resp.body?.string().orEmpty()
        }
    }
}
