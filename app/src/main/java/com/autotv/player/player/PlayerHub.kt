@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.autotv.player.player

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import androidx.media3.common.AudioAttributes
import android.view.Surface
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.source.SingleSampleMediaSource
import com.autotv.player.data.Http
import com.autotv.player.data.Item
import com.autotv.player.data.Kind
import com.autotv.player.data.Prefs
import com.autotv.player.data.Store
import com.autotv.player.data.YouTube
import com.autotv.player.data.YtMedia
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

/** Uma opção de qualidade, áudio ou legenda para mostrar nos menus. */
data class Option(val id: String, val label: String, val selected: Boolean)

/**
 * Um único player para o celular e para o carro.
 * Tudo aqui deve ser chamado na thread principal.
 *
 * Dois modos de escolher qualidade/áudio/legenda:
 *  - modo "stream": vídeo do YouTube com streams separados (trocamos a fonte mantendo a posição);
 *  - modo "trilhas": lives (HLS) e canais M3U (usamos as trilhas do próprio ExoPlayer).
 */
object PlayerHub {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var retries = 0

    lateinit var player: ExoPlayer
        private set
    private lateinit var iptvFactory: DataSource.Factory
    private lateinit var webFactory: DataSource.Factory

    val nowPlaying = MutableStateFlow<Item?>(null)
    val status = MutableStateFlow<String?>(null)
    val isPlaying = MutableStateFlow(false)
    /** true enquanto o vídeo está sendo desenhado na tela do carro. */
    val carActive = MutableStateFlow(false)
    val isLive = MutableStateFlow(false)
    /** true enquanto o vídeo está sendo buscado ou carregando (para mostrar a rodinha). */
    val loading = MutableStateFlow(false)
    private var failed = false
    private var netJob: Job? = null

    /** true quando o player já está com o vídeo de [nowPlaying] carregado (a posição dele é confiável). */
    private var tracking = false
    private var saveJob: Job? = null
    /** Momento em que o sistema pausou sozinho (saída de áudio desconectada ou outro app tomou o áudio). */
    private var autoPausedAt = 0L
    private var lastNetwork: Network? = null

    val qualityOptions = MutableStateFlow<List<Option>>(emptyList())
    val audioOptions = MutableStateFlow<List<Option>>(emptyList())
    val subtitleOptions = MutableStateFlow<List<Option>>(emptyList())

    private var queue: List<Item> = emptyList()
    private var index = -1
    private var carSurface: Surface? = null

    private var yt: YtMedia? = null
    private var selVideo = -1
    private var selAudio = 0
    private var selSub = -1

    /** true = modo antigo (um stream fixo por vez). Com HLS ou DASH o player usa trilhas e tem "Automático". */
    private fun streamMode() = yt?.let { it.hls == null && it.dash == null } ?: false

    fun init(context: Context) {
        val app = context.applicationContext
        iptvFactory = DefaultDataSource.Factory(
            app,
            DefaultHttpDataSource.Factory()
                .setUserAgent(Http.UA_IPTV)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15_000)
                .setReadTimeoutMs(20_000)
        )
        webFactory = DefaultDataSource.Factory(
            app,
            DefaultHttpDataSource.Factory()
                .setUserAgent(Http.UA_WEB)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15_000)
                .setReadTimeoutMs(20_000)
        )
        // Se o decodificador principal (por hardware) falhar, tenta os outros do aparelho.
        val renderers = androidx.media3.exoplayer.DefaultRenderersFactory(app).setEnableDecoderFallback(true)
        player = ExoPlayer.Builder(app, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(iptvFactory))
            .setWakeMode(C.WAKE_MODE_NETWORK)
            // Pede o "foco de áudio": outros apps (Spotify etc.) pausam quando o AutoTV toca, e vice-versa.
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        watchNetwork(app)
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                this@PlayerHub.isPlaying.value = isPlaying
                // Enquanto toca, guarda a posição a cada poucos segundos; ao parar, guarda na hora.
                saveJob?.cancel()
                if (isPlaying) {
                    saveJob = scope.launch { while (true) { delay(SAVE_EVERY_MS); savePosition() } }
                } else savePosition()
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int
            ) {
                // O usuário avançou ou voltou (mesmo com o vídeo pausado): guarda a posição nova.
                if (reason == Player.DISCONTINUITY_REASON_SEEK) savePosition()
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (!playWhenReady && (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY ||
                        reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS)
                ) autoPausedAt = android.os.SystemClock.elapsedRealtime()
            }

            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_BUFFERING -> { status.value = "Carregando…"; loading.value = true }
                    Player.STATE_READY -> { status.value = null; retries = 0; failed = false; loading.value = false }
                    Player.STATE_ENDED -> {
                        loading.value = false
                        val ended = nowPlaying.value
                        // Visto até o fim: da próxima vez começa do início.
                        if (ended != null && tracking) Store.clearPosition(ended)
                        // Passa para o próximo ao terminar um vídeo do YouTube ou um arquivo do celular.
                        if ((ended?.kind == Kind.YOUTUBE && !isLive.value) || ended?.url?.startsWith("content://") == true) next()
                    }
                    else -> Unit
                }
            }

            override fun onTracksChanged(tracks: Tracks) { publishTrackOptions() }

            override fun onTrackSelectionParametersChanged(parameters: androidx.media3.common.TrackSelectionParameters) {
                publishTrackOptions()
            }

            override fun onPlayerError(error: PlaybackException) {
                val item = nowPlaying.value
                when {
                    item?.kind == Kind.STREAM && retries < 3 -> {
                        retries++
                        status.value = "Reconectando ($retries/3)…"
                        loading.value = true
                        scope.launch { delay(2_000); player.prepare() }
                    }
                    item?.kind == Kind.YOUTUBE && retries < 2 -> {
                        // O link do stream pode ter expirado: resolve de novo e continua de onde parou.
                        retries++
                        status.value = "Reconectando ($retries/2)…"
                        loading.value = true
                        resumeYoutube(item, player.currentPosition)
                    }
                    else -> fail("Erro: ${error.errorCodeName}")
                }
            }
        })
    }

    private fun fail(message: String) {
        status.value = message
        loading.value = false
        failed = true
    }

    /** Ao trocar de rede (Wi-Fi <-> dados móveis) ou a internet voltar, recarrega e continua de onde parou. */
    private fun watchNetwork(app: Context) {
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    scope.launch {
                        val changed = lastNetwork != null && lastNetwork != network
                        lastNetwork = network
                        if (changed || failed) reloadAfterNetworkChange()
                    }
                }
            })
        }
    }

    private fun reloadAfterNetworkChange() {
        val item = nowPlaying.value ?: return
        // Pausado pelo usuário: não mexe (se o link vencer, o player reconecta sozinho ao dar play).
        if (!player.playWhenReady && !failed) return
        netJob?.cancel()
        netJob = scope.launch {
            delay(1_500) // dá tempo de a rede nova estabilizar
            retries = 0
            failed = false
            status.value = "Reconectando…"
            loading.value = true
            val pos = player.currentPosition
            if (item.kind == Kind.YOUTUBE) {
                // Os links do YouTube valem só para a rede em que foram gerados: busca de novo.
                resumeYoutube(item, pos)
            } else {
                player.prepare()
                player.playWhenReady = true
            }
        }
    }

    fun pause() { player.pause() }

    /**
     * O Android Auto desconectou (cabo solto, carro desligado). Pausa e, se o vídeo estava tocando,
     * marca para voltar a tocar sozinho na próxima conexão.
     */
    fun onCarDisconnected() {
        val pausedBySystem = android.os.SystemClock.elapsedRealtime() - autoPausedAt < 15_000
        if (nowPlaying.value != null && (player.playWhenReady || pausedBySystem)) Prefs.resumeInCar = true
        player.pause()
        savePosition()
    }

    /**
     * O Android Auto conectou. Se o vídeo foi interrompido pela desconexão, volta a tocar de onde parou
     * e devolve true (para o carro abrir direto na tela do vídeo).
     */
    fun resumeInCar(): Boolean {
        if (!Prefs.resumeInCar) return false
        Prefs.resumeInCar = false
        val item = nowPlaying.value
        if (item == null) {
            // O sistema fechou o app nesse meio-tempo: recarrega a fila guardada.
            val (items, i) = Store.loadSession() ?: return false
            play(items, i)
            return true
        }
        retries = 0
        if (failed) {
            failed = false
            status.value = "Reconectando…"
            loading.value = true
            if (item.kind == Kind.YOUTUBE) resumeYoutube(item, player.currentPosition)
            else { player.prepare(); player.playWhenReady = true }
        } else player.play()
        return true
    }

    fun play(items: List<Item>, startIndex: Int) {
        if (startIndex !in items.indices) return
        queue = items
        index = startIndex
        start()
    }

    fun next() { if (index + 1 < queue.size) { index++; start() } }
    fun previous() { if (index > 0) { index--; start() } }
    fun hasNext() = index + 1 < queue.size
    fun hasPrevious() = index > 0

    fun togglePause() {
        if (player.isPlaying) player.pause() else player.play()
    }

    fun stop() {
        savePosition()
        tracking = false
        Prefs.resumeInCar = false
        Store.clearSession()
        job?.cancel()
        netJob?.cancel()
        player.stop()
        nowPlaying.value = null
        status.value = null
        loading.value = false
        failed = false
        yt = null
        clearOptions()
    }

    // ---- posição ----
    fun positionMs(): Long = player.currentPosition
    fun durationMs(): Long = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: -1L
    fun bufferedMs(): Long = player.bufferedPosition
    fun liveNow(): Boolean = isLive.value || player.isCurrentMediaItemLive
    fun seekTo(ms: Long) { player.seekTo(ms.coerceAtLeast(0)) }

    /**
     * Guarda onde o vídeo atual está, para continuar dali na próxima vez (em qualquer aba, e mesmo
     * depois de fechar o app). Não vale para transmissões ao vivo nem canais de TV, que não têm "onde parou".
     */
    private fun savePosition() {
        if (!tracking) return
        val item = nowPlaying.value ?: return
        if (liveNow()) return
        val dur = durationMs()
        if (dur <= 0) return
        val pos = player.currentPosition
        // Bem no começo ou já no finalzinho: não há o que retomar.
        if (pos < RESUME_MIN_MS || pos > dur - RESUME_END_MS) Store.clearPosition(item)
        else Store.savePosition(item, pos)
    }

    /** Posição de onde [item] deve começar (0 = do início). */
    private fun resumePosition(item: Item): Long {
        if (item.isLive) return 0L
        val pos = Store.positionOf(item)
        val known = item.durationSec * 1000
        return if (known > 0 && pos > known - RESUME_END_MS) 0L else pos
    }

    private const val SAVE_EVERY_MS = 5_000L
    private const val RESUME_MIN_MS = 5_000L
    private const val RESUME_END_MS = 10_000L

    /** Transmissão ao vivo em que dá para voltar no tempo (o YouTube guarda um trecho recente). */
    fun liveSeekable(): Boolean = liveNow() && player.isCurrentMediaItemSeekable && durationMs() > 30_000

    /** true quando o vídeo ao vivo está atrasado em relação ao "agora" (o usuário voltou no tempo). */
    fun behindLive(): Boolean = liveSeekable() && durationMs() - player.currentPosition > 40_000

    /** Volta para o ponto mais recente da transmissão. */
    fun seekToLive() {
        player.seekToDefaultPosition()
        player.play()
    }
    fun seekBy(deltaMs: Long) {
        val d = durationMs()
        var t = player.currentPosition + deltaMs
        t = if (d > 0) t.coerceIn(0, d) else t.coerceAtLeast(0)
        player.seekTo(t)
    }

    // ---- tela do carro ----
    fun setCarActive(active: Boolean) { carActive.value = active }

    /** Modo simples: o vídeo é desenhado direto na superfície do carro. */
    fun attachCarSurface(surface: Surface) {
        carSurface = surface
        player.setVideoSurface(surface)
        carActive.value = true
    }

    fun detachCarSurface() {
        carSurface?.let { player.clearVideoSurface(it) }
        carSurface = null
        carActive.value = false
    }

    // ---- escolhas de qualidade / áudio / legenda ----
    fun selectQuality(id: String) {
        if (streamMode()) {
            val m = yt ?: return
            val i = id.removePrefix("v:").toIntOrNull() ?: return
            if (i < 0 || i == selVideo || i !in m.videos.indices) return
            selVideo = i
            Prefs.preferredHeight = m.videos[i].height
            applyYt(keepPosition = true)
        } else {
            val params = player.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_VIDEO)
            if (id != "auto") {
                // id = "h:<altura>:<60 ou 0>". Entre os codecs com essa qualidade, usa o primeiro que o
                // aparelho suporta (a ordem do manifesto põe primeiro os que têm aceleração).
                val parts = id.split(":")
                val h = parts.getOrNull(1)?.toIntOrNull()
                val fast = parts.getOrNull(2) == "60"
                var fallback: TrackSelectionOverride? = null
                var chosen: TrackSelectionOverride? = null
                outer@ for (g in player.currentTracks.groups) {
                    if (g.type != C.TRACK_TYPE_VIDEO) continue
                    for (i in 0 until g.length) {
                        val f = g.getTrackFormat(i)
                        if (f.height != h || (f.frameRate >= 50f) != fast) continue
                        val o = TrackSelectionOverride(g.mediaTrackGroup, i)
                        if (g.isTrackSupported(i)) { chosen = o; break@outer }
                        if (fallback == null) fallback = o
                    }
                }
                (chosen ?: fallback)?.let { params.setOverrideForType(it) }
            }
            player.trackSelectionParameters = params.build()
        }
    }

    fun selectAudio(id: String) {
        if (streamMode()) {
            val m = yt ?: return
            val i = id.removePrefix("a:").toIntOrNull() ?: return
            if (i == selAudio || i !in m.audios.indices) return
            selAudio = i
            applyYt(keepPosition = true)
        } else {
            val parts = id.split(":")
            val g = parts.getOrNull(1)?.toIntOrNull() ?: return
            val t = parts.getOrNull(2)?.toIntOrNull() ?: return
            val group = player.currentTracks.groups.getOrNull(g) ?: return
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, t))
                .build()
        }
    }

    fun selectSubtitle(id: String) {
        if (streamMode()) {
            val m = yt ?: return
            if (id == "off") {
                if (selSub == -1) return
                selSub = -1
                Prefs.subtitleLang = ""
            } else {
                val i = id.removePrefix("s:").toIntOrNull() ?: return
                if (i == selSub || i !in m.subs.indices) return
                selSub = i
                Prefs.subtitleLang = m.subs[i].lang
            }
            applyYt(keepPosition = true)
        } else {
            val builder = player.trackSelectionParameters.buildUpon()
            val remember = yt?.dash != null
            if (id == "off") {
                builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                if (remember) Prefs.subtitleLang = ""
            } else {
                val parts = id.split(":")
                val g = parts.getOrNull(1)?.toIntOrNull() ?: return
                val t = parts.getOrNull(2)?.toIntOrNull() ?: return
                val group = player.currentTracks.groups.getOrNull(g) ?: return
                builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, t))
                if (remember) Prefs.subtitleLang = group.getTrackFormat(t).language.orEmpty()
            }
            player.trackSelectionParameters = builder.build()
        }
    }

    // ---- interno ----
    private fun clearOptions() {
        qualityOptions.value = emptyList()
        audioOptions.value = emptyList()
        subtitleOptions.value = emptyList()
    }

    private fun setTextDisabled(disabled: Boolean) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, disabled)
            .build()
    }

    private fun start() {
        val item = queue.getOrNull(index) ?: return
        // Antes de trocar, guarda onde o vídeo anterior parou.
        savePosition()
        tracking = false
        Prefs.resumeInCar = false
        job?.cancel()
        netJob?.cancel()
        retries = 0
        failed = false
        nowPlaying.value = item
        if (item.kind == Kind.YOUTUBE) Store.addHistory(item)
        Store.saveSession(queue, index)
        status.value = "Carregando…"
        loading.value = true
        yt = null
        isLive.value = false
        clearOptions()
        // Limpa escolhas de trilhas do vídeo anterior; legendas começam desligadas nas trilhas HLS/M3U.
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverrides()
            .setMaxVideoSize(Int.MAX_VALUE, Int.MAX_VALUE)
            .setPreferredTextLanguage(null)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
        job = scope.launch {
            try {
                when (item.kind) {
                    Kind.STREAM -> {
                        val source = DefaultMediaSourceFactory(iptvFactory).createMediaSource(MediaItem.fromUri(item.url))
                        // Arquivo do celular ou filme da lista: continua de onde parou. Canal ao vivo não guarda posição.
                        val from = resumePosition(item)
                        if (from > 0) player.setMediaSource(source, from) else player.setMediaSource(source)
                        tracking = true
                        player.prepare()
                        player.playWhenReady = true
                    }
                    Kind.YOUTUBE -> {
                        val m = YouTube.resolveAsync(item.url)
                        setupYoutube(m)
                        applyYt(keepPosition = false, startMs = if (m.isLive) 0L else resumePosition(item))
                        tracking = true
                        player.playWhenReady = true
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail("Erro: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    private fun setupYoutube(m: YtMedia) {
        yt = m
        isLive.value = m.isLive
        selVideo = YouTube.defaultVideoIndex(m.videos, Prefs.preferredHeight)
        selAudio = 0
        val lang = Prefs.subtitleLang
        selSub = if (lang.isBlank()) -1 else {
            val manual = m.subs.indexOfFirst { it.lang == lang && !it.auto }
            if (manual >= 0) manual else m.subs.indexOfFirst { it.lang == lang }
        }
    }

    private fun resumeYoutube(item: Item, positionMs: Long) {
        job?.cancel()
        job = scope.launch {
            try {
                val m = YouTube.resolveAsync(item.url)
                val oldVideoHeight = yt?.videos?.getOrNull(selVideo)?.height
                setupYoutube(m)
                if (oldVideoHeight != null) {
                    val i = m.videos.indexOfFirst { it.height == oldVideoHeight }
                    if (i >= 0) selVideo = i
                }
                applyYt(keepPosition = false, startMs = if (m.isLive) 0L else positionMs)
                player.playWhenReady = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail("Erro: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    private fun applyYt(keepPosition: Boolean, startMs: Long = 0L) {
        val m = yt ?: return
        val pos = if (keepPosition) player.currentPosition else startMs
        val wasPlaying = if (keepPosition) player.playWhenReady else true

        val source: MediaSource
        if (m.hls != null) {
            source = HlsMediaSource.Factory(webFactory).createMediaSource(MediaItem.fromUri(m.hls))
        } else if (m.dash != null) {
            // Vídeo gravado: manifesto DASH montado pelo app, entregue ao player como um endereço "data:".
            val b64 = android.util.Base64.encodeToString(m.dash.toByteArray(), android.util.Base64.NO_WRAP)
            val item = MediaItem.Builder()
                .setUri("data:application/dash+xml;base64,$b64")
                .setMimeType(MimeTypes.APPLICATION_MPD)
                .build()
            source = DashMediaSource.Factory(webFactory).createMediaSource(item)
            // Legenda: volta no idioma escolhido da última vez (se houver).
            val lang = Prefs.subtitleLang
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, lang.isBlank())
                .setPreferredTextLanguage(lang.ifBlank { null })
                .build()
        } else {
            val pf = ProgressiveMediaSource.Factory(webFactory)
            val sources = ArrayList<MediaSource>()
            val v = m.videos.getOrNull(selVideo)
            if (v != null) {
                sources += pf.createMediaSource(MediaItem.fromUri(v.url))
                m.audios.getOrNull(selAudio)?.let { sources += pf.createMediaSource(MediaItem.fromUri(it.url)) }
            } else {
                sources += pf.createMediaSource(MediaItem.fromUri(m.muxed ?: error("sem stream")))
            }
            m.subs.getOrNull(selSub)?.let { s ->
                val cfg = MediaItem.SubtitleConfiguration.Builder(Uri.parse(s.url))
                    .setMimeType(s.mime)
                    .setLanguage(s.lang)
                    .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                    .build()
                sources += SingleSampleMediaSource.Factory(webFactory).createMediaSource(cfg, C.TIME_UNSET)
            }
            source = if (sources.size == 1) sources[0] else MergingMediaSource(*sources.toTypedArray())
        }
        if (streamMode()) setTextDisabled(selSub < 0)
        player.setMediaSource(source, pos)
        player.prepare()
        player.playWhenReady = wasPlaying
        if (streamMode()) publishStreamOptions()
    }

    private fun publishStreamOptions() {
        val m = yt ?: return
        qualityOptions.value =
            if (m.videos.isEmpty()) listOf(Option("v:-1", "Padrão", true))
            else m.videos.mapIndexed { i, v -> Option("v:$i", v.label, i == selVideo) }
        audioOptions.value = m.audios.mapIndexed { i, a -> Option("a:$i", a.label, i == selAudio) }
        subtitleOptions.value =
            listOf(Option("off", "Desativadas", selSub < 0)) +
                m.subs.mapIndexed { i, s -> Option("s:$i", s.label, i == selSub) }
    }

    /** Opções a partir das trilhas do ExoPlayer (lives HLS e canais M3U). */
    private fun publishTrackOptions() {
        if (streamMode()) return
        val groups = player.currentTracks.groups
        val params = player.trackSelectionParameters
        val ptBr = Locale("pt", "BR")

        // Qualidade
        var hasVideoOverride = false
        var selectedKey = -1
        // chave = altura * 2 (+1 se for 50/60 quadros por segundo); só entram qualidades que o aparelho decodifica
        val keys = sortedSetOf<Int>()
        val unsupported = sortedSetOf<Int>()
        for (g in groups) {
            if (g.type != C.TRACK_TYPE_VIDEO) continue
            if (params.overrides.containsKey(g.mediaTrackGroup)) hasVideoOverride = true
            for (i in 0 until g.length) {
                val f = g.getTrackFormat(i)
                if (f.height <= 0) continue
                val key = f.height * 2 + if (f.frameRate >= 50f) 1 else 0
                if (g.isTrackSupported(i)) keys += key else unsupported += key
                if (g.isTrackSelected(i)) selectedKey = key
            }
        }
        // Mostra todas as qualidades que o vídeo tem; as que o aparelho diz não decodificar vêm marcadas.
        val all = sortedSetOf<Int>().apply { addAll(keys); addAll(unsupported) }
        qualityOptions.value =
            if (all.isEmpty()) emptyList()
            else listOf(Option("auto", "Automático", !hasVideoOverride)) +
                all.reversed().map { key ->
                    val h = key / 2
                    val fast = key % 2 == 1
                    val name = if (fast) "${h}p60" else "${h}p"
                    val label = if (key in keys || keys.isEmpty()) name else "$name (pode travar neste aparelho)"
                    Option("h:$h:${if (fast) 60 else 0}", label, hasVideoOverride && key == selectedKey)
                }

        // Áudio
        val audio = ArrayList<Option>()
        for ((gi, g) in groups.withIndex()) {
            if (g.type != C.TRACK_TYPE_AUDIO) continue
            for (i in 0 until g.length) {
                val f = g.getTrackFormat(i)
                val label = f.label?.takeIf { it.isNotBlank() }
                    ?: f.language?.takeIf { it != "und" }?.let { Locale.forLanguageTag(it).getDisplayLanguage(ptBr) }
                        ?.replaceFirstChar { it.uppercase() }
                    ?: "Faixa ${audio.size + 1}"
                audio += Option("a:$gi:$i", label, g.isTrackSelected(i))
            }
        }
        // O mesmo idioma pode vir repetido (uma cópia por taxa de bits): mostra um só, o que está em uso.
        val unique = audio.groupBy { it.label }.map { (_, same) -> same.firstOrNull { it.selected } ?: same.first() }
        audioOptions.value = if (unique.size > 1) unique else emptyList()

        // Legendas
        val textDisabled = params.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
        val subs = ArrayList<Option>()
        for ((gi, g) in groups.withIndex()) {
            if (g.type != C.TRACK_TYPE_TEXT) continue
            for (i in 0 until g.length) {
                val f = g.getTrackFormat(i)
                val label = f.label?.takeIf { it.isNotBlank() }
                    ?: f.language?.takeIf { it != "und" }?.let { Locale.forLanguageTag(it).getDisplayLanguage(ptBr) }
                        ?.replaceFirstChar { it.uppercase() }
                    ?: "Legenda ${subs.size + 1}"
                subs += Option("t:$gi:$i", label, !textDisabled && g.isTrackSelected(i))
            }
        }
        subtitleOptions.value = if (subs.isEmpty()) emptyList() else listOf(Option("off", "Desativadas", textDisabled)) + subs
    }
}
