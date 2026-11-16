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
    private const val CACHE_MS = 10 * 60 * 1000L

    private val cache = HashMap<String, Pair<Long, List<Item>>>()

    private fun get(path: String): String {
        var last: Exception? = null
        for (server in SERVERS) {
            try {
                return Http.getWeb(server + path, mapOf("User-Agent" to USER_AGENT))
            } catch (e: Exception) {
                last = e
            }
        }
        throw IllegalStateException("O catálogo de rádios não respondeu (${last?.message ?: "sem resposta"})")
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

    /** Rádios do Brasil mais ouvidas, de um gênero ([tag]) ou de todos (vazio). */
    fun stations(tag: String = "", force: Boolean = false): List<Item> {
        if (!force) synchronized(cache) {
            cache[tag]?.let { (at, items) -> if (System.currentTimeMillis() - at < CACHE_MS) return items }
        }
        val path = "/json/stations/search?countrycode=BR&hidebroken=true&order=clickcount&reverse=true&limit=150" +
            if (tag.isBlank()) "" else "&tag=${enc(tag)}"
        val items = parse(get(path))
        if (items.isEmpty()) error("Nenhuma rádio encontrada nesse gênero")
        synchronized(cache) { cache[tag] = System.currentTimeMillis() to items }
        return items
    }

    /** Busca rádios pelo nome, no mundo todo (as do Brasil primeiro). */
    fun search(query: String): List<Item> {
        val text = get("/json/stations/search?hidebroken=true&order=clickcount&reverse=true&limit=100&name=${enc(query.trim())}")
        val items = parse(text)
        if (items.isEmpty()) error("Nenhuma rádio encontrada com \"${query.trim()}\"")
        return items.sortedByDescending { it.group?.endsWith("BR") == true }
    }
}
