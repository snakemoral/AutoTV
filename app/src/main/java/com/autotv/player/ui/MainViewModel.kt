package com.autotv.player.ui

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.autotv.player.data.Account
import com.autotv.player.data.Channel
import com.autotv.player.data.Http
import com.autotv.player.data.Item
import com.autotv.player.data.M3uParser
import com.autotv.player.data.Playlist
import com.autotv.player.data.Store
import com.autotv.player.data.YouTube
import com.autotv.player.data.streamItem
import com.autotv.player.player.PlayerHub
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
        // O YouTube acabou com a página "Em alta" antiga: aqui ela é a mistura das categorias em alta
        // (música, jogos, filmes, ao vivo), a mesma lista que o carro mostra em "YouTube".
        if (section == HomeSection.LIVE) task({ YouTube.kiosk("live") }) { feed.value = section to it }
        else task({
            YouTube.homeFeed().ifEmpty { error("O YouTube não devolveu vídeos em alta agora. Tente de novo.") }
        }) { feed.value = section to it }
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

    /** Importa as inscrições do Google Takeout: aceita o subscriptions.csv ou o .zip inteiro do Takeout. */
    fun importSubscriptions(resolver: ContentResolver, uri: Uri) {
        task({
            val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Não foi possível abrir o arquivo")
            val text = if (bytes.size > 3 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) {
                // É o .zip do Takeout: procura o CSV de inscrições lá dentro.
                var found: String? = null
                java.util.zip.ZipInputStream(bytes.inputStream()).use { zip ->
                    while (found == null) {
                        val e = zip.nextEntry ?: break
                        if (!e.isDirectory && e.name.endsWith(".csv", true)) {
                            val t = zip.readBytes().toString(Charsets.UTF_8)
                            if (CHANNEL_ID.containsMatchIn(t)) found = t
                        }
                    }
                }
                found ?: error("Não achei o arquivo de inscrições dentro do .zip")
            } else bytes.toString(Charsets.UTF_8)

            val channels = text.lineSequence().mapNotNull { line ->
                val id = CHANNEL_ID.find(line)?.value ?: return@mapNotNull null
                // Formato do Takeout: id, endereço, nome. O nome é o que vem depois da segunda vírgula.
                val name = line.split(',', limit = 3).getOrNull(2)?.trim()?.trim('"')?.ifBlank { null } ?: "Canal"
                Channel("https://www.youtube.com/channel/$id", name)
            }.distinctBy { it.url }.toList()
            require(channels.isNotEmpty()) {
                "Nenhuma inscrição encontrada nesse arquivo. Escolha o subscriptions.csv (ou inscrições.csv) do Google Takeout."
            }
            channels
        }) {
            Store.followAll(it)
            message.value = "${it.size} canais importados"
            loadFollowingFeed(force = true)
        }
    }

    // ---- Vídeos guardados no celular ----
    val localVideos = MutableStateFlow<List<Item>>(emptyList())

    fun loadLocalVideos(context: android.content.Context) {
        val app = context.applicationContext
        task({ com.autotv.player.data.LocalVideos.list(app) }) { localVideos.value = it }
    }

    // ---- Conta Google (login por código, como nas TVs) ----
    /** Código a mostrar enquanto o usuário autoriza no navegador; nulo = sem login em andamento. */
    val loginCode = MutableStateFlow<Account.DeviceCode?>(null)
    val signedIn = Account.signedIn
    private var loginJob: Job? = null

    fun startLogin() {
        loginJob?.cancel()
        loginJob = viewModelScope.launch {
            try {
                val code = withContext(Dispatchers.IO) { Account.requestCode() }
                loginCode.value = code
                val ok = withContext(Dispatchers.IO) { Account.awaitAuthorization(code) }
                loginCode.value = null
                if (!ok) { message.value = "O código venceu. Toque em Entrar para gerar outro."; return@launch }
                message.value = "Conta conectada"
                // Tenta também copiar a lista de canais para o app (se a API permitir).
                val channels = withContext(Dispatchers.IO) { runCatching { Account.subscribedChannels() }.getOrDefault(emptyList()) }
                if (channels.isNotEmpty()) {
                    Store.followAll(channels)
                    message.value = "Conta conectada: ${channels.size} canais"
                }
                loadFollowingFeed(force = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                loginCode.value = null
                message.value = e.message ?: e.javaClass.simpleName
            }
        }
    }

    fun cancelLogin() { loginJob?.cancel(); loginCode.value = null }

    fun signOut() { Account.signOut(); followingFeedKey = null; followingFeed.value = emptyList(); loadFollowingFeed() }

    /** Novidades de "Seguindo": pela conta Google, se conectada; senão pelos canais salvos. */
    fun loadFollowingFeed(force: Boolean = false) {
        val key = "${Account.signedIn.value}|" + following.value.joinToString("|") { it.url }
        if (!force && key == followingFeedKey) return
        followingFeedKey = key
        if (!Account.signedIn.value && following.value.isEmpty()) { followingFeed.value = emptyList(); return }
        followingLoading.value = true
        viewModelScope.launch {
            try {
                followingFeed.value = withContext(Dispatchers.IO) { Account.followingItems(force) }
                followingError.value = null
            } catch (e: Exception) {
                followingFeed.value = emptyList()
                followingError.value = e.message ?: e.javaClass.simpleName
            } finally {
                followingLoading.value = false
            }
        }
    }

    val followingLoading = MutableStateFlow(false)
    val followingError = MutableStateFlow<String?>(null)

    private companion object {
        val CHANNEL_ID = Regex("UC[\\w-]{22}")
    }
}
