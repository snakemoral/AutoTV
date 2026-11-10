package com.autotv.player.data

import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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

    /** GET como navegador. Bloqueante. */
    fun getWeb(url: String, headers: Map<String, String> = emptyMap()): String {
        val b = Request.Builder().url(url).header("User-Agent", UA_WEB)
        headers.forEach { (k, v) -> b.header(k, v) }
        client.newCall(b.build()).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            return resp.body?.string().orEmpty()
        }
    }

    /** POST de JSON; devolve (código HTTP, corpo). Bloqueante. */
    fun postJson(url: String, json: String, headers: Map<String, String> = emptyMap()): Pair<Int, String> {
        val body = json.toByteArray().toRequestBody("application/json".toMediaType())
        val b = Request.Builder().url(url).post(body)
        headers.forEach { (k, v) -> b.header(k, v) }
        client.newCall(b.build()).execute().use { resp ->
            return resp.code to resp.body?.string().orEmpty()
        }
    }
}
