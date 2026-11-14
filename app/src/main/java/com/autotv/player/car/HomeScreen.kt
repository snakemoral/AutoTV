package com.autotv.player.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.lifecycle.lifecycleScope
import com.autotv.player.data.Account
import com.autotv.player.data.LocalVideos
import com.autotv.player.data.Playlist
import com.autotv.player.data.Prefs
import com.autotv.player.data.Store
import com.autotv.player.data.YouTube
import com.autotv.player.player.PlayerHub
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext

fun playlistEntry(p: Playlist): Entry = Entry(p.name, "${p.items.size} itens") { screen ->
    val groups = p.items.groupBy { it.group ?: "Sem categoria" }
    if (groups.size > 1) {
        screen.screenManager.push(PagedScreen(screen.carContext, p.name) {
            groups.map { (g, list) ->
                Entry(g, "${list.size} itens") { s -> s.screenManager.push(PagedScreen(s.carContext, g) { itemEntries(list) }) }
            }
        })
    } else {
        screen.screenManager.push(PagedScreen(screen.carContext, p.name) { itemEntries(p.items) })
    }
}

/** A lista "Seguindo" (vídeos das inscrições). */
fun followingScreen(ctx: CarContext): Screen = videoList(ctx, "Seguindo") {
    withContext(Dispatchers.IO) { Account.followingItems() }
}

class HomeScreen(ctx: CarContext) : PagedScreen(ctx, "AutoTV", isRoot = true, entries = {
    val list = ArrayList<Entry>()
    PlayerHub.nowPlaying.value?.let { now ->
        list += Entry("Tocando agora", now.title, now.thumb) { it.screenManager.push(PlayerScreen(it.carContext)) }
    }
    list += Entry("Seguindo", "Novidades dos canais em que você é inscrito") { s ->
        s.screenManager.push(followingScreen(s.carContext))
    }
    list += Entry("YouTube", "Em alta, ao vivo, categorias e busca") { s ->
        s.screenManager.push(videoList(s.carContext, "YouTube") { YouTube.homeFeed() })
    }
    val playlists = Store.playlists.value
    list += Entry("TV (IPTV)", if (playlists.isEmpty()) "Adicione listas no app do celular" else "${playlists.size} listas") { s ->
        val all = Store.playlists.value
        if (all.size == 1) playlistEntry(all[0]).onClick(s)
        else s.screenManager.push(PagedScreen(s.carContext, "TV (IPTV)") { Store.playlists.value.map { playlistEntry(it) } })
    }
    list += Entry("Vídeos do celular", "Download, Filmes e outras pastas") { s ->
        val app = s.carContext.applicationContext
        s.screenManager.push(videoList(s.carContext, "Vídeos do celular") {
            withContext(Dispatchers.IO) { LocalVideos.list(app) }
        })
    }
    list += Entry("Navegador", "Abrir páginas da internet") { s ->
        if (Prefs.carAdvancedUi) s.screenManager.push(BrowserScreen(s.carContext))
        else androidx.car.app.CarToast.makeText(
            s.carContext, "Ligue \"Interface avançada no carro\" no app do celular", androidx.car.app.CarToast.LENGTH_LONG
        ).show()
    }
    list += Entry("Buscar no YouTube", "Digite no carro") { it.screenManager.push(YouTubeSearchScreen(it.carContext)) }
    val history = Store.history.value
    if (history.isNotEmpty() || com.autotv.player.data.HistorySync.enabled) {
        list += Entry("Histórico", "${history.size} vídeos") { s ->
            s.screenManager.push(videoList(s.carContext, "Histórico") {
                com.autotv.player.data.HistorySync.sync()
                Store.history.value
            })
        }
    }
    val favs = Store.favorites.value
    if (favs.isNotEmpty()) {
        list += Entry("Favoritos", "${favs.size} itens") { s ->
            s.screenManager.push(PagedScreen(s.carContext, "Favoritos") { itemEntries(Store.favorites.value) })
        }
    }
    list
}) {
    init {
        // Redesenha quando listas/favoritos/histórico/o item tocando mudam.
        merge(
            Store.playlists.drop(1),
            Store.favorites.drop(1),
            Store.history.drop(1),
            PlayerHub.nowPlaying.drop(1)
        ).onEach { invalidate() }.launchIn(lifecycleScope)
    }
}
