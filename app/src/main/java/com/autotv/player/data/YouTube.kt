package com.autotv.player.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import java.util.Locale

data class VideoOpt(val label: String, val height: Int, val url: String)
data class AudioOpt(val label: String, val url: String)
data class SubOpt(val label: String, val lang: String, val auto: Boolean, val url: String, val mime: String)

/** Tudo que o player precisa para tocar um vídeo/live do YouTube e trocar qualidade/áudio/legenda. */
data class YtMedia(
    val title: String,
    val isLive: Boolean,
    /** Manifesto HLS (lives). Se presente, o player escolhe qualidade/áudio/legenda pelas trilhas HLS. */
    val hls: String?,
    /** Vídeo com áudio embutido (usado se não houver vídeo separado). */
    val muxed: String?,
    val videos: List<VideoOpt>,
    val audios: List<AudioOpt>,
    val subs: List<SubOpt>,
    val durationSec: Long
)

data class Category(val id: String, val title: String, val kiosk: String? = null, val query: String? = null)

/**
 * YouTube via NewPipeExtractor. As funções não-suspend são BLOQUEANTES (rede): chame em Dispatchers.IO.
 * O YouTube muda com frequência; se parar de funcionar, atualize a versão do NewPipeExtractor.
 */
object YouTube {
    private const val MAX_DEFAULT_HEIGHT = 1080

    val CATEGORIES = listOf(
        Category("live", "Ao vivo agora", kiosk = "live"),
        Category("music", "Música", kiosk = "trending_music"),
        Category("gaming", "Jogos", kiosk = "trending_gaming"),
        Category("movies", "Filmes e séries", kiosk = "trending_movies_and_shows"),
        Category("podcasts", "Podcasts", kiosk = "trending_podcasts_episodes"),
        Category("news", "Notícias", query = "notícias ao vivo hoje"),
        Category("sports", "Esportes", query = "esportes ao vivo")
    )

    private val cache = HashMap<String, Pair<Long, List<Item>>>()
    private const val CACHE_MS = 10 * 60 * 1000L

    fun init(client: OkHttpClient) {
        NewPipe.init(NewPipeDownloader(client), Localization("pt", "BR"), ContentCountry("BR"))
    }

    fun looksLikeLink(text: String): Boolean {
        val t = text.trim().lowercase()
        return (t.startsWith("http://") || t.startsWith("https://")) && (t.contains("youtube.") || t.contains("youtu.be"))
    }

    fun isPlaylistLink(url: String) = url.contains("list=") && !url.contains("watch?v=") && !url.contains("youtu.be/")

    private fun bestThumb(images: List<Image>?): String? {
        if (images.isNullOrEmpty()) return null
        val sorted = images.sortedBy { it.width }
        return (sorted.firstOrNull { it.width >= 300 } ?: sorted.last()).url
    }

    private fun toItem(i: StreamInfoItem): Item {
        val live = i.streamType == StreamType.LIVE_STREAM || i.streamType == StreamType.AUDIO_LIVE_STREAM
        return youtubeItem(i.name, i.url, i.uploaderName, bestThumb(i.thumbnails), live, if (live) -1 else i.duration, i.uploaderUrl)
    }

    fun search(query: String): List<Item> {
        val extractor = ServiceList.YouTube.getSearchExtractor(query)
        extractor.fetchPage()
        return extractor.initialPage.items.filterIsInstance<StreamInfoItem>().map { toItem(it) }
    }

    /** Carrega uma categoria da home (com cache de 10 min). */
    fun loadCategory(c: Category): List<Item> {
        synchronized(cache) {
            cache[c.id]?.let { (t, items) -> if (System.currentTimeMillis() - t < CACHE_MS) return items }
        }
        val items = when {
            c.kiosk != null -> {
                val ex = ServiceList.YouTube.kioskList.getExtractorById(c.kiosk, null)
                ex.fetchPage()
                ex.initialPage.items.filterIsInstance<StreamInfoItem>().map { toItem(it) }
            }
            c.query != null -> search(c.query)
            else -> emptyList()
        }
        synchronized(cache) { cache[c.id] = System.currentTimeMillis() to items }
        return items
    }

    /** Várias categorias em paralelo; categorias que falharem ficam de fora. */
    suspend fun loadSections(cats: List<Category> = CATEGORIES): List<Pair<Category, List<Item>>> = coroutineScope {
        cats.map { c ->
            async(Dispatchers.IO) { runCatching { c to loadCategory(c) }.getOrNull() }
        }.awaitAll().filterNotNull().filter { it.second.isNotEmpty() }
    }

    /** "Home": mistura os primeiros itens de cada categoria, como um feed. */
    suspend fun homeFeed(): List<Item> {
        val sections = loadSections(CATEGORIES.take(4))
        val out = ArrayList<Item>()
        val seen = HashSet<String>()
        var round = 0
        while (round < 8) {
            var any = false
            for ((_, items) in sections) {
                items.getOrNull(round)?.let { if (seen.add(it.id)) { out += it; any = true } }
            }
            if (!any && round > 0) break
            round++
        }
        return out
    }

    /** Um quiosque do YouTube pelo id (sem diferenciar maiúsculas): "Trending", "live", "trending_music"... */
    fun kiosk(id: String): List<Item> {
        val list = ServiceList.YouTube.kioskList
        val real = list.availableKiosks.firstOrNull { it.equals(id, ignoreCase = true) } ?: id
        val ex = list.getExtractorById(real, null)
        ex.fetchPage()
        return ex.initialPage.items.filterIsInstance<StreamInfoItem>().map { toItem(it) }
    }

    /** Últimos vídeos de um canal (aba "Vídeos"). */
    private fun latestOf(ch: Channel): List<Pair<Item, Long>> {
        val info = ChannelInfo.getInfo(ch.url)
        val tab = info.tabs.firstOrNull { it.contentFilters.contains(ChannelTabs.VIDEOS) } ?: return emptyList()
        val tabInfo = ChannelTabInfo.getInfo(ServiceList.YouTube, tab)
        return tabInfo.relatedItems.filterIsInstance<StreamInfoItem>().take(6).map {
            toItem(it) to (it.uploadDate?.instant?.toEpochMilli() ?: 0L)
        }
    }

    /** Novidades dos canais seguidos, mais recentes primeiro. BLOQUEANTE (chame em Dispatchers.IO). */
    fun followingFeed(channels: List<Channel>): List<Item> = runBlocking(Dispatchers.IO) {
        channels.take(20)
            .map { ch -> async { runCatching { latestOf(ch) }.getOrDefault(emptyList()) } }
            .awaitAll()
            .flatten()
            .sortedByDescending { it.second }
            .map { it.first }
            .distinctBy { it.id }
    }

    fun itemFromLink(url: String): Item {
        val info = StreamInfo.getInfo(url.trim())
        val live = info.streamType == StreamType.LIVE_STREAM || info.streamType == StreamType.AUDIO_LIVE_STREAM
        return youtubeItem(info.name, info.url, info.uploaderName, bestThumb(info.thumbnails), live, if (live) -1 else info.duration, info.uploaderUrl)
    }

    /** Importa os vídeos da primeira página de uma playlist do YouTube. */
    fun loadPlaylist(url: String): Pair<String, List<Item>> {
        val info = PlaylistInfo.getInfo(url.trim())
        return info.name to info.relatedItems.map { toItem(it) }
    }

    fun resolve(url: String): YtMedia {
        val info = StreamInfo.getInfo(url)
        val hls = info.hlsUrl?.takeIf { it.isNotBlank() }
        val live = hls != null && (info.streamType == StreamType.LIVE_STREAM || info.streamType == StreamType.AUDIO_LIVE_STREAM)

        // Lives (e vídeos que só têm HLS): o ExoPlayer escolhe as trilhas.
        val videoOnly = info.videoOnlyStreams
            .filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.isUrl && it.height > 0 }
        val videos = videoOnly.groupBy { it.height }.map { (h, list) ->
            // Prefere MP4 (H.264, decodificação por hardware) e, empatando, a variante de maior fps.
            val best = list.sortedWith(compareBy({ it.format == MediaFormat.MPEG_4 }, { it.resolution.length })).last()
            VideoOpt(best.resolution, h, best.content)
        }.sortedByDescending { it.height }

        val audioStreams = info.audioStreams
            .filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.isUrl }
        val ptBr = Locale("pt", "BR")
        val audios = audioStreams
            .groupBy { "${it.audioLocale?.toLanguageTag().orEmpty()}|${it.audioTrackType?.name.orEmpty()}|${it.audioTrackName.orEmpty()}" }
            .values
            .sortedBy { g -> if (g.first().audioTrackType?.name == "ORIGINAL") 0 else 1 }
            .map { g ->
                val best = g.sortedWith(compareBy({ it.format == MediaFormat.M4A }, { it.averageBitrate })).last()
                val name = best.audioTrackName?.takeIf { it.isNotBlank() }
                    ?: best.audioLocale?.getDisplayLanguage(ptBr)?.replaceFirstChar { it.uppercase() }
                    ?: "Padrão"
                val label = if (best.audioTrackType?.name == "ORIGINAL" && !name.contains("original", true)) "$name (original)" else name
                AudioOpt(label, best.content)
            }

        val subs = info.subtitles.filter { it.isUrl }.mapNotNull { s ->
            val (mime, prio) = when (s.format) {
                MediaFormat.VTT -> "text/vtt" to 0
                MediaFormat.TTML -> "application/ttml+xml" to 1
                MediaFormat.SRT -> "application/x-subrip" to 2
                else -> return@mapNotNull null
            }
            Triple(SubOpt(
                label = s.displayLanguageName + if (s.isAutoGenerated) " (automática)" else "",
                lang = s.languageTag, auto = s.isAutoGenerated, url = s.content, mime = mime
            ), prio, 0)
        }.groupBy { it.first.lang to it.first.auto }
            .map { (_, g) -> g.minByOrNull { it.second }!!.first }
            .sortedWith(compareBy({ it.auto }, { it.label }))

        val muxed = info.videoStreams
            .filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.isUrl }
            .maxByOrNull { it.height }?.content

        if (hls == null && videos.isEmpty() && muxed == null) error("Nenhum stream reproduzível encontrado para este vídeo")
        return YtMedia(info.name, live, if (live || (videos.isEmpty() && muxed == null)) hls else null, muxed, videos, audios, subs, info.duration)
    }

    /** Índice da qualidade padrão: a maior que não passe da preferida (ou de 1080p). */
    fun defaultVideoIndex(videos: List<VideoOpt>, preferredHeight: Int): Int {
        if (videos.isEmpty()) return -1
        val cap = if (preferredHeight > 0) preferredHeight else MAX_DEFAULT_HEIGHT
        val i = videos.indexOfFirst { it.height <= cap }
        return if (i >= 0) i else videos.lastIndex
    }

    suspend fun resolveAsync(url: String): YtMedia = withContext(Dispatchers.IO) { resolve(url) }
}

/** Implementação do Downloader do NewPipeExtractor usando OkHttp. */
class NewPipeDownloader(private val client: OkHttpClient) : Downloader() {
    override fun execute(request: Request): Response {
        val body = request.dataToSend()?.let { it.toRequestBody(null, 0, it.size) }
        val builder = okhttp3.Request.Builder()
            .method(request.httpMethod(), body)
            .url(request.url())
            .addHeader("User-Agent", Http.UA_WEB)

        for ((name, values) in request.headers()) {
            builder.removeHeader(name)
            for (v in values) builder.addHeader(name, v)
        }

        client.newCall(builder.build()).execute().use { resp ->
            if (resp.code == 429) throw ReCaptchaException("reCaptcha solicitado", request.url())
            return Response(
                resp.code,
                resp.message,
                resp.headers.toMultimap(),
                resp.body?.string(),
                resp.request.url.toString()
            )
        }
    }
}
