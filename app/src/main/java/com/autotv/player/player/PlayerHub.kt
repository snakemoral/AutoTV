@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.autotv.player.player

import android.content.Context
import android.view.Surface
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.autotv.player.data.Http
import com.autotv.player.data.Item
import com.autotv.player.data.Kind
import com.autotv.player.data.ResolvedMedia
import com.autotv.player.data.YouTube
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Um único player para o celular e para o carro.
 * Tudo aqui deve ser chamado na thread principal.
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

    private var queue: List<Item> = emptyList()
    private var index = -1
    private var carSurface: Surface? = null

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
        player = ExoPlayer.Builder(app)
            .setMediaSourceFactory(DefaultMediaSourceFactory(iptvFactory))
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                this@PlayerHub.isPlaying.value = isPlaying
            }

            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_BUFFERING -> status.value = "Carregando…"
                    Player.STATE_READY -> { status.value = null; retries = 0 }
                    Player.STATE_ENDED -> if (nowPlaying.value?.kind == Kind.YOUTUBE) next()
                    else -> Unit
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                val item = nowPlaying.value
                if (item?.kind == Kind.STREAM && retries < 3) {
                    retries++
                    status.value = "Reconectando ($retries/3)…"
                    scope.launch { delay(2_000); player.prepare() }
                } else {
                    status.value = "Erro: ${error.errorCodeName}"
                }
            }
        })
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
        job?.cancel()
        player.stop()
        nowPlaying.value = null
        status.value = null
    }

    // ---- tela do carro ----
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

    // ---- interno ----
    private fun start() {
        val item = queue.getOrNull(index) ?: return
        job?.cancel()
        retries = 0
        nowPlaying.value = item
        status.value = "Carregando…"
        job = scope.launch {
            try {
                val source: MediaSource = when (item.kind) {
                    Kind.STREAM ->
                        DefaultMediaSourceFactory(iptvFactory).createMediaSource(MediaItem.fromUri(item.url))
                    Kind.YOUTUBE -> {
                        val resolved = withContext(Dispatchers.IO) { YouTube.resolve(item.url) }
                        buildYoutubeSource(resolved)
                    }
                }
                player.setMediaSource(source)
                player.prepare()
                player.playWhenReady = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status.value = "Erro: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    private fun buildYoutubeSource(r: ResolvedMedia): MediaSource {
        if (r.isHls) {
            return HlsMediaSource.Factory(webFactory).createMediaSource(MediaItem.fromUri(r.video))
        }
        val factory = ProgressiveMediaSource.Factory(webFactory)
        val video = factory.createMediaSource(MediaItem.fromUri(r.video))
        val audioUrl = r.audio ?: return video
        return MergingMediaSource(video, factory.createMediaSource(MediaItem.fromUri(audioUrl)))
    }
}
