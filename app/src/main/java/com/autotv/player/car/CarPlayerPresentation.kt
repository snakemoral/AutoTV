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
import com.autotv.player.data.Channel
import com.autotv.player.data.Kind
import com.autotv.player.data.Prefs
import com.autotv.player.data.Thumbs
import com.autotv.player.data.compactCount
import kotlinx.coroutines.withContext
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
    private val onBack: () -> Unit,
    private val onOpenChannel: (Channel) -> Unit
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
    private lateinit var spinner: android.widget.ProgressBar
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

    private lateinit var avatarView: ImageView
    private lateinit var likePill: TextView
    private lateinit var dislikePill: TextView
    private lateinit var subPill: TextView
    private lateinit var description: FrameLayout
    private lateinit var descTitle: TextView
    private lateinit var descFacts: TextView
    private lateinit var descText: TextView
    private lateinit var descScroll: android.widget.ScrollView
    private lateinit var noticeView: TextView
    private var descriptionOpen = false
    private var hideNotice: Runnable? = null

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
            updateTransportButtons()
            if (descriptionOpen) closeDescription()
        } }
        jobs += scope.launch {
            combine(PlayerHub.nowPlaying, PlayerHub.details, PlayerHub.rating, PlayerHub.subscribed) { _, _, _, _ -> }
                .collect { updateVideoInfo() }
        }
        jobs += scope.launch { PlayerHub.notice.collect { showNotice(it) } }
        jobs += scope.launch {
            combine(PlayerHub.nowPlaying, PlayerHub.audioOnly) { _, _ -> }.collect { updateArtwork() }
        }
        jobs += scope.launch {
            combine(PlayerHub.nowPlaying, PlayerHub.isLive) { _, _ -> }.collect { updateChat() }
        }
        jobs += scope.launch { combine(PlayerHub.viewers, PlayerHub.isLive) { _, _ -> }.collect { updateVideoInfo() } }
        jobs += scope.launch { PlayerHub.status.collect { s ->
            statusOverlay.text = s.orEmpty()
            statusOverlay.visibility = if (s.isNullOrBlank()) View.GONE else View.VISIBLE
        } }
        jobs += scope.launch { PlayerHub.isPlaying.collect {
            playBtn.setImageResource(if (it) R.drawable.ic_pause else R.drawable.ic_play)
            // Pausado: controles à mostra. Começou a tocar: conta os segundos a partir de agora para escondê-los.
            if (!it) showControls() else kick()
        } }
        jobs += scope.launch { PlayerHub.loading.collect { spinner.visibility = if (it) View.VISIBLE else View.GONE } }
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
                if (controlsVisible && !settingsOpen && !descriptionOpen && PlayerHub.isPlaying.value &&
                    SystemClock.uptimeMillis() - lastInteraction > AUTO_HIDE_MS
                ) hideControls()
                handler.postDelayed(this, 500)
            }
        }
        ticker = tick
        handler.post(tick)
    }

    override fun onStop() {
        chatJob?.cancel(); chatJob = null; chatVideo = null
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
        // A faixa de cima é reservada pelo Android Auto só por causa do botão dele no canto direito:
        // a barra do título sobe para ela e deixa livre apenas o espaço desse botão.
        controlsContent.setPadding(left, 0, right, bottom)
        // O chat vai de cima a baixo da área livre (abaixo dele fica a barra do Android Auto).
        if (::chatPanel.isInitialized) chatPanel.layoutParams = (chatPanel.layoutParams as FrameLayout.LayoutParams).apply {
            topMargin = dp(10); bottomMargin = bottom + dp(10); rightMargin = right + dp(6)
        }
        topBar.setPadding(dp(12), dp(6), if (top > 0) top + dp(6) else dp(12), dp(28))
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
            setOnClickListener {
                if (settingsOpen) closeSettings() else if (descriptionOpen) closeDescription()
                else if (controlsVisible) hideControls() else showControls()
            }
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
        buildArtwork()
        buildChat()

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
        buildDescription()

        noticeView = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(10), dp(20), dp(10))
            background = rounded(0xE6202024.toInt(), dp(24))
            visibility = View.GONE
        }
        root.addView(noticeView, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM).apply {
            bottomMargin = dp(140); leftMargin = dp(40); rightMargin = dp(40)
        })
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

    private lateinit var topBar: LinearLayout

    /** Botão redondo só com ícone (voltar, ajustes). */
    private fun roundIcon(res: Int, label: String, onClick: () -> Unit) = ImageView(context).apply {
        setImageResource(res)
        contentDescription = label
        scaleType = ImageView.ScaleType.FIT_CENTER
        setPadding(dp(11), dp(11), dp(11), dp(11))
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x77000000) }
        layoutParams = LinearLayout.LayoutParams(dp(54), dp(54))
        setOnClickListener { kick(); onClick() }
    }

    // ---- chat ao vivo ----
    private lateinit var chatBtn: ImageView
    private lateinit var chatPanel: FrameLayout
    private lateinit var chatList: LinearLayout
    private lateinit var chatScroll: android.widget.ScrollView
    private lateinit var chatInfo: TextView
    private var chatJob: kotlinx.coroutines.Job? = null
    private var chatVideo: String? = null

    private fun buildChat() {
        // Sem fundo próprio: cada mensagem tem o seu, escuro e meio transparente (o vídeo aparece por trás).
        chatPanel = FrameLayout(context).apply {
            visibility = View.GONE
            isClickable = true // toques no chat não escondem/mostram os controles
        }
        chatList = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM
        }
        chatScroll = android.widget.ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            isFillViewport = true // poucas mensagens ficam embaixo, como no YouTube
            addView(chatList, ViewGroup.LayoutParams(-1, -2))
        }
        chatPanel.addView(chatScroll, FrameLayout.LayoutParams(-1, -1))
        chatInfo = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            gravity = Gravity.CENTER
            background = rounded(0x73000000, dp(12))
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        chatPanel.addView(chatInfo, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        // À direita, de cima a baixo da área do vídeo; fica visível mesmo quando os controles somem
        // (os controles, quando aparecem, ficam por cima dele).
        root.addView(chatPanel, FrameLayout.LayoutParams(dp(215), -1, Gravity.END).apply {
            topMargin = dp(10); bottomMargin = dp(10); rightMargin = dp(6)
        })
        moveChat(animated = false)
    }

    private fun showChat(list: List<com.autotv.player.data.ChatMessage>) {
        chatInfo.visibility = View.GONE
        chatList.removeAllViews()
        // Na tela do carro, só as mais recentes (a mais nova embaixo).
        list.takeLast(30).forEach { m ->
            val text = android.text.SpannableStringBuilder()
            m.amount?.let {
                val s = text.length
                text.append(it).append("  ")
                text.setSpan(android.text.style.ForegroundColorSpan(0xFFFFC107.toInt()), s, text.length, 0)
            }
            val s = text.length
            text.append(m.author)
            text.setSpan(android.text.style.ForegroundColorSpan(0xFFAAAAAA.toInt()), s, text.length, 0)
            text.append("  ").append(m.text)
            chatList.addView(TextView(context).apply {
                this.text = text
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                background = rounded(0x73000000, dp(12))
                setPadding(dp(12), dp(6), dp(12), dp(6))
            }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(5) })
        }
        chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun updateChat() {
        val item = PlayerHub.nowPlaying.value
        val id = item?.let { com.autotv.player.data.Store.youtubeId(it) }
        val available = id != null && PlayerHub.isLive.value
        chatBtn.visibility = if (available) View.VISIBLE else View.GONE
        chatBtn.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL; setColor(if (chatWanted && available) 0xCCFF2D55.toInt() else 0x77000000)
        }
        val open = available && chatWanted
        chatPanel.visibility = if (open) View.VISIBLE else View.GONE
        if (!open) { chatJob?.cancel(); chatJob = null; chatVideo = null; return }
        if (chatVideo == id && chatJob?.isActive == true) return
        chatJob?.cancel()
        chatVideo = id
        chatList.removeAllViews()
        chatInfo.text = "Conectando ao chat…"
        chatInfo.visibility = View.VISIBLE
        chatJob = scope.launch {
            try {
                com.autotv.player.data.LiveChat.watch(id!!).collect { showChat(it) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                chatList.removeAllViews()
                chatInfo.text = e.message ?: "Não foi possível abrir o chat"
                chatInfo.visibility = View.VISIBLE
            }
        }
    }

    // ---- capa da rádio (conteúdo só de áudio) ----
    private lateinit var artwork: LinearLayout
    private lateinit var artworkImage: ImageView
    private lateinit var artworkNote: TextView
    private lateinit var artworkName: TextView

    private fun buildArtwork() {
        artwork = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            visibility = View.GONE
        }
        val box = FrameLayout(context).apply {
            background = rounded(0xFF26262C.toInt(), dp(28))
            clipToOutline = true
        }
        artworkNote = TextView(context).apply {
            text = "♪"
            setTextColor(0xFF9A9AA2.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 96f)
            gravity = Gravity.CENTER
        }
        box.addView(artworkNote, FrameLayout.LayoutParams(-1, -1))
        artworkImage = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        box.addView(artworkImage, FrameLayout.LayoutParams(-1, -1))
        artwork.addView(box, LinearLayout.LayoutParams(dp(220), dp(220)))
        artworkName = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        artwork.addView(artworkName, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(14) })
        // Fica sobre a área do vídeo (que é preta numa rádio) e por baixo dos controles.
        root.addView(artwork, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
    }

    private fun updateArtwork() {
        val item = PlayerHub.nowPlaying.value
        val show = item != null && PlayerHub.audioOnly.value
        artwork.visibility = if (show) View.VISIBLE else View.GONE
        if (!show || item == null) return
        artworkName.text = item.title
        val url = item.thumb?.takeIf { it.isNotBlank() }
        if (artworkImage.tag == url) return
        artworkImage.tag = url
        artworkImage.setImageDrawable(null)
        artworkNote.visibility = View.VISIBLE
        if (url == null) return
        scope.launch {
            val bmp = withContext(Dispatchers.IO) { Thumbs.load(url, 512) }
            if (bmp != null && artworkImage.tag == url) {
                artworkImage.setImageBitmap(bmp)
                artworkNote.visibility = View.GONE
            }
        }
    }

    private fun buildTopBar() {
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = gradient(true)
            setPadding(dp(12), dp(10), dp(12), dp(28))
        }
        // Voltar para a lista: seta (sem texto), em botão redondo.
        bar.addView(roundIcon(R.drawable.ic_back, "Voltar") { onBack() })

        // Foto do canal: toque para ver os vídeos dele.
        avatarView = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFF333333.toInt()) }
            clipToOutline = true
            visibility = View.GONE
            setOnClickListener { kick(); PlayerHub.currentChannel()?.let { onOpenChannel(it) } }
        }
        bar.addView(avatarView, LinearLayout.LayoutParams(dp(52), dp(52)).apply { leftMargin = dp(10) })

        // Título e canal: toque para ver a descrição do vídeo.
        val titles = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setOnClickListener { kick(); openDescription() }
        }
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
        bar.addView(titles, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(12), 0, dp(10), 0) })

        // Ajustes (qualidade, áudio, legendas): engrenagem, como no app do YouTube.
        // Chat da transmissão ao vivo (só aparece em lives do YouTube).
        chatBtn = roundIcon(R.drawable.ic_chat, "Chat") { chatWanted = !chatWanted; updateChat() }.apply {
            visibility = View.GONE
            (layoutParams as LinearLayout.LayoutParams).rightMargin = dp(8)
        }
        bar.addView(chatBtn)
        bar.addView(roundIcon(R.drawable.ic_settings, "Ajustes") { openSettings(0) })
        controlsContent.addView(bar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        topBar = bar
    }

    private fun buildCenter() {
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        prevBtn = iconButton(R.drawable.ic_prev, 64) { PlayerHub.previous() }
        val back10 = textButton("−10", 64) { PlayerHub.seekBy(-10_000) }
        playBtn = iconButton(R.drawable.ic_play, 88) { PlayerHub.togglePause() }
        val fwd10 = textButton("+10", 64) { PlayerHub.seekBy(10_000) }
        nextBtn = iconButton(R.drawable.ic_next, 64) { PlayerHub.next() }
        // Rodinha em volta do botão central enquanto o vídeo carrega.
        spinner = android.widget.ProgressBar(context).apply {
            isIndeterminate = true
            indeterminateTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            visibility = View.GONE
        }
        val center = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(116), dp(116)).apply { setMargins(dp(4), 0, dp(4), 0) }
        }
        center.addView(spinner, FrameLayout.LayoutParams(dp(116), dp(116), Gravity.CENTER))
        center.addView(playBtn, FrameLayout.LayoutParams(dp(88), dp(88), Gravity.CENTER))
        listOf(prevBtn, back10, center, fwd10, nextBtn).forEach { row.addView(it) }
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
        timeCur = timeText().apply {
            text = "0:00"
            setPadding(dp(8), dp(10), dp(8), dp(10))
            setOnClickListener { kick(); if (PlayerHub.liveNow()) PlayerHub.seekToLive() }
        }
        timeTotal = timeText().apply { text = "0:00" }
        seek = SeekView(context).apply { onUserAction = { kick() } }
        line.addView(timeCur)
        line.addView(seek, LinearLayout.LayoutParams(0, dp(48), 1f).apply { setMargins(dp(6), 0, dp(6), 0) })
        line.addView(timeTotal)
        bar.addView(line)

        val pills = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        // À esquerda, qualidade, áudio e legendas ficam num espaço próprio: se não couberem, os textos são
        // encurtados ali, sem empurrar gostei / não gostei / inscrição para fora da tela.
        val options = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        qualityPill = pill("Qualidade") { openSettings(0) }
        audioPill = pill("Áudio") { openSettings(1) }
        subsPill = pill("Legendas: desl.") { openSettings(2) }
        listOf(qualityPill, audioPill, subsPill).forEach {
            it.isSingleLine = true
            it.ellipsize = android.text.TextUtils.TruncateAt.END
            it.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            it.setPadding(dp(14), dp(9), dp(14), dp(9))
            options.addView(it, LinearLayout.LayoutParams(-2, -2, 1f).apply { setMargins(0, 0, dp(8), 0) })
        }
        pills.addView(options, LinearLayout.LayoutParams(0, -2, 1f))
        // À direita: gostei, não gostei e inscrição (só em vídeos do YouTube).
        likePill = pill("👍") { PlayerHub.rate(1) }
        dislikePill = pill("👎") { PlayerHub.rate(-1) }
        subPill = pill("Inscrever-se") { PlayerHub.toggleSubscribe() }
        listOf(likePill, dislikePill, subPill).forEach {
            it.isSingleLine = true
            pills.addView(it, LinearLayout.LayoutParams(-2, -2).apply { setMargins(dp(8), 0, 0, 0) })
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
        if (descriptionOpen) {
            // Com a descrição aberta, arrastar rola o texto.
            descScroll.scrollBy(0, distanceY.roundToInt())
            return
        }
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

    // ---- canal, gostei / não gostei, inscrição e descrição ----

    private fun selectable(view: TextView, selected: Boolean) {
        view.background = rounded(if (selected) 0xFFFFFFFF.toInt() else 0x77000000, dp(24))
        view.setTextColor(if (selected) Color.BLACK else Color.WHITE)
    }

    private fun updateVideoInfo() {
        val item = PlayerHub.nowPlaying.value
        val details = PlayerHub.details.value
        val youtube = item?.kind == Kind.YOUTUBE

        val channel = details?.channelName ?: item?.group
        val subs = details?.subscribers ?: -1
        val watching = if (PlayerHub.isLive.value) PlayerHub.viewers.value else -1L
        channelView.text = listOfNotNull(
            channel,
            subs.takeIf { it >= 0 }?.let { "${compactCount(it)} inscritos" },
            watching.takeIf { it >= 0 }?.let { "${compactCount(it)} assistindo" }
        ).joinToString("  •  ")
        channelView.visibility = if (channelView.text.isNullOrBlank()) View.GONE else View.VISIBLE

        val hasChannel = youtube && PlayerHub.currentChannel() != null
        avatarView.visibility = if (hasChannel) View.VISIBLE else View.GONE
        val avatar = details?.avatar
        if (avatar == null) {
            avatarView.setImageDrawable(null)
            avatarView.tag = null
        } else if (avatarView.tag != avatar) {
            avatarView.tag = avatar
            avatarView.setImageDrawable(null)
            scope.launch {
                val bmp = withContext(Dispatchers.IO) { Thumbs.load(avatar, 160) }
                if (bmp != null && avatarView.tag == avatar) avatarView.setImageBitmap(bmp)
            }
        }

        val rating = PlayerHub.rating.value
        val likes = details?.likes ?: -1
        likePill.text = if (likes >= 0) "👍 ${compactCount(likes)}" else "👍"
        selectable(likePill, rating > 0)
        selectable(dislikePill, rating < 0)
        val subscribed = PlayerHub.subscribed.value
        val signedIn = com.autotv.player.data.Account.signedIn.value
        subPill.text = when {
            signedIn -> if (subscribed) "Inscrito" else "Inscrever-se"
            else -> if (subscribed) "Seguindo" else "Seguir"
        }
        selectable(subPill, subscribed)
        likePill.visibility = if (youtube) View.VISIBLE else View.GONE
        dislikePill.visibility = if (youtube) View.VISIBLE else View.GONE
        subPill.visibility = if (hasChannel) View.VISIBLE else View.GONE

        if (descriptionOpen) fillDescription()
    }

    private fun showNotice(text: String) {
        noticeView.text = text
        noticeView.visibility = View.VISIBLE
        hideNotice?.let { handler.removeCallbacks(it) }
        val hide = Runnable { noticeView.visibility = View.GONE }
        hideNotice = hide
        handler.postDelayed(hide, 4000)
    }

    private fun buildDescription() {
        description = FrameLayout(context).apply {
            visibility = View.GONE
            setBackgroundColor(0xAA000000.toInt())
            isClickable = true
            setOnClickListener { closeDescription() }
        }
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(0xF2202024.toInt(), dp(20))
            setPadding(dp(18), dp(14), dp(18), dp(12))
            isClickable = true
        }
        descTitle = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        descFacts = TextView(context).apply {
            setTextColor(0xFFFF8FA3.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        descText = TextView(context).apply {
            setTextColor(0xEEFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setLineSpacing(0f, 1.15f)
        }
        descScroll = android.widget.ScrollView(context).apply {
            isVerticalScrollBarEnabled = true
            addView(descText, ViewGroup.LayoutParams(-1, -2))
        }
        card.addView(descTitle)
        card.addView(descFacts, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        card.addView(descScroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(10); bottomMargin = dp(8) })

        // No carro só chegam toques e arrastos: os botões rolam o texto de página em página.
        val pager = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val up = pill("▲") { descScroll.smoothScrollBy(0, -(descScroll.height * 0.8f).roundToInt()) }
        val down = pill("▼") { descScroll.smoothScrollBy(0, (descScroll.height * 0.8f).roundToInt()) }
        val close = pill("Fechar") { closeDescription() }
        pager.addView(up)
        pager.addView(down, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(10) })
        pager.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        pager.addView(close)
        card.addView(pager)

        description.addView(card, FrameLayout.LayoutParams(0, -1, Gravity.CENTER).apply { setMargins(0, dp(24), 0, dp(24)) })
        root.addView(description, FrameLayout.LayoutParams(-1, -1))
    }

    private fun fillDescription() {
        val item = PlayerHub.nowPlaying.value
        val details = PlayerHub.details.value
        descTitle.text = item?.title.orEmpty()
        val facts = listOfNotNull(
            details?.channelName ?: item?.group,
            details?.views?.takeIf { it >= 0 }?.let { "${compactCount(it)} visualizações" },
            details?.uploaded
        )
        descFacts.text = facts.joinToString("  •  ")
        descFacts.visibility = if (facts.isEmpty()) View.GONE else View.VISIBLE
        descText.text = when {
            item?.kind != Kind.YOUTUBE -> "Este conteúdo não tem descrição."
            details == null -> "Carregando as informações do vídeo…"
            details.description.isBlank() -> "Este vídeo não tem descrição."
            else -> details.description
        }
    }

    private fun openDescription() {
        if (settingsOpen) return
        descriptionOpen = true
        val card = description.getChildAt(0)
        card.layoutParams = (card.layoutParams as FrameLayout.LayoutParams).apply {
            width = (root.width * 0.8f).roundToInt().coerceAtLeast(dp(320))
        }
        fillDescription()
        descScroll.scrollTo(0, 0)
        description.visibility = View.VISIBLE
    }

    private fun closeDescription() {
        descriptionOpen = false
        description.visibility = View.GONE
        kick()
    }

    // ---- estado dos controles ----

    private fun updatePills(q: List<Option>, a: List<Option>, s: List<Option>) {
        val qSel = q.firstOrNull { it.selected }?.label
        qualityPill.text = "Qualidade: ${qSel?.replace("Automático", "Auto") ?: "—"}"
        qualityPill.visibility = if (q.isEmpty()) View.GONE else View.VISIBLE
        // Só o idioma ("English (US) original" vira "English"): o resto está na tela de ajustes.
        val aSel = a.firstOrNull { it.selected }?.label?.substringBefore(" (")?.trim()
        audioPill.text = "Áudio: ${aSel ?: "—"}"
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
            // Ao vivo: a barra cobre o trecho recente que dá para rever; o rótulo é o botão de voltar ao vivo.
            val seekable = PlayerHub.liveSeekable()
            val behind = PlayerHub.behindLive()
            timeCur.text = if (behind) "▶▶ AO VIVO" else "● AO VIVO"
            timeCur.setTextColor(if (behind) Color.WHITE else 0xFFFF2D55.toInt())
            timeTotal.text = if (behind) "-" + formatDuration((dur - pos) / 1000) else ""
            seek.progress = if (seekable && dur > 0) pos.toFloat() / dur else 1f
            seek.buffered = if (seekable && dur > 0) PlayerHub.bufferedMs().toFloat() / dur else 1f
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

    /** Chat encostado à direita; com os controles à mostra, sai da frente da barra de brilho. */
    private fun moveChat(animated: Boolean = true) {
        if (!::chatPanel.isInitialized) return
        val x = if (controlsVisible) -dp(70).toFloat() else 0f
        chatPanel.animate().cancel()
        if (animated) chatPanel.animate().translationX(x).setDuration(220).start() else chatPanel.translationX = x
    }

    private fun showControls() {
        kick()
        if (controlsVisible && controls.visibility == View.VISIBLE) return
        controlsVisible = true
        moveChat()
        controls.animate().cancel()
        controls.visibility = View.VISIBLE
        controls.animate().alpha(1f).setDuration(180).start()
    }

    private fun hideControls() {
        if (!controlsVisible) return
        controlsVisible = false
        moveChat()
        controls.animate().cancel()
        controls.animate().alpha(0f).setDuration(220).withEndAction {
            if (!controlsVisible) controls.visibility = View.GONE
        }.start()
        // Garantia: se a animação for interrompida, os controles somem do mesmo jeito.
        handler.postDelayed({ if (!controlsVisible) { controls.alpha = 0f; controls.visibility = View.GONE } }, 400)
    }

    private companion object {
        /** O chat ao vivo fica aberto de um vídeo para o outro, enquanto o app estiver aberto. */
        var chatWanted = false

        const val AUTO_HIDE_MS = 4500L
    }
}
