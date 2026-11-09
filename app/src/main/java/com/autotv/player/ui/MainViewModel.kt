package com.autotv.player.ui

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.autotv.player.data.Channel
import com.autotv.player.data.Http
import com.autotv.player.data.Item
import com.autotv.player.data.M3uParser
import com.autotv.player.data.Playlist
import com.autotv.player.data.Store
import com.autotv.player.data.YouTube
import com.autotv.player.data.streamItem
import com.autotv.player.player.PlayerHub
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.UUID

enum class HomeSection(val label: String) {
    TRENDING("Em alta"), LIVE("Ao vivo"), HISTORY("Histórico")
}

class MainViewModel : ViewModel() {
    val playlists = Store.playlists
    val favorites = Store.favorites
    val history = Store.history
    val following = Store.following

    // ---- YouTube: busca e início ----
    val ytResults = MutableStateFlow<List<Item>>(emptyList())
    /** Termo da busca atual; nulo = mostrando a página inicial. */
    val resultsFor = MutableStateFlow<String?>(null)
    val homeSection = MutableStateFlow(HomeSection.TRENDING)
    val feed = MutableStateFlow<Pair<HomeSection, List<Item>>?>(null)
    val followingFeed = MutableStateFlow<List<Item>>(emptyList())

    val busy = MutableStateFlow(false)
    val message = MutableStateFlow<String?>(null)

    private var followingFeedKey: String? = null

    /** Executa [block] em IO mostrando "ocupado" e reportando erros. */
    private fun <T> task(block: suspend () -> T, onDone: (T) -> Unit = {}) {
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
    fun clearHistory() = Store.clearHistory()

    // ---- Listas de TV ----
    private fun buildPlaylist(id: String, name: String, source: String, text: String): Playlist {
        val items = M3uParser.parse(text).map { streamItem(it.name, it.url, it.group, it.logo) }
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
                    message.value = "Playlist \"$title\" salva em TV"
                }
            YouTube.looksLikeLink(text) ->
                task({ YouTube.itemFromLink(text) }) { PlayerHub.play(listOf(it), 0) }
            else ->
                task({ YouTube.search(text) }) { ytResults.value = it; resultsFor.value = text }
        }
    }

    fun clearResults() { resultsFor.value = null; ytResults.value = emptyList() }

    fun selectHome(section: HomeSection, force: Boolean = false) {
        homeSection.value = section
        if (section == HomeSection.HISTORY) return
        if (!force && feed.value?.first == section) return
        val kiosk = if (section == HomeSection.LIVE) "Live" else "Trending"
        task({ YouTube.kiosk(kiosk) }) { feed.value = section to it }
    }

    /** Link compartilhado a partir do app do YouTube. */
    fun handleShared(text: String) {
        val link = Regex("""https?://\S+""").find(text)?.value ?: return
        if (YouTube.looksLikeLink(link)) submitYoutube(link)
    }

    // ---- Canais seguidos (no lugar do login) ----
    fun isFollowing(url: String?, list: List<Channel>) = url != null && list.any { it.url == url }

    fun toggleFollow(item: Item) {
        val url = item.channelUrl ?: return
        if (following.value.any { it.url == url }) Store.unfollow(url)
        else Store.follow(Channel(url, item.group ?: "Canal"))
    }

    fun unfollow(url: String) = Store.unfollow(url)

    /** Importa o arquivo subscriptions.csv do Google Takeout. */
    fun importSubscriptions(resolver: ContentResolver, uri: Uri) {
        task({
            val text = resolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: error("Não foi possível abrir o arquivo")
            val channels = text.lineSequence().drop(1).mapNotNull { line ->
                val parts = line.split(',', limit = 3)
                if (parts.size < 3 || parts[0].isBlank()) null
                else Channel("https://www.youtube.com/channel/${parts[0].trim()}", parts[2].trim().trim('"').ifBlank { "Canal" })
            }.toList()
            require(channels.isNotEmpty()) { "Nenhuma inscrição encontrada. Use o arquivo subscriptions.csv do Google Takeout." }
            channels
        }) {
            Store.followAll(it)
            message.value = "${it.size} canais importados"
        }
    }

    /** Novidades dos canais seguidos (intercaladas para não ficar um canal só). */
    fun loadFollowingFeed(force: Boolean = false) {
        val channels = following.value.take(20)
        val key = channels.joinToString("|") { it.url }
        if (!force && key == followingFeedKey) return
        followingFeedKey = key
        if (channels.isEmpty()) { followingFeed.value = emptyList(); return }
        task({ YouTube.followingFeed(channels) }) { followingFeed.value = it }
    }
}
