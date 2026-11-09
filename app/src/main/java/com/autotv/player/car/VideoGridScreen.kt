package com.autotv.player.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.GridItem
import androidx.car.app.model.GridTemplate
import androidx.car.app.model.ItemList
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Template
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.lifecycleScope
import com.autotv.player.R
import com.autotv.player.data.Item
import com.autotv.player.data.YouTube
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Grade de vídeos com miniaturas, como a home do YouTube. Ações: buscar e escolher categoria. */
class VideoGridScreen(
    ctx: CarContext,
    private val screenTitle: String,
    private val loader: suspend () -> List<Item>
) : Screen(ctx) {

    private var items: List<Item>? = null
    private var error: String? = null
    private var page = 0
    private val thumbs = ThumbHelper(this)

    init {
        lifecycleScope.launch {
            try {
                items = loader()
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            }
            invalidate()
        }
    }

    private fun icon(res: Int): CarIcon =
        CarIcon.Builder(IconCompat.createWithResource(carContext, res)).setTint(CarColor.DEFAULT).build()

    private fun openCategories() {
        screenManager.push(PagedScreen(carContext, "Categorias") {
            YouTube.CATEGORIES.map { cat ->
                Entry(cat.title) { s ->
                    s.screenManager.push(VideoGridScreen(s.carContext, cat.title) {
                        withContext(Dispatchers.IO) { YouTube.loadCategory(cat) }
                    })
                }
            }
        })
    }

    override fun onGetTemplate(): Template {
        val strip = ActionStrip.Builder()
            .addAction(Action.Builder().setIcon(icon(R.drawable.ic_search))
                .setOnClickListener { screenManager.push(YouTubeSearchScreen(carContext)) }.build())
            .addAction(Action.Builder().setIcon(icon(R.drawable.ic_list))
                .setOnClickListener { openCategories() }.build())
            .build()

        error?.let {
            return MessageTemplate.Builder("Não foi possível carregar: $it")
                .setTitle(screenTitle)
                .setHeaderAction(Action.BACK)
                .setActionStrip(strip)
                .build()
        }
        val all = items
        val builder = GridTemplate.Builder()
            .setTitle(screenTitle)
            .setHeaderAction(Action.BACK)
            .setActionStrip(strip)
        if (all == null) return builder.setLoading(true).build()

        val limit = carContext.getCarService(ConstraintManager::class.java)
            .getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_GRID)
        val needsPaging = all.size > limit
        val pageSize = if (needsPaging) (limit - 2).coerceIn(2, 30) else all.size
        val pages = if (pageSize == 0) 1 else (all.size + pageSize - 1) / pageSize
        if (page >= pages) page = pages - 1
        if (page < 0) page = 0

        val list = ItemList.Builder()
        if (all.isEmpty()) {
            list.setNoItemsMessage("Nenhum vídeo encontrado")
        } else {
            if (needsPaging && page > 0) {
                list.addItem(
                    GridItem.Builder().setTitle("Anterior")
                        .setImage(icon(R.drawable.ic_prev), GridItem.IMAGE_TYPE_ICON)
                        .setOnClickListener { page--; invalidate() }.build()
                )
            }
            val start = page * pageSize
            all.drop(start).take(pageSize).forEachIndexed { i, item ->
                val g = GridItem.Builder()
                    .setTitle(item.title)
                    .setImage(thumbs.icon(item.thumb), GridItem.IMAGE_TYPE_LARGE)
                    .setOnClickListener { playAndOpen(all, start + i) }
                itemSubtitle(item)?.let { g.setText(it) }
                list.addItem(g.build())
            }
            if (needsPaging && page < pages - 1) {
                list.addItem(
                    GridItem.Builder().setTitle("Próxima")
                        .setImage(icon(R.drawable.ic_next), GridItem.IMAGE_TYPE_ICON)
                        .setOnClickListener { page++; invalidate() }.build()
                )
            }
        }
        return builder.setSingleList(list.build()).build()
    }
}
