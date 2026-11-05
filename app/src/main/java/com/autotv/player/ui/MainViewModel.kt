package com.autotv.player.ui

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.autotv.player.data.Http
import com.autotv.player.data.Item
import com.autotv.player.data.M3uParser
import com.autotv.player.data.Playlist
import com.autotv.player.data.Store
import com.autotv.player.data.YouTube
import com.autotv.player.data.streamItem
import com.autotv.player.player.PlayerHub
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class MainViewModel : ViewModel() {
    val playlists = Store.playlists
    val favorites = Store.favorites

    val ytResults = MutableStateFlow<List<Item>>(emptyList())
    val busy = MutableStateFlow(false)
    val message = MutableStateFlow<String?>(null)

    /** Executa [block] em IO mostrando "ocupado" e reportando erros. */
    private fun <T> task(block: () -> T, onDone: (T) -> Unit = {}) {
        viewModelScope.launch {
            busy.value = true
            try {
                val result = withContext(Dispatchers.IO) { block() }
                onDone(result)
            } catch (e: Exception) {
                message.value = e.message ?: e.javaClass.simpleName
            } finally {
                busy.value = false
            }
        }
    }

    fun clearMessage() { message.value = null }

    fun play(items: List<Item>, index: Int) = PlayerHub.play(items, index)
    fun toggleFavorite(item: Item) = Store.toggleFavorite(item)

    // ---- Listas ----
    private fun buildPlaylist(id: String, name: String, source: String, text: String): Playlist {
        val items = M3uParser.parse(text).map { streamItem(it.name, it.url, it.group) }
        require(items.isNotEmpty()) { "Nenhum canal encontrado nessa lista" }
        return Playlist(id, name, source, items)
    }

    fun addPlaylistFromUrl(name: String, url: String) {
        val cleanUrl = url.trim()
        val cleanName = name.trim()
        if (YouTube.looksLikeLink(cleanUrl)) {
            task({ YouTube.loadPlaylist(cleanUrl) }) { (title, items) ->
                Store.putPlaylist(Playlist(UUID.randomUUID().toString(), cleanName.ifBlank { title }, cleanUrl, items))
            }
        } else {
            task({
                val text = Http.getText(cleanUrl)
                buildPlaylist(UUID.randomUUID().toString(), cleanName.ifBlank { cleanUrl.substringAfter("://").substringBefore('/') }, cleanUrl, text)
            }) { Store.putPlaylist(it) }
        }
    }

    fun addPlaylistFromFile(resolver: ContentResolver, uri: Uri, name: String) {
        task({
            val text = resolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: error("Não foi possível abrir o arquivo")
            buildPlaylist(UUID.randomUUID().toString(), name.trim().ifBlank { "Minha lista" }, "", text)
        }) { Store.putPlaylist(it) }
    }

    fun refresh(p: Playlist) {
        if (p.source.isBlank()) { message.value = "Listas de arquivo não podem ser atualizadas"; return }
        task({
            if (YouTube.looksLikeLink(p.source)) {
                p.copy(items = YouTube.loadPlaylist(p.source).second)
            } else {
                buildPlaylist(p.id, p.name, p.source, Http.getText(p.source))
            }
        }) { Store.putPlaylist(it) }
    }

    fun remove(p: Playlist) = Store.removePlaylist(p.id)

    // ---- YouTube ----
    fun submitYoutube(input: String) {
        val text = input.trim()
        if (text.isEmpty()) return
        when {
            YouTube.looksLikeLink(text) && YouTube.isPlaylistLink(text) ->
                task({ YouTube.loadPlaylist(text) }) { (title, items) ->
                    Store.putPlaylist(Playlist(UUID.randomUUID().toString(), title, text, items))
                    message.value = "Playlist \"$title\" salva em Listas"
                }
            YouTube.looksLikeLink(text) ->
                task({ YouTube.itemFromLink(text) }) { PlayerHub.play(listOf(it), 0) }
            else ->
                task({ YouTube.search(text) }) { ytResults.value = it }
        }
    }

    /** Link compartilhado a partir do app do YouTube. */
    fun handleShared(text: String) {
        val link = Regex("""https?://\S+""").find(text)?.value ?: return
        if (YouTube.looksLikeLink(link)) submitYoutube(link)
    }
}
