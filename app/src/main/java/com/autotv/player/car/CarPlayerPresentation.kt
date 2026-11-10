@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.autotv.player.car

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.InputDevice
import android.view.MotionEvent
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.text.CueGroup
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.SubtitleView
import com.autotv.player.R
import com.autotv.player.data.Prefs
import com.autotv.player.data.formatDuration
import com.autotv.player.player.Option
import com.autotv.player.player.PlayerHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Interface própria do vídeo no carro. É desenhada numa tela virtual (VirtualDisplay) cujo conteúdo
 * o Android Auto mostra na superfície de navegação. Os toques chegam por dispatchTap().
 *
 * Tudo que é "controle" (título, botões, barra de progresso, brilho) fica em [controls] e some junto.
 */
class CarPlayerPresentation(
    ctx: Context,
    display: Display,
    private val onBack: () -> Unit
) : Presentation(ctx, display), SurfaceUi {

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val density get() = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).roundToInt()

    private lateinit var root: FrameLayout
    private lateinit var videoFrame: AspectRatioFrameLayout
    private lateinit var texture: TextureView
    private lateinit var subtitles: SubtitleView
    private lateinit var dim: View
    private lateinit var controls: FrameLayout
    private lateinit var controlsContent: FrameLayout
    private lateinit var titleView: TextView
    private lateinit var channelView: TextView
    private lateinit var statusOverlay: TextView
    private lateinit var playBtn: ImageView
    private lateinit var prevBtn: ImageView
    private lateinit var nextBtn: ImageView
    private lateinit var seek: SeekView
    private lateinit var timeCur: TextView
    private lateinit var timeTotal: TextView
    private lateinit var qualityPill: TextView
    private lateinit var audioPill: TextView
    private lateinit var subsPill: TextView
    private lateinit var brightness: BrightnessView
    private lateinit var brightLabel: TextView
    private lateinit var settings: FrameLayout
    private lateinit var settingsList: LinearLayout
    private lateinit var settingsTabs: List<TextView>
    private lateinit var settingsPageLabel: TextView

    private var controlsVisible = true
    private var settingsOpen = false
    private var settingsTab = 0
    private var settingsPage = 0
    private var lastInteraction = SystemClock.uptimeMillis()
    private var ticker: Runnable? = null
    private val jobs = ArrayList<Job>()

    private val playerListener = object : Player.Listener {
        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.height > 0) {
                videoFrame.setAspectRatio(videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height)
            }
        }

        override fun onCues(cueGroup: CueGroup) {
            subtitles.setCues(cueGroup.cues)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        buildViews()
        setContentView(root)
    }

    override fun onStart() {
        super.onStart()
        val player = PlayerHub.player
        player.setVideoTextureView(texture)
        player.addListener(playerListener)
        PlayerHub.setCarActive(true)

        val v = player.videoSize
        if (v.height > 0) videoFrame.setAspectRatio(v.width * v.pixelWidthHeightRatio / v.height)

        brightness.level = Prefs.brightness
        applyBrightness()

        jobs += scope.launch { PlayerHub.nowPlaying.collect { item ->
            titleView.text = item?.title.orEmpty()
            channelView.text = item?.group.orEmpty()
            channelView.visibility = if (item?.group.isNullOrBlank()) View.GONE else View.VISIBLE
            updateTransportButtons()
        } }
        jobs += scope.launch { PlayerHub.status.collect { s ->
            statusOverlay.text = s.orEmpty()
            statusOverlay.visibility = if (s.isNullOrBlank()) View.GONE else View.VISIBLE
        } }
        jobs += scope.launch { PlayerHub.isPlaying.collect {
            playBtn.setImageResource(if (it) R.drawable.ic_pause else R.drawable.ic_play)
            if (!it) showControls()
        } }
        jobs += scope.launch {
            combine(PlayerHub.qualityOptions, PlayerHub.audioOptions, PlayerHub.subtitleOptions) { q, a, s -> Triple(q, a, s) }
                .collect { (q, a, s) ->
                    updatePills(q, a, s)
                    if (settingsOpen) refreshSettings()
                }
        }

        val tick = object : Runnable {
            override fun run() {
                updateProgress()
                if (controlsVisible && !settingsOpen && PlayerHub.isPlaying.value &&
                    SystemClock.uptimeMillis() - lastInteraction > AUTO_HIDE_MS
                ) hideControls()
                handler.postDelayed(this, 500)
            }
        }
        ticker = tick
        handler.post(tick)
    }

    override fun onStop() {
        ticker?.let { handler.removeCallbacks(it) }
        ticker = null
        jobs.forEach { it.cancel() }
        jobs.clear()
        val player = PlayerHub.player
        player.removeListener(playerListener)
        player.clearVideoTextureView(texture)
        PlayerHub.setCarActive(false)
        super.onStop()
    }

    override fun open() = show()

    override fun release() {
        scope.cancel()
        try { dismiss() } catch (_: Exception) { }
    }

    /** Área livre (sem barras do carro por cima), em pixels da superfície. */
    override fun setInsets(left: Int, top: Int, right: Int, bottom: Int) {
        if (!::controlsContent.isInitialized) return
        controlsContent.setPadding(left, top, right, bottom)
        subtitles.setPadding(left, 0, right, maxOf(bottom, dp(24)))
    }

    /** Transforma um toque vindo do Android Auto em um toque na nossa interface. */
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

    // ------------------------------------------------------------------ construção da interface

    private fun buildViews() {
        root = FrameLayout(context).apply {
            setBackgroundColor(Color.BLACK)
            isClickable = true
            setOnClickListener { if (settingsOpen) closeSettings() else if (controlsVisible) hideControls() else showControls() }
        }

        videoFrame = AspectRatioFrameLayout(context).apply {
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        texture = TextureView(context)
        videoFrame.addView(texture, FrameLayout.LayoutParams(-1, -1))
        root.addView(videoFrame, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))

        subtitles = SubtitleView(context).apply {
            setFractionalTextSize(0.055f)
            setApplyEmbeddedStyles(true)
        }
        root.addView(subtitles, FrameLayout.LayoutParams(-1, -1))

        dim = View(context).apply { visibility = View.GONE }
        root.addView(dim, FrameLayout.LayoutParams(-1, -1))

        statusOverlay = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setPadding(dp(20), dp(10), dp(20), dp(10))
            background = rounded(0xAA000000.toInt(), dp(24))
            visibility = View.GONE
        }
        root.addView(statusOverlay, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL or Gravity.TOP).apply { topMargin = dp(90) })

        controls = FrameLayout(context)
        controlsContent = FrameLayout(context)
        controls.addView(controlsContent, FrameLayout.LayoutParams(-1, -1))
        root.addView(controls, FrameLayout.LayoutParams(-1, -1))

        buildTopBar()
        buildCenter()
        buildBottomBar()
        buildBrightness()
        buildSettings()
    }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius.toFloat()
    }

    private fun pill(text: String, onClick: () -> Unit) = TextView(context).apply {
        this.text = text
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        gravity = Gravity.CENTER
        setPadding(dp(18), dp(10), dp(18), dp(10))
        background = rounded(0x77000000, dp(24))
        setOnClickListener { kick(); onClick() }
    }

    private fun iconButton(res: Int, sizeDp: Int, onClick: () -> Unit) = ImageView(context).apply {
        setImageResource(res)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        val pad = dp(sizeDp / 4)
        setPadding(pad, pad, pad, pad)
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x77000000) }
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)).apply { setMargins(dp(10), 0, dp(10), 0) }
        setOnClickListener { kick(); onClick() }
    }

    private fun textButton(text: String, sizeDp: Int, onClick: () -> Unit) = TextView(context).apply {
        this.text = text
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        gravity = Gravity.CENTER
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x77000000) }
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)).apply { setMargins(dp(10), 0, dp(10), 0) }
        setOnClickListener { kick(); onClick() }
    }

    private fun gradient(top: Boolean) = GradientDrawable(
        if (top) GradientDrawable.Orientation.TOP_BOTTOM else GradientDrawable.Orientation.BOTTOM_TOP,
        intArrayOf(0xCC000000.toInt(), 0x00000000)
    )

    private fun buildTopBar() {
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = gradient(true)
            setPadding(dp(12), dp(10), dp(12), dp(28))
        }
        bar.addView(pill("‹  Lista") { onBack() })

        val titles = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        titleView = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setShadowLayer(6f, 0f, 2f, Color.BLACK)
        }
        channelView = TextView(context).apply {
            setTextColor(0xCCFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        titles.addView(titleView)
        titles.addView(channelView)
        bar.addView(titles, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(16), 0, dp(16), 0) })

        bar.addView(pill("⚙  Ajustes") { openSettings(0) })
        controlsContent.addView(bar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
    }

    private fun buildCenter() {
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        prevBtn = iconButton(R.drawable.ic_prev, 64) { PlayerHub.previous() }
        val back10 = textButton("−10", 64) { PlayerHub.seekBy(-10_000) }
        playBtn = iconButton(R.drawable.ic_play, 88) { PlayerHub.togglePause() }
        val fwd10 = textButton("+10", 64) { PlayerHub.seekBy(10_000) }
        nextBtn = iconButton(R.drawable.ic_next, 64) { PlayerHub.next() }
        listOf(prevBtn, back10, playBtn, fwd10, nextBtn).forEach { row.addView(it) }
        controlsContent.addView(row, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
    }

    private fun buildBottomBar() {
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = gradient(false)
            setPadding(dp(12), dp(28), dp(12), dp(10))
        }
        val line = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        fun timeText() = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setShadowLayer(4f, 0f, 1f, Color.BLACK)
        }
        timeCur = timeText().apply { text = "0:00" }
        timeTotal = timeText().apply { text = "0:00" }
        seek = SeekView(context).apply { onUserAction = { kick() } }
        line.addView(timeCur)
        line.addView(seek, LinearLayout.LayoutParams(0, dp(48), 1f).apply { setMargins(dp(6), 0, dp(6), 0) })
        line.addView(timeTotal)
        bar.addView(line)

        val pills = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        qualityPill = pill("Qualidade") { openSettings(0) }
        audioPill = pill("Áudio") { openSettings(1) }
        subsPill = pill("Legendas: desl.") { openSettings(2) }
        listOf(qualityPill, audioPill, subsPill).forEach {
            pills.addView(it, LinearLayout.LayoutParams(-2, -2).apply { setMargins(0, 0, dp(10), 0) })
        }
        bar.addView(pills)
        controlsContent.addView(bar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
    }

    private fun buildBrightness() {
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = rounded(0x55000000, dp(28))
            setPadding(dp(4), dp(6), dp(4), dp(6))
        }
        fun small(text: String, onClick: () -> Unit) = TextView(context).apply {
            this.text = text
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            gravity = Gravity.CENTER
            setOnClickListener { kick(); onClick() }
        }
        brightness = BrightnessView(context).apply {
            onLevelChanged = { kick(); applyBrightness(it) }
        }
        brightLabel = small("☀") { }.apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f) }
        col.addView(brightLabel, LinearLayout.LayoutParams(dp(52), dp(36)))
        col.addView(small("+") { stepBrightness(+0.15f) }, LinearLayout.LayoutParams(dp(52), dp(44)))
        col.addView(brightness, LinearLayout.LayoutParams(dp(52), 0, 1f))
        col.addView(small("−") { stepBrightness(-0.15f) }, LinearLayout.LayoutParams(dp(52), dp(44)))
        controlsContent.addView(col, FrameLayout.LayoutParams(dp(60), -1, Gravity.END).apply {
            topMargin = dp(96); bottomMargin = dp(120); rightMargin = dp(10)
        })
    }

    private fun stepBrightness(delta: Float) {
        brightness.level = brightness.level + delta
        applyBrightness(brightness.level)
    }

    private fun applyBrightness(level: Float = brightness.level) {
        Prefs.brightness = level
        // Escurece o vídeo com uma camada preta por cima (quanto menor o brilho, mais opaca).
        val a = ((1f - level) * 255).roundToInt().coerceIn(0, 255)
        dim.setBackgroundColor(Color.argb(a, 0, 0, 0))
        dim.visibility = if (a == 0) View.GONE else View.VISIBLE
        brightLabel.text = "☀ ${(level * 100).roundToInt()}"
    }

    /** No carro o dedo arrastado chega como "rolagem": arrastar para cima/baixo ajusta o brilho. */
    override fun onScroll(distanceX: Float, distanceY: Float) {
        if (settingsOpen || !::brightness.isInitialized) return
        if (kotlin.math.abs(distanceY) < kotlin.math.abs(distanceX)) return
        showControls()
        stepBrightness(distanceY / root.height.coerceAtLeast(1) * 1.3f)
    }

    // ---- painel de ajustes ----

    private fun buildSettings() {
        settings = FrameLayout(context).apply {
            visibility = View.GONE
            setBackgroundColor(0xAA000000.toInt())
            isClickable = true
            setOnClickListener { closeSettings() }
        }
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(0xF2202024.toInt(), dp(20))
            setPadding(dp(14), dp(12), dp(14), dp(12))
            isClickable = true
        }
        val tabs = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val names = listOf("Qualidade", "Áudio", "Legendas")
        settingsTabs = names.mapIndexed { i, n ->
            TextView(context).apply {
                text = n
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                setPadding(dp(8), dp(10), dp(8), dp(10))
                setOnClickListener { kick(); settingsTab = i; settingsPage = 0; refreshSettings() }
            }
        }
        settingsTabs.forEach { tabs.addView(it, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(3), 0, dp(3), 0) }) }
        card.addView(tabs)

        settingsList = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        card.addView(settingsList, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(8) })

        val pager = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val prev = pill("◀") { settingsPage = (settingsPage - 1).coerceAtLeast(0); refreshSettings() }
        val next = pill("▶") { settingsPage++; refreshSettings() }
        settingsPageLabel = TextView(context).apply {
            setTextColor(0xCCFFFFFF.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f); gravity = Gravity.CENTER
        }
        val close = pill("Fechar") { closeSettings() }
        pager.addView(prev)
        pager.addView(settingsPageLabel, LinearLayout.LayoutParams(0, -2, 1f))
        pager.addView(next)
        pager.addView(close, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(16) })
        card.addView(pager)

        settings.addView(card, FrameLayout.LayoutParams(0, -1, Gravity.CENTER).apply { setMargins(0, dp(24), 0, dp(24)) })
        root.addView(settings, FrameLayout.LayoutParams(-1, -1))
    }

    private fun openSettings(tab: Int) {
        settingsTab = tab
        settingsPage = 0
        settingsOpen = true
        val card = settings.getChildAt(0)
        card.layoutParams = (card.layoutParams as FrameLayout.LayoutParams).apply {
            width = (root.width * 0.72f).roundToInt().coerceAtLeast(dp(320))
        }
        settings.visibility = View.VISIBLE
        refreshSettings()
    }

    private fun closeSettings() {
        settingsOpen = false
        settings.visibility = View.GONE
        kick()
    }

    private fun optionsForTab(): List<Option> = when (settingsTab) {
        0 -> PlayerHub.qualityOptions.value
        1 -> PlayerHub.audioOptions.value
        else -> PlayerHub.subtitleOptions.value
    }

    private fun refreshSettings() {
        settingsTabs.forEachIndexed { i, t ->
            t.background = rounded(if (i == settingsTab) 0xFFFF2D55.toInt() else 0x33FFFFFF, dp(18))
        }
        val options = optionsForTab()
        // Quantas linhas cabem: altura do painel menos abas, paginação e margens.
        val availDp = (root.height / density) - 48 - 60 - 56 - 40
        val perPage = (availDp / 50f).toInt().coerceIn(3, 8)
        val pages = maxOf(1, (options.size + perPage - 1) / perPage)
        if (settingsPage >= pages) settingsPage = pages - 1

        settingsList.removeAllViews()
        if (options.isEmpty()) {
            settingsList.addView(TextView(context).apply {
                text = "Sem opções disponíveis para este vídeo"
                setTextColor(0xCCFFFFFF.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                setPadding(dp(8), dp(16), dp(8), dp(16))
            })
        } else {
            options.drop(settingsPage * perPage).take(perPage).forEach { opt ->
                settingsList.addView(TextView(context).apply {
                    text = (if (opt.selected) "✓  " else "     ") + opt.label
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(14), 0, dp(14), 0)
                    background = rounded(if (opt.selected) 0x44FFFFFF else 0x14FFFFFF, dp(14))
                    setOnClickListener { kick(); choose(opt) }
                }, LinearLayout.LayoutParams(-1, dp(46)).apply { setMargins(0, dp(2), 0, dp(2)) })
            }
        }
        settingsPageLabel.text = "${settingsPage + 1} / $pages"
    }

    private fun choose(opt: Option) {
        when (settingsTab) {
            0 -> PlayerHub.selectQuality(opt.id)
            1 -> PlayerHub.selectAudio(opt.id)
            else -> PlayerHub.selectSubtitle(opt.id)
        }
        closeSettings()
    }

    // ---- estado dos controles ----

    private fun updatePills(q: List<Option>, a: List<Option>, s: List<Option>) {
        val qSel = q.firstOrNull { it.selected }?.label
        qualityPill.text = "Qualidade: ${qSel ?: "—"}"
        qualityPill.visibility = if (q.isEmpty()) View.GONE else View.VISIBLE
        audioPill.text = "Áudio: ${a.firstOrNull { it.selected }?.label ?: "—"}"
        audioPill.visibility = if (a.size > 1) View.VISIBLE else View.GONE
        val sSel = s.firstOrNull { it.selected }
        subsPill.text = if (sSel == null || sSel.id == "off") "Legendas: desl." else "Legendas: ${sSel.label}"
        subsPill.visibility = if (s.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun updateTransportButtons() {
        prevBtn.alpha = if (PlayerHub.hasPrevious()) 1f else 0.35f
        nextBtn.alpha = if (PlayerHub.hasNext()) 1f else 0.35f
    }

    private fun updateProgress() {
        val live = PlayerHub.liveNow()
        val dur = PlayerHub.durationMs()
        val pos = PlayerHub.positionMs()
        if (live) {
            timeCur.text = "● AO VIVO"
            timeCur.setTextColor(0xFFFF2D55.toInt())
            timeTotal.text = ""
            seek.progress = 1f
            seek.buffered = 1f
        } else {
            timeCur.setTextColor(Color.WHITE)
            timeCur.text = formatDuration(pos / 1000)
            timeTotal.text = if (dur > 0) formatDuration(dur / 1000) else "--:--"
            seek.progress = if (dur > 0) pos.toFloat() / dur else 0f
            seek.buffered = if (dur > 0) PlayerHub.bufferedMs().toFloat() / dur else 0f
        }
        seek.invalidate()
        updateTransportButtons()
    }

    private fun kick() { lastInteraction = SystemClock.uptimeMillis() }

    private fun showControls() {
        kick()
        if (controlsVisible && controls.visibility == View.VISIBLE) return
        controlsVisible = true
        controls.animate().cancel()
        controls.visibility = View.VISIBLE
        controls.animate().alpha(1f).setDuration(180).start()
    }

    private fun hideControls() {
        if (!controlsVisible) return
        controlsVisible = false
        controls.animate().cancel()
        controls.animate().alpha(0f).setDuration(220).withEndAction {
            if (!controlsVisible) controls.visibility = View.GONE
        }.start()
    }

    private companion object {
        const val AUTO_HIDE_MS = 4500L
    }
}
