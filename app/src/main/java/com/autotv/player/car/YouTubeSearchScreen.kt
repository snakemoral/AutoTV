package com.autotv.player.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.Row
import androidx.car.app.model.SearchTemplate
import androidx.car.app.model.Template
import androidx.lifecycle.lifecycleScope
import com.autotv.player.data.Item
import com.autotv.player.data.YouTube
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Busca no YouTube com resultados em lista, com miniatura, título, canal e duração / "AO VIVO". */
class YouTubeSearchScreen(ctx: CarContext) : Screen(ctx) {
    private var results: List<Item> = emptyList()
    private var loading = false
    private var error: String? = null
    private val thumbs = ThumbHelper(this)

    private val callback = object : SearchTemplate.SearchCallback {
        override fun onSearchTextChanged(searchText: String) {}
        override fun onSearchSubmitted(searchText: String) { search(searchText) }
    }

    private fun search(query: String) {
        if (query.isBlank()) return
        loading = true; error = null; invalidate()
        lifecycleScope.launch {
            try {
                results = withContext(Dispatchers.IO) { YouTube.search(query) }
            } catch (e: Exception) {
                results = emptyList()
                error = e.message ?: e.javaClass.simpleName
            }
            loading = false
            invalidate()
        }
    }

    override fun onGetTemplate(): Template {
        val builder = SearchTemplate.Builder(callback)
            .setHeaderAction(Action.BACK)
            .setSearchHint("Buscar no YouTube")
            .setShowKeyboardByDefault(results.isEmpty())
            .setLoading(loading)

        if (!loading) {
            val limit = carContext.getCarService(ConstraintManager::class.java)
                .getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST)
            val list = ItemList.Builder()
            if (results.isEmpty()) list.setNoItemsMessage(error ?: "Digite e confirme para buscar")
            results.take(limit).forEachIndexed { i, item ->
                val row = Row.Builder()
                    .setTitle(item.title)
                    .setImage(thumbs.icon(item.thumb), Row.IMAGE_TYPE_LARGE)
                    .setOnClickListener { playAndOpen(results, i) }
                itemSubtitle(item)?.let { row.addText(it) }
                list.addItem(row.build())
            }
            builder.setItemList(list.build())
        }
        return builder.build()
    }
}
