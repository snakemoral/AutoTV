package com.autotv.player.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class Entry(
    val title: String,
    val subtitle: String? = null,
    /** Miniatura / logo (opcional). */
    val thumb: String? = null,
    val onClick: (Screen) -> Unit
)

/** Lista com paginação que respeita o limite de itens do carro (menor com o carro em movimento). */
open class PagedScreen(
    ctx: CarContext,
    private val screenTitle: String,
    private val isRoot: Boolean = false,
    /** Se informado, a lista é carregada em segundo plano (mostra "carregando" até terminar). */
    private val loader: (suspend () -> List<Entry>)? = null,
    private val entries: () -> List<Entry> = { emptyList() }
) : Screen(ctx) {
    private var page = 0
    private val thumbs = ThumbHelper(this)
    private var loading = loader != null
    private var loaded: List<Entry> = emptyList()
    private var error: String? = null

    init {
        if (loader != null) {
            lifecycleScope.launch {
                try {
                    loaded = loader.invoke()
                } catch (e: Exception) {
                    error = e.message ?: e.javaClass.simpleName
                }
                loading = false
                invalidate()
            }
        }
    }

    override fun onGetTemplate(): Template {
        if (loading) {
            return ListTemplate.Builder()
                .setTitle(screenTitle)
                .setHeaderAction(if (isRoot) Action.APP_ICON else Action.BACK)
                .setLoading(true)
                .build()
        }
        val all = if (loader != null) loaded else entries()
        val limit = carContext.getCarService(ConstraintManager::class.java)
            .getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST)
        val needsPaging = all.size > limit
        val pageSize = if (needsPaging) (limit - 2).coerceIn(2, 20) else all.size
        val pages = if (pageSize == 0) 1 else (all.size + pageSize - 1) / pageSize
        if (page >= pages) page = pages - 1
        if (page < 0) page = 0

        val list = ItemList.Builder()
        if (all.isEmpty()) {
            list.setNoItemsMessage(error ?: "Nada por aqui ainda. Adicione listas no app do celular.")
        } else {
            if (needsPaging && page > 0) {
                list.addItem(
                    Row.Builder().setTitle("◀ Página anterior")
                        .setOnClickListener { page--; invalidate() }.build()
                )
            }
            all.drop(page * pageSize).take(pageSize).forEach { e ->
                val row = Row.Builder().setTitle(e.title)
                e.subtitle?.let { row.addText(it) }
                if (e.thumb != null) row.setImage(thumbs.icon(e.thumb), Row.IMAGE_TYPE_LARGE)
                row.setOnClickListener { e.onClick(this) }
                list.addItem(row.build())
            }
            if (needsPaging && page < pages - 1) {
                list.addItem(
                    Row.Builder().setTitle("Próxima página ▶")
                        .setOnClickListener { page++; invalidate() }.build()
                )
            }
        }

        val title = if (needsPaging) "$screenTitle (${page + 1}/$pages)" else screenTitle
        return ListTemplate.Builder()
            .setTitle(title)
            .setHeaderAction(if (isRoot) Action.APP_ICON else Action.BACK)
            .setSingleList(list.build())
            .build()
    }
}
