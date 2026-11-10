package com.autotv.player.car

import android.graphics.Color
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.autotv.player.R
import com.autotv.player.data.Account
import com.autotv.player.data.Item
import com.autotv.player.data.LocalVideos
import com.autotv.player.data.Store
import com.autotv.player.data.YouTube
import com.autotv.player.car.CarBrowsePresentation.Chip
import com.autotv.player.player.PlayerHub
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Tela inicial do carro (modo avançado): abas no topo, todas a um toque, e a lista de vídeos embaixo.
 * Abre em "Em alta". As abas de lista carregam aqui mesmo; TV, Web, Favoritos e Buscar abrem outra tela.
 */
class HubScreen(ctx: CarContext) : Screen(ctx) {
    private var section = "trending"
    /** Filtro escolhido em cada aba (categoria em "Em alta", tipo em "Seguindo"). */
    private val filterOf = HashMap<String, String>()
    private var raw: List<Item>? = null
    private var error: String? = null
    private var job: Job? = null
    private var savedScroll = 0

    private val client = PresentationClient(carContext) { c, display ->
        CarBrowsePresentation(
            c, display, "AutoTV",
            onBack = {},
            onSearch = {},
            onCategories = {},
            onNowPlaying = {},
            onPick = { list, index -> playAndOpen(list, index) },
            hub = true,
            onTab = { id -> openTab(id) },
            onFilter = { id -> pickFilter(id) },
            initialScroll = savedScroll,
            onScrollSaved = { savedScroll = it }
        ).also { push(it) }
    }

    private val ui get() = client.ui as? CarBrowsePresentation

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) { CarSurface.setClient(client) }
            override fun onStop(owner: LifecycleOwner) { CarSurface.clearClient(client) }
        })
        // A aba "Tocando" aparece e some conforme há algo no player.
        PlayerHub.nowPlaying.drop(1).onEach { ui?.let { push(it) } }.launchIn(lifecycleScope)
        load(force = false)
    }

    private fun filter() = filterOf[section] ?: "all"

    private fun shown(): List<Item>? {
        val list = raw ?: return null
        return if (section == "following") YouTube.filterFollowing(list, filter()) else list
    }

    private fun push(p: CarBrowsePresentation) {
        val tabs = ArrayList<Chip>()
        if (PlayerHub.nowPlaying.value != null) tabs += Chip("now", "▶ Tocando")
        tabs += Chip("trending", "Em alta", section == "trending")
        tabs += Chip("following", "Seguindo", section == "following")
        tabs += Chip("live", "Ao vivo", section == "live")
        tabs += Chip("history", "Histórico", section == "history")
        tabs += Chip("local", "Celular", section == "local")
        tabs += Chip("tv", "TV")
        tabs += Chip("web", "Web")
        tabs += Chip("favs", "Favoritos")
        tabs += Chip("search", "Buscar")

        val filters = ArrayList<Chip>()
        if (section != "history") filters += Chip("refresh", "⟳ Atualizar")
        when (section) {
            "trending" -> {
                filters += Chip("all", "Tudo", filter() == "all")
                YouTube.CATEGORIES.filter { it.id != "live" }.forEach { filters += Chip(it.id, it.title, filter() == it.id) }
            }
            "following" -> if (raw?.let { YouTube.canFilterFollowing(it) } == true) {
                YouTube.FOLLOWING_FILTERS.forEach { (id, label) -> filters += Chip(id, label, filter() == id) }
            }
        }
        p.setChips(tabs, filters)

        val e = error
        val list = shown()
        when {
            e != null -> p.showMessage("Não foi possível carregar: $e")
            list == null -> p.showMessage("Carregando…")
            list.isEmpty() -> p.showMessage(if (section == "history") "Nada assistido ainda." else "Nenhum vídeo encontrado")
            else -> p.showItems(list)
        }
    }

    private fun openTab(id: String) {
        when (id) {
            "now" -> screenManager.push(PlayerScreen(carContext))
            "tv" -> {
                val all = Store.playlists.value
                if (all.size == 1) playlistEntry(all[0]).onClick(this)
                else screenManager.push(PagedScreen(carContext, "TV (IPTV)") { Store.playlists.value.map { playlistEntry(it) } })
            }
            "web" -> screenManager.push(BrowserScreen(carContext))
            "favs" -> screenManager.push(PagedScreen(carContext, "Favoritos") { itemEntries(Store.favorites.value) })
            "search" -> screenManager.push(YouTubeSearchScreen(carContext))
            else -> if (id != section) {
                section = id
                load(force = false)
            }
        }
    }

    private fun pickFilter(id: String) {
        when {
            id == "refresh" -> load(force = true)
            section == "following" -> {
                // Filtra a lista que já está carregada.
                filterOf[section] = id
                savedScroll = 0
                ui?.let { push(it) }
            }
            else -> {
                filterOf[section] = id
                load(force = false)
            }
        }
    }

    private fun load(force: Boolean) {
        job?.cancel()
        raw = null
        error = null
        savedScroll = 0
        ui?.let { push(it) }
        val which = section
        val f = filter()
        val app = carContext.applicationContext
        job = lifecycleScope.launch {
            try {
                raw = when (which) {
                    "trending" -> {
                        val cat = YouTube.CATEGORIES.firstOrNull { it.id == f }
                        if (cat == null) YouTube.homeFeed(force) else withContext(Dispatchers.IO) { YouTube.loadCategory(cat, force) }
                    }
                    "following" -> withContext(Dispatchers.IO) { Account.followingItems(force) }
                    "live" -> withContext(Dispatchers.IO) { YouTube.kiosk("live") }
                    "history" -> Store.history.value
                    "local" -> withContext(Dispatchers.IO) { LocalVideos.list(app) }
                    else -> emptyList()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            }
            ui?.let { push(it) }
        }
    }

    override fun onGetTemplate(): Template {
        val search = Action.Builder()
            .setIcon(CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.ic_search)).setTint(CarColor.DEFAULT).build())
            .setOnClickListener { screenManager.push(YouTubeSearchScreen(carContext)) }
            .build()
        return NavigationTemplate.Builder()
            .setActionStrip(ActionStrip.Builder().addAction(search).build())
            .setBackgroundColor(CarColor.createCustom(Color.BLACK, Color.BLACK))
            .build()
    }
}
