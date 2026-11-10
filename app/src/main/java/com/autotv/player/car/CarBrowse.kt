package com.autotv.player.car

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
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
import com.autotv.player.data.Item
import com.autotv.player.data.Prefs
import com.autotv.player.data.Thumbs
import com.autotv.player.data.YouTube
import com.autotv.player.data.formatDuration
import com.autotv.player.player.PlayerHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Abre a lista "Categorias" do YouTube. */
fun Screen.openCategories() {
    screenManager.push(PagedScreen(carContext, "Categorias") {
        YouTube.CATEGORIES.map { cat ->
            Entry(cat.title) { s ->
                s.screenManager.push(videoList(s.carContext, cat.title) {
                    withContext(Dispatchers.IO) { YouTube.loadCategory(cat) }
                })
            }
        }
    })
}

/** Tela de lista de vídeos: cartões grandes desenhados pelo app (modo avançado) ou grade do Android Auto. */
fun videoList(ctx: CarContext, title: String, loader: suspend () -> List<Item>): Screen =
    if (Prefs.carAdvancedUi) VideoBrowseScreen(ctx, title, loader) else VideoGridScreen(ctx, title, loader)

/**
 * Lista de vídeos no estilo do YouTube: miniaturas grandes, título pequeno em até duas linhas.
 * O Android Auto só fornece a superfície; quem desenha é [CarBrowsePresentation].
 */
class VideoBrowseScreen(
    ctx: CarContext,
    private val screenTitle: String,
    private val loader: suspend () -> List<Item>
) : Screen(ctx) {

    private var items: List<Item>? = null
    private var error: String? = null

    private val client = PresentationClient(carContext) { c, display ->
        CarBrowsePresentation(
            c, display, screenTitle,
            onBack = { screenManager.pop() },
            onSearch = { screenManager.push(YouTubeSearchScreen(carContext)) },
            onCategories = { openCategories() },
            onNowPlaying = { screenManager.push(PlayerScreen(carContext)) },
            onPick = { list, index -> playAndOpen(list, index) }
        ).also { push(it) }
    }

    private fun push(ui: CarBrowsePresentation) {
        val e = error
        val list = items
        when {
            e != null -> ui.showMessage("Não foi possível carregar: $e")
            list == null -> ui.showMessage("Carregando…")
            list.isEmpty() -> ui.showMessage("Nenhum vídeo encontrado")
            else -> ui.showItems(list)
        }
    }

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) { CarSurface.setClient(client) }
            override fun onStop(owner: LifecycleOwner) { CarSurface.clearClient(client) }
        })
        lifecycleScope.launch {
            try {
                items = loader()
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            }
            (client.ui as? CarBrowsePresentation)?.let { push(it) }
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

/** A interface da lista de vídeos, desenhada numa tela virtual. */
class CarBrowsePresentation(
    ctx: Context,
    display: Display,
    private val title: String,
    private val onBack: () -> Unit,
    private val onSearch: () -> Unit,
    private val onCategories: () -> Unit,
    private val onNowPlaying: () -> Unit,
    private val onPick: (List<Item>, Int) -> Unit
) : Presentation(ctx, display), SurfaceUi {

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val gate = Semaphore(6)
    private val density get() = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).roundToInt()

    private lateinit var root: FrameLayout
    private lateinit var content: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var grid: LinearLayout
    private lateinit var message: TextView
    private lateinit var nowPlayingPill: TextView

    private var pendingItems: List<Item>? = null
    private var pendingMessage: String? = "Carregando…"
    private var builtFor = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.setBackgroundDrawable(ColorDrawable(BG))
        buildViews()
        setContentView(root)
        root.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (right - left != oldRight - oldLeft) rebuild()
        }
        render()
    }

    override fun onStart() {
        super.onStart()
        scope.launch {
            PlayerHub.nowPlaying.collect {
                nowPlayingPill.visibility = if (it == null) View.GONE else View.VISIBLE
            }
        }
    }

    override fun open() = show()

    override fun release() {
        scope.cancel()
        try { dismiss() } catch (_: Exception) { }
    }

    override fun setInsets(left: Int, top: Int, right: Int, bottom: Int) {
        if (!::content.isInitialized) return
        content.setPadding(left + dp(10), top + dp(6), right + dp(10), bottom)
        rebuild()
    }

    override fun dispatchTap(x: Float, y: Float) {
        val t = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        dispatchTouchEvent(down)
        down.recycle()
        handler.postDelayed({
            val up = MotionEvent.obtain(t, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0)
                .apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            dispatchTouchEvent(up)
            up.recycle()
        }, 50)
    }

    override fun onScroll(distanceX: Float, distanceY: Float) {
        if (::scroll.isInitialized) scroll.scrollBy(0, distanceY.roundToInt())
    }

    override fun onFling(velocityX: Float, velocityY: Float) {
        if (::scroll.isInitialized) scroll.fling(-velocityY.roundToInt())
    }

    fun showMessage(text: String) {
        pendingItems = null
        pendingMessage = text
        render()
    }

    fun showItems(items: List<Item>) {
        pendingItems = items.take(MAX_ITEMS)
        pendingMessage = null
        builtFor = -1
        render()
    }

    // ------------------------------------------------------------------ construção

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius.toFloat()
    }

    private fun pill(text: String, onClick: () -> Unit) = TextView(context).apply {
        this.text = text
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        gravity = Gravity.CENTER
        setPadding(dp(16), dp(9), dp(16), dp(9))
        background = rounded(0x33FFFFFF, dp(22))
        setOnClickListener { onClick() }
    }

    private fun buildViews() {
        root = FrameLayout(context).apply { setBackgroundColor(BG) }
        content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        root.addView(content, FrameLayout.LayoutParams(-1, -1))

        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        bar.addView(pill("‹  Voltar") { onBack() })
        bar.addView(TextView(context).apply {
            text = title
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(14), 0, dp(10), 0) })
        nowPlayingPill = pill("▶  Tocando") { onNowPlaying() }.apply { visibility = View.GONE }
        val gap = LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) }
        bar.addView(nowPlayingPill, gap)
        bar.addView(pill("Categorias") { onCategories() }, LinearLayout.LayoutParams(gap))
        bar.addView(pill("Buscar") { onSearch() }, LinearLayout.LayoutParams(gap))
        content.addView(bar, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })

        val body = FrameLayout(context)
        scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = true
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        grid = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, dp(64), dp(16))
        }
        scroll.addView(grid, FrameLayout.LayoutParams(-1, -2))
        body.addView(scroll, FrameLayout.LayoutParams(-1, -1))

        message = TextView(context).apply {
            setTextColor(0xCCFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        body.addView(message, FrameLayout.LayoutParams(-1, -1))

        // Setas para rolar a lista (além de arrastar o dedo).
        val arrows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        fun arrow(text: String, dir: Int) = TextView(context).apply {
            this.text = text
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x99202024.toInt()) }
            setOnClickListener { scroll.smoothScrollBy(0, (scroll.height * 0.8f).roundToInt() * dir) }
        }
        arrows.addView(arrow("▲", -1), LinearLayout.LayoutParams(dp(52), dp(52)).apply { bottomMargin = dp(14) })
        arrows.addView(arrow("▼", +1), LinearLayout.LayoutParams(dp(52), dp(52)))
        body.addView(arrows, FrameLayout.LayoutParams(dp(56), -2, Gravity.END or Gravity.CENTER_VERTICAL))

        content.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun rebuild() {
        builtFor = -1
        render()
    }

    private fun render() {
        if (!::grid.isInitialized) return
        val msg = pendingMessage
        val items = pendingItems
        if (msg != null || items == null) {
            message.text = msg.orEmpty()
            message.visibility = View.VISIBLE
            scroll.visibility = View.GONE
            return
        }
        message.visibility = View.GONE
        scroll.visibility = View.VISIBLE

        val gridWidth = root.width - content.paddingLeft - content.paddingRight - grid.paddingRight
        if (gridWidth <= 0) { root.post { render() }; return }
        if (builtFor == gridWidth) return
        builtFor = gridWidth

        val cols = (gridWidth / dp(230)).coerceIn(2, 5)
        val gapPx = dp(10)
        val cardW = (gridWidth - gapPx * (cols - 1)) / cols
        val thumbH = cardW * 9 / 16

        grid.removeAllViews()
        for (start in items.indices step cols) {
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            for (i in start until minOf(start + cols, items.size)) {
                row.addView(card(items, i, items[i], thumbH), LinearLayout.LayoutParams(cardW, -2).apply {
                    if (i != start) leftMargin = gapPx
                })
            }
            grid.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        }
        scroll.scrollTo(0, 0)
    }

    private fun card(all: List<Item>, index: Int, item: Item, thumbH: Int): View {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setOnClickListener { onPick(all, index) }
        }
        val frame = FrameLayout(context).apply {
            background = rounded(0xFF26262A.toInt(), dp(10))
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(10).toFloat())
                }
            }
        }
        val image = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        frame.addView(image, FrameLayout.LayoutParams(-1, -1))

        val badge = when {
            item.isLive -> "AO VIVO"
            item.durationSec > 0 -> formatDuration(item.durationSec)
            else -> null
        }
        if (badge != null) {
            frame.addView(TextView(context).apply {
                text = badge
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(dp(6), dp(2), dp(6), dp(2))
                background = rounded(if (item.isLive) 0xFFCC0000.toInt() else 0xCC000000.toInt(), dp(5))
            }, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(6), dp(6)) })
        }
        card.addView(frame, LinearLayout.LayoutParams(-1, thumbH))

        card.addView(TextView(context).apply {
            text = item.title
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })

        item.group?.takeIf { it.isNotBlank() }?.let { channel ->
            card.addView(TextView(context).apply {
                text = channel
                setTextColor(0xFFAAAAAA.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
        }

        val url = item.thumb
        if (url != null) {
            val ready = Thumbs.cached(url)
            if (ready != null) image.setImageBitmap(ready)
            else scope.launch {
                val bmp = gate.withPermit { withContext(Dispatchers.IO) { Thumbs.load(url, 480) } }
                if (bmp != null) image.setImageBitmap(bmp)
            }
        }
        return card
    }

    private companion object {
        const val BG = 0xFF0F0F0F.toInt()
        const val MAX_ITEMS = 60
    }
}
