package com.autotv.player.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.lifecycle.lifecycleScope
import com.autotv.player.data.Account
import com.autotv.player.data.Playlist
import com.autotv.player.data.Store
import com.autotv.player.data.YouTube
import com.autotv.player.player.PlayerHub
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext

private fun playlistEntry(p: Playlist): Entry = Entry(p.name, "${p.items.size} itens") { screen ->
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

class HomeScreen(ctx: CarContext) : PagedScreen(ctx, "AutoTV", isRoot = true, entries = {
    val list = ArrayList<Entry>()
    PlayerHub.nowPlaying.value?.let { now ->
        list += Entry("Tocando agora", now.title, now.thumb) { it.screenManager.push(PlayerScreen(it.carContext)) }
    }
    list += Entry("YouTube", "Início com vídeos em alta e ao vivo") { s ->
        s.screenManager.push(videoList(s.carContext, "YouTube") { YouTube.homeFeed() })
    }
    list += Entry("Ao vivo agora", "YouTube") { s ->
        s.screenManager.push(videoList(s.carContext, "Ao vivo agora") {
            withContext(Dispatchers.IO) { YouTube.kiosk("live") }
        })
    }
    list += Entry("Seguindo", "Novidades dos canais em que você é inscrito") { s ->
        s.screenManager.push(videoList(s.carContext, "Seguindo") {
            withContext(Dispatchers.IO) { Account.followingItems() }
        })
    }
    list += Entry("Buscar no YouTube", "Digite no carro") { it.screenManager.push(YouTubeSearchScreen(it.carContext)) }
    val history = Store.history.value
    if (history.isNotEmpty()) {
        list += Entry("Histórico", "${history.size} vídeos") { s ->
            s.screenManager.push(videoList(s.carContext, "Histórico") { Store.history.value })
        }
    }
    val favs = Store.favorites.value
    if (favs.isNotEmpty()) {
        list += Entry("Favoritos", "${favs.size} itens") { s ->
            s.screenManager.push(PagedScreen(s.carContext, "Favoritos") { itemEntries(Store.favorites.value) })
        }
    }
    Store.playlists.value.forEach { list += playlistEntry(it) }
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
