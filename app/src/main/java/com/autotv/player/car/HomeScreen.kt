package com.autotv.player.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.lifecycle.lifecycleScope
import com.autotv.player.data.Item
import com.autotv.player.data.Playlist
import com.autotv.player.data.Store
import com.autotv.player.player.PlayerHub
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach

private fun Screen.openPlayer() {
    screenManager.push(PlayerScreen(carContext))
}

/** Toca [items] a partir de [index] e abre a tela de vídeo. */
fun Screen.playAndOpen(items: List<Item>, index: Int) {
    PlayerHub.play(items, index)
    openPlayer()
}

fun itemEntries(items: List<Item>): List<Entry> =
    items.mapIndexed { i, item -> Entry(item.title, item.group) { it.playAndOpen(items, i) } }

private fun playlistEntry(p: Playlist): Entry = Entry(p.name, "${p.items.size} itens") { screen ->
    val groups = p.items.groupBy { it.group ?: "Sem categoria" }
    if (groups.size > 1) {
        screen.screenManager.push(PagedScreen(screen.carContext, p.name) {
            groups.map { (g, list) -> Entry(g, "${list.size} itens") { s -> s.screenManager.push(PagedScreen(s.carContext, g) { itemEntries(list) }) } }
        })
    } else {
        screen.screenManager.push(PagedScreen(screen.carContext, p.name) { itemEntries(p.items) })
    }
}

class HomeScreen(ctx: CarContext) : PagedScreen(ctx, "AutoTV", isRoot = true, entries = {
    val list = ArrayList<Entry>()
    PlayerHub.nowPlaying.value?.let { now ->
        list += Entry("Tocando agora", now.title) { it.screenManager.push(PlayerScreen(it.carContext)) }
    }
    val favs = Store.favorites.value
    if (favs.isNotEmpty()) {
        list += Entry("Favoritos", "${favs.size} itens") { s ->
            s.screenManager.push(PagedScreen(s.carContext, "Favoritos") { itemEntries(Store.favorites.value) })
        }
    }
    Store.playlists.value.forEach { list += playlistEntry(it) }
    list += Entry("Buscar no YouTube", "Digite no carro ou use o app do celular") { s ->
        s.screenManager.push(YouTubeSearchScreen(s.carContext))
    }
    list
}) {
    init {
        // Redesenha quando listas/favoritos/o item tocando mudam.
        merge(
            Store.playlists.drop(1),
            Store.favorites.drop(1),
            PlayerHub.nowPlaying.drop(1)
        ).onEach { invalidate() }.launchIn(lifecycleScope)
    }
}
