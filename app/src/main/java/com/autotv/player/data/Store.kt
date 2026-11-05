package com.autotv.player.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Persistência simples em JSON (playlists e favoritos), compartilhada pelo app e pelo carro. */
object Store {
    private lateinit var dir: File
    private val lock = Any()

    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val playlists: StateFlow<List<Playlist>> = _playlists

    private val _favorites = MutableStateFlow<List<Item>>(emptyList())
    val favorites: StateFlow<List<Item>> = _favorites

    fun init(context: Context) {
        dir = context.filesDir
        synchronized(lock) {
            _playlists.value = runCatching { readPlaylists() }.getOrDefault(emptyList())
            _favorites.value = runCatching { readFavorites() }.getOrDefault(emptyList())
        }
    }

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

    fun toggleFavorite(item: Item) = synchronized(lock) {
        val cur = _favorites.value
        val list = if (cur.any { it.id == item.id }) cur.filterNot { it.id == item.id } else cur + item
        _favorites.value = list
        writeFavorites(list)
    }

    // ---- JSON ----
    private fun itemToJson(i: Item) = JSONObject()
        .put("id", i.id).put("t", i.title).put("u", i.url)
        .put("g", i.group ?: JSONObject.NULL).put("k", i.kind.name)

    private fun itemFromJson(o: JSONObject) = Item(
        id = o.getString("id"),
        title = o.getString("t"),
        url = o.getString("u"),
        group = if (o.isNull("g")) null else o.getString("g"),
        kind = runCatching { Kind.valueOf(o.getString("k")) }.getOrDefault(Kind.STREAM)
    )

    private fun writeFavorites(list: List<Item>) {
        val arr = JSONArray(); list.forEach { arr.put(itemToJson(it)) }
        atomicWrite(File(dir, "favorites.json"), arr.toString())
    }

    private fun readFavorites(): List<Item> {
        val f = File(dir, "favorites.json"); if (!f.exists()) return emptyList()
        val arr = JSONArray(f.readText())
        return List(arr.length()) { itemFromJson(arr.getJSONObject(it)) }
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
