package com.autotv.player.data

import org.json.JSONArray
import java.net.URLEncoder

/**
 * Rádios online pelo catálogo aberto Radio Browser (radio-browser.info), mantido pela comunidade e
 * gratuito, sem cadastro. As funções são BLOQUEANTES (rede): chame em Dispatchers.IO.
 */
object Radio {
    /** Gêneros oferecidos como filtro: (etiqueta no catálogo, nome mostrado). Vazio = as mais ouvidas. */
    val GENRES = listOf(
        "" to "Mais ouvidas",
        "news" to "Notícias",
        "sports" to "Esportes",
        "sertanejo" to "Sertanejo",
        "pop" to "Pop",
        "rock" to "Rock",
        "mpb" to "MPB",
        "gospel" to "Gospel",
        "forró" to "Forró",
        "pagode" to "Pagode",
        "jazz" to "Jazz",
        "classical" to "Clássica"
    )

    // O catálogo tem vários servidores iguais; se um estiver fora, tenta o seguinte.
    private val SERVERS = listOf("de1", "de2", "nl1", "at1", "all").map { "https://$it.api.radio-browser.info" }
    private const val USER_AGENT = "AutoTV/1.0 (github.com/snakemoral/AutoTV)"
    /** A lista guardada no aparelho é mostrada na hora; passado esse tempo, é renovada em segundo plano. */
    private const val REFRESH_AFTER_MS = 6 * 60 * 60 * 1000L

    private val cache = HashMap<String, List<Item>>()
    private val refreshing = HashSet<String>()
    private var dir: java.io.File? = null
    /** O servidor que respondeu por último é tentado primeiro. */
    @Volatile private var preferred = 0

    fun init(context: android.content.Context) {
        dir = java.io.File(context.applicationContext.cacheDir, "radio").apply { mkdirs() }
    }

    /** Em segundo plano, deixa a lista das mais ouvidas pronta para quando a aba Rádio for aberta. */
    fun warmUp() {
        Thread { runCatching { stations("") } }.apply { isDaemon = true }.start()
    }

    private fun get(path: String): String {
        var last: Exception? = null
        for (n in SERVERS.indices) {
            val i = (preferred + n) % SERVERS.size
            try {
                val text = Http.getWeb(SERVERS[i] + path, mapOf("User-Agent" to USER_AGENT), Http.quick)
                preferred = i
                return text
            } catch (e: Exception) {
                last = e
            }
        }
        throw IllegalStateException("O catálogo de rádios não respondeu (${last?.message ?: "sem resposta"})")
    }

    private fun fileOf(tag: String): java.io.File? =
        dir?.let { java.io.File(it, "stations_" + tag.ifBlank { "top" }.replace(Regex("[^\\p{L}\\p{N}]"), "_") + ".json") }

    private fun fetch(tag: String): List<Item> {
        val path = "/json/stations/search?countrycode=BR&hidebroken=true&order=clickcount&reverse=true&limit=150" +
            if (tag.isBlank()) "" else "&tag=${enc(tag)}"
        val text = get(path)
        val items = parse(text)
        if (items.isEmpty()) error("Nenhuma rádio encontrada nesse gênero")
        synchronized(cache) { cache[tag] = items }
        runCatching { fileOf(tag)?.writeText(text) }
        return items
    }

    private fun refreshInBackground(tag: String) {
        synchronized(refreshing) { if (!refreshing.add(tag)) return }
        Thread {
            runCatching { fetch(tag) }
            synchronized(refreshing) { refreshing.remove(tag) }
        }.apply { isDaemon = true }.start()
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun parse(text: String): List<Item> {
        val arr = JSONArray(text)
        val out = ArrayList<Item>()
        val seen = HashSet<String>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val url = o.optString("url_resolved").ifBlank { o.optString("url") }
            val name = o.optString("name").trim()
            if (url.isBlank() || name.isBlank() || !seen.add(url)) continue
            val place = listOf(o.optString("state").trim(), o.optString("countrycode").trim())
                .filter { it.isNotBlank() }.joinToString(", ")
            out += streamItem(name, url, if (place.isBlank()) "Rádio" else "Rádio • $place", o.optString("favicon"))
        }
        return out
    }

    /**
     * Rádios do Brasil mais ouvidas, de um gênero ([tag]) ou de todos (vazio).
     * Responde na hora com a lista guardada (na memória ou no aparelho) e a renova em segundo plano quando
     * está velha; só espera a internet na primeira vez de cada gênero ou com [force].
     */
    fun stations(tag: String = "", force: Boolean = false): List<Item> {
        if (!force) {
            synchronized(cache) { cache[tag] }?.let { return it }
            val file = fileOf(tag)
            if (file != null && file.exists()) {
                val items = runCatching { parse(file.readText()) }.getOrDefault(emptyList())
                if (items.isNotEmpty()) {
                    synchronized(cache) { cache[tag] = items }
                    if (System.currentTimeMillis() - file.lastModified() > REFRESH_AFTER_MS) refreshInBackground(tag)
                    return items
                }
            }
        }
        return fetch(tag)
    }

    /** Busca rádios pelo nome, no mundo todo (as do Brasil primeiro). */
    fun search(query: String): List<Item> {
        val text = get("/json/stations/search?hidebroken=true&order=clickcount&reverse=true&limit=100&name=${enc(query.trim())}")
        val items = parse(text)
        if (items.isEmpty()) error("Nenhuma rádio encontrada com \"${query.trim()}\"")
        return items.sortedByDescending { it.group?.endsWith("BR") == true }
    }
}
