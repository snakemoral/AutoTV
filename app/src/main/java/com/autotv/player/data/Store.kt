package com.autotv.player.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Persistência simples em JSON (playlists, favoritos, histórico, canais seguidos e posição dos vídeos). */
object Store {
    private const val MAX_HISTORY = 60
    private const val MAX_POSITIONS = 500
    private const val MAX_HIDDEN = 300
    /** Quantos itens da fila são guardados antes e depois do vídeo atual. */
    private const val SESSION_BEFORE = 20
    private const val SESSION_AFTER = 30

    private lateinit var dir: File
    private val lock = Any()

    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val playlists: StateFlow<List<Playlist>> = _playlists

    private val _favorites = MutableStateFlow<List<Item>>(emptyList())
    val favorites: StateFlow<List<Item>> = _favorites

    private val _history = MutableStateFlow<List<Item>>(emptyList())
    val history: StateFlow<List<Item>> = _history

    private val _following = MutableStateFlow<List<Channel>>(emptyList())
    val following: StateFlow<List<Channel>> = _following

    /** Onde cada vídeo parou (chave do vídeo -> milissegundos). O mais recente fica no fim. */
    private val positions = LinkedHashMap<String, Long>()
    /** Vídeos vistos neste app desde a última sincronização com a conta (a versão daqui é a mais nova). */
    private val recentLocal = HashSet<String>()
    /** Vídeos que o usuário limpou do histórico do app: não voltam na sincronização. */
    private val hidden = LinkedHashSet<String>()
    /** Gravações frequentes (posição, fila) saem da thread principal, uma de cada vez e em ordem. */
    private val io = java.util.concurrent.Executors.newSingleThreadExecutor()

    fun init(context: Context) {
        dir = context.filesDir
        synchronized(lock) {
            _playlists.value = runCatching { readPlaylists() }.getOrDefault(emptyList())
            _favorites.value = runCatching { readItems("favorites.json") }.getOrDefault(emptyList())
            _history.value = runCatching { readItems("history.json") }.getOrDefault(emptyList())
            _following.value = runCatching { readChannels() }.getOrDefault(emptyList())
            positions.clear()
            runCatching { readPositions() }
            hidden.clear()
            runCatching { readHidden() }
        }
    }

    // ---- playlists ----
    fun putPlaylist(p: Playlist) = synchronized(lock) {
        val list = _playlists.value.filterNot { it.id == p.id } + p
        _playlists.value = list
        writePlaylists(list)
    }

    fun removePlaylist(id: String) = synchronized(lock) {
        val list = _playlists.value.filterNot { it.id == id }
        _playlists.value = list
        writePlaylists(list)
    }

    // ---- favoritos ----
    fun toggleFavorite(item: Item) = synchronized(lock) {
        val cur = _favorites.value
        val list = if (cur.any { it.id == item.id }) cur.filterNot { it.id == item.id } else cur + item
        _favorites.value = list
        writeItems("favorites.json", list)
    }

    // ---- histórico (só YouTube) ----
    fun addHistory(item: Item) = synchronized(lock) {
        if (item.kind != Kind.YOUTUBE) return@synchronized
        val key = positionKey(item)
        val list = (listOf(item) + _history.value.filterNot { positionKey(it) == key }).take(MAX_HISTORY)
        _history.value = list
        writeItems("history.json", list)
        recentLocal += key
        if (hidden.remove(key)) writeHidden()
    }

    fun clearHistory() = synchronized(lock) {
        _history.value = emptyList()
        // Com a conta conectada, o que foi limpo aqui não volta na próxima sincronização
        // (o histórico da conta, no YouTube, não é apagado).
        _history.value.forEach { hidden += positionKey(it) }
        while (hidden.size > MAX_HIDDEN) hidden.remove(hidden.first())
        writeHidden()
        recentLocal.clear()
        _history.value = emptyList()
        writeItems("history.json", emptyList())
        positions.clear()
        writePositions()
    }

    /** Código do vídeo no YouTube (11 caracteres), ou null se o item não é do YouTube. */
    fun youtubeId(item: Item): String? =
        if (item.kind == Kind.YOUTUBE) YT_ID.find(item.url)?.groupValues?.get(1) else null

    /**
     * Junta o histórico da conta do YouTube ([remote], do mais recente para o mais antigo) com o do app.
     * Ordem final: o que foi visto aqui desde a última sincronização, depois o histórico da conta,
     * depois o resto do histórico do app. Também traz o ponto em que cada vídeo parou nos outros aparelhos.
     */
    fun mergeRemoteHistory(remote: List<Watched>) = synchronized(lock) {
        val local = _history.value
        val fresh = local.filter { positionKey(it) in recentLocal }
        val used = fresh.map { positionKey(it) }.toHashSet()
        val fromAccount = remote.filter { it.item.kind == Kind.YOUTUBE }.map { it.item }.filter {
            val key = positionKey(it)
            key !in hidden && used.add(key)
        }
        val rest = local.filter { used.add(positionKey(it)) }
        val list = (fresh + fromAccount + rest).take(MAX_HISTORY)
        if (list != local) {
            _history.value = list
            writeItems("history.json", list)
        }

        var changed = false
        for (w in remote) {
            val key = positionKey(w.item)
            // Visto aqui por último: a posição do app é a mais nova (e já foi avisada ao YouTube).
            if (key in recentLocal) continue
            val durMs = w.item.durationSec * 1000
            if (w.percent < 0 || durMs <= 0 || w.item.isLive) continue
            val remotePos = durMs * w.percent / 100
            if (w.percent >= 97 || remotePos > durMs - 10_000) {
                // Visto até o fim em outro aparelho: começa do início.
                if (positions.remove(key) != null) changed = true
                continue
            }
            if (remotePos < 5_000) continue
            // A conta só informa a porcentagem (número inteiro): diferenças pequenas não são novidade.
            val tolerance = maxOf(30_000L, durMs * 2 / 100)
            val mine = positions[key]
            if (mine == null || kotlin.math.abs(mine - remotePos) > tolerance) {
                positions.remove(key)
                positions[key] = remotePos
                changed = true
            }
        }
        while (positions.size > MAX_POSITIONS) positions.remove(positions.keys.first())
        if (changed) writePositions()
        // O que já apareceu no histórico da conta deixa de ser "novidade daqui".
        remote.forEach { recentLocal.remove(positionKey(it.item)) }
    }

    private fun writeHidden() {
        val arr = JSONArray(); hidden.forEach { arr.put(it) }
        val text = arr.toString()
        io.execute { runCatching { atomicWrite(File(dir, "hidden.json"), text) } }
    }

    private fun readHidden() {
        val f = File(dir, "hidden.json"); if (!f.exists()) return
        val arr = JSONArray(f.readText())
        for (i in 0 until arr.length()) hidden += arr.getString(i)
    }

    // ---- posição em que cada vídeo parou ----
    /**
     * Chave do vídeo para guardar a posição. No YouTube é o código do vídeo, para o mesmo vídeo ser
     * reconhecido em qualquer aba (Em alta, Seguindo, Histórico, busca, link colado).
     */
    fun positionKey(item: Item): String {
        if (item.kind == Kind.YOUTUBE) {
            YT_ID.find(item.url)?.let { return "y:" + it.groupValues[1] }
        }
        return item.id
    }

    private val YT_ID = Regex("(?:[?&]v=|youtu\\.be/|/shorts/|/live/|/embed/)([A-Za-z0-9_-]{11})")

    /** Posição guardada do vídeo, em milissegundos (0 = começar do início). */
    fun positionOf(item: Item): Long = synchronized(lock) { positions[positionKey(item)] ?: 0L }

    fun savePosition(item: Item, ms: Long) = synchronized(lock) {
        val key = positionKey(item)
        if (positions[key] == ms) return@synchronized
        positions.remove(key)
        positions[key] = ms
        while (positions.size > MAX_POSITIONS) positions.remove(positions.keys.first())
        writePositions()
    }

    fun clearPosition(item: Item) = synchronized(lock) {
        if (positions.remove(positionKey(item)) != null) writePositions()
    }

    private fun writePositions() {
        val arr = JSONArray()
        positions.forEach { (k, v) -> arr.put(JSONArray().put(k).put(v)) }
        val text = arr.toString()
        io.execute { runCatching { atomicWrite(File(dir, "positions.json"), text) } }
    }

    private fun readPositions() {
        val f = File(dir, "positions.json"); if (!f.exists()) return
        val arr = JSONArray(f.readText())
        for (i in 0 until arr.length()) {
            val pair = arr.getJSONArray(i)
            positions[pair.getString(0)] = pair.getLong(1)
        }
    }

    // ---- última fila tocada (para retomar depois de o app ser fechado pelo sistema) ----
    fun saveSession(queue: List<Item>, index: Int) {
        if (index !in queue.indices) return
        val from = (index - SESSION_BEFORE).coerceAtLeast(0)
        val to = (index + SESSION_AFTER + 1).coerceAtMost(queue.size)
        val arr = JSONArray(); queue.subList(from, to).forEach { arr.put(itemToJson(it)) }
        val text = JSONObject().put("i", index - from).put("items", arr).toString()
        io.execute { runCatching { atomicWrite(File(dir, "session.json"), text) } }
    }

    fun clearSession() {
        io.execute { runCatching { File(dir, "session.json").delete() } }
    }

    /** A fila guardada e o índice do vídeo que estava tocando, ou null se não há. */
    fun loadSession(): Pair<List<Item>, Int>? {
        val f = File(dir, "session.json")
        if (!f.exists()) return null
        return runCatching {
            val o = JSONObject(f.readText())
            val arr = o.getJSONArray("items")
            val items = List(arr.length()) { itemFromJson(arr.getJSONObject(it)) }
            val i = o.getInt("i")
            if (i in items.indices) items to i else null
        }.getOrNull()
    }

    // ---- canais seguidos ----
    fun follow(channel: Channel) = synchronized(lock) {
        if (_following.value.any { it.url == channel.url }) return@synchronized
        val list = _following.value + channel
        _following.value = list
        writeChannels(list)
    }

    fun followAll(channels: List<Channel>) = synchronized(lock) {
        val known = _following.value.map { it.url }.toSet()
        val list = _following.value + channels.filter { it.url !in known }.distinctBy { it.url }
        _following.value = list
        writeChannels(list)
    }

    fun unfollow(url: String) = synchronized(lock) {
        val list = _following.value.filterNot { it.url == url }
        _following.value = list
        writeChannels(list)
    }

    // ---- JSON ----
    private fun itemToJson(i: Item) = JSONObject()
        .put("id", i.id).put("t", i.title).put("u", i.url)
        .put("g", i.group ?: JSONObject.NULL).put("k", i.kind.name)
        .put("th", i.thumb ?: JSONObject.NULL)
        .put("lv", i.isLive).put("d", i.durationSec)
        .put("cu", i.channelUrl ?: JSONObject.NULL)

    private fun JSONObject.strOrNull(key: String): String? = if (isNull(key) || !has(key)) null else getString(key)

    private fun itemFromJson(o: JSONObject) = Item(
        id = o.getString("id"),
        title = o.getString("t"),
        url = o.getString("u"),
        group = o.strOrNull("g"),
        kind = runCatching { Kind.valueOf(o.getString("k")) }.getOrDefault(Kind.STREAM),
        thumb = o.strOrNull("th"),
        isLive = o.optBoolean("lv", false),
        durationSec = o.optLong("d", 0),
        channelUrl = o.strOrNull("cu")
    )

    private fun writeItems(name: String, list: List<Item>) {
        val arr = JSONArray(); list.forEach { arr.put(itemToJson(it)) }
        atomicWrite(File(dir, name), arr.toString())
    }

    private fun readItems(name: String): List<Item> {
        val f = File(dir, name); if (!f.exists()) return emptyList()
        val arr = JSONArray(f.readText())
        return List(arr.length()) { itemFromJson(arr.getJSONObject(it)) }
    }

    private fun writeChannels(list: List<Channel>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("u", it.url).put("n", it.name)) }
        atomicWrite(File(dir, "following.json"), arr.toString())
    }

    private fun readChannels(): List<Channel> {
        val f = File(dir, "following.json"); if (!f.exists()) return emptyList()
        val arr = JSONArray(f.readText())
        return List(arr.length()) { val o = arr.getJSONObject(it); Channel(o.getString("u"), o.getString("n")) }
    }

    private fun writePlaylists(list: List<Playlist>) {
        val arr = JSONArray()
        list.forEach { p ->
            val items = JSONArray(); p.items.forEach { items.put(itemToJson(it)) }
            arr.put(JSONObject().put("id", p.id).put("name", p.name).put("src", p.source).put("items", items))
        }
        atomicWrite(File(dir, "playlists.json"), arr.toString())
    }

    private fun readPlaylists(): List<Playlist> {
        val f = File(dir, "playlists.json"); if (!f.exists()) return emptyList()
        val arr = JSONArray(f.readText())
        return List(arr.length()) { idx ->
            val o = arr.getJSONObject(idx)
            val items = o.getJSONArray("items")
            Playlist(
                id = o.getString("id"),
                name = o.getString("name"),
                source = o.optString("src", ""),
                items = List(items.length()) { itemFromJson(items.getJSONObject(it)) }
            )
        }
    }

    private fun atomicWrite(file: File, text: String) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) { file.writeText(text); tmp.delete() }
    }
}
