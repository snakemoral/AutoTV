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
import com.autotv.player.data.HistorySync
import com.autotv.player.data.Item
import com.autotv.player.data.Kind
import com.autotv.player.data.Radio
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
 * Abre em "Em alta". As abas de lista (inclusive TV) carregam aqui mesmo; Web e Favoritos abrem outra tela.
 */
class HubScreen(ctx: CarContext) : Screen(ctx) {
    private var section = "trending"
    /** Filtro escolhido em cada aba (categoria em "Em alta", tipo em "Seguindo"). */
    private val filterOf = HashMap<String, String>()
    private var raw: List<Item>? = null
    private var error: String? = null
    private var job: Job? = null
    private var savedScroll = 0
    /** Aba TV: lista de canais escolhida e grupo aberto (null = mostrando os grupos). */
    private var tvList = 0
    private var tvGroup: String? = null

    private val client = PresentationClient(carContext) { c, display ->
        CarBrowsePresentation(
            c, display, "AutoTV",
            onBack = {},
            onSearch = {},
            onCategories = {},
            onNowPlaying = {},
            onPick = { list, index ->
                val picked = list[index]
                if (picked.id.startsWith(GROUP_PREFIX)) {
                    // Aba TV: tocar num grupo mostra os canais dele.
                    tvGroup = picked.title
                    savedScroll = 0
                    ui?.let { push(it) }
                } else playAndOpen(list, index)
            },
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
        tabs += Chip("shorts", "Shorts", section == "shorts")
        tabs += Chip("following", "Seguindo", section == "following")
        tabs += Chip("live", "Ao vivo", section == "live")
        tabs += Chip("history", "Histórico", section == "history")
        tabs += Chip("radio", "Rádio", section == "radio")
        tabs += Chip("local", "Celular", section == "local")
        tabs += Chip("tv", "TV", section == "tv")
        tabs += Chip("web", "Web")
        tabs += Chip("favs", "Favoritos")

        if (section == "tv") { pushTv(p, tabs); return }

        val filters = ArrayList<Chip>()
        if (section != "history" || HistorySync.enabled) filters += Chip("refresh", "⟳ Atualizar")
        when (section) {
            "trending" -> {
                filters += Chip("all", "Tudo", filter() == "all")
                YouTube.CATEGORIES.filter { it.id != "live" }.forEach { filters += Chip(it.id, it.title, filter() == it.id) }
            }
            "radio" -> Radio.GENRES.forEach { (tag, label) ->
                // "all" é o filtro padrão das abas; aqui equivale às mais ouvidas.
                val id = tag.ifBlank { "all" }
                filters += Chip(id, label, filter() == id)
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
            // Rádios: uma coluna só, com o logotipo pequeno ao lado do nome.
            else -> p.showItems(list, compact = section == "radio")
        }
    }

    /** Aba TV (listas IPTV): grupos e canais em uma coluna, com o logotipo pequeno, como as rádios. */
    private fun pushTv(p: CarBrowsePresentation, tabs: List<Chip>) {
        val lists = Store.playlists.value
        val filters = ArrayList<Chip>()
        if (tvList !in lists.indices) { tvList = 0; tvGroup = null }
        if (lists.size > 1) lists.forEachIndexed { i, pl -> filters += Chip("pl:$i", pl.name, i == tvList) }
        val pl = lists.getOrNull(tvList)
        if (pl == null) {
            p.setChips(tabs, filters)
            p.showMessage("Nenhuma lista de TV. Adicione no app do celular, em TV/Rádio > TV.")
            return
        }
        val groups = pl.items.groupBy { it.group ?: "Sem categoria" }
        val g = tvGroup?.takeIf { groups.containsKey(it) }
        if (groups.size > 1 && g == null) {
            p.setChips(tabs, filters)
            p.showItems(groups.map { (name, list) ->
                Item(GROUP_PREFIX + name, name, "", "${list.size} canais", Kind.STREAM)
            }, compact = true)
            return
        }
        if (g != null) {
            filters.add(0, Chip("groups", "‹ Grupos"))
            filters.add(1, Chip("group", g, true))
        }
        p.setChips(tabs, filters)
        val list = if (g == null) pl.items else groups[g].orEmpty()
        if (list.isEmpty()) p.showMessage("Esta lista está vazia") else p.showItems(list, compact = true)
    }

    private fun openTab(id: String) {
        when (id) {
            "now" -> screenManager.push(PlayerScreen(carContext))
            "tv" -> if (section != "tv") {
                job?.cancel()
                section = "tv"
                savedScroll = 0
                ui?.let { push(it) }
            } else if (tvGroup != null) {
                // Tocar em "TV" de novo volta para os grupos.
                tvGroup = null
                savedScroll = 0
                ui?.let { push(it) }
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
            section == "tv" -> {
                when {
                    id == "groups" -> tvGroup = null
                    id.startsWith("pl:") -> { tvList = id.removePrefix("pl:").toIntOrNull() ?: 0; tvGroup = null }
                    else -> return
                }
                savedScroll = 0
                ui?.let { push(it) }
            }
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
                    "shorts" -> withContext(Dispatchers.IO) { YouTube.shorts() }
                    "radio" -> withContext(Dispatchers.IO) { Radio.stations(if (f == "all") "" else f, force) }
                    "history" -> {
                        // Com a conta conectada, traz antes o que foi visto no celular e na TV.
                        HistorySync.sync(force)
                        Store.history.value
                    }
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
