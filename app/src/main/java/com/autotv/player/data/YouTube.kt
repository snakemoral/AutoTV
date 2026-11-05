package com.autotv.player.data

import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
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

/** Fontes já resolvidas, prontas para o ExoPlayer. */
data class ResolvedMedia(val video: String, val audio: String?, val isHls: Boolean)

/**
 * YouTube via NewPipeExtractor. Tudo aqui é BLOQUEANTE (rede): chame em Dispatchers.IO.
 * O YouTube muda com frequência; se parar de funcionar, atualize a versão do NewPipeExtractor.
 */
object YouTube {
    private const val MAX_HEIGHT = 1080

    fun init(client: OkHttpClient) {
        NewPipe.init(NewPipeDownloader(client), Localization("pt", "BR"), ContentCountry("BR"))
    }

    fun looksLikeLink(text: String): Boolean {
        val t = text.trim().lowercase()
        return (t.startsWith("http://") || t.startsWith("https://")) && (t.contains("youtube.") || t.contains("youtu.be"))
    }

    fun isPlaylistLink(url: String) = url.contains("list=") && !url.contains("watch?v=") && !url.contains("youtu.be/")

    fun search(query: String): List<Item> {
        val extractor = ServiceList.YouTube.getSearchExtractor(query)
        extractor.fetchPage()
        return extractor.initialPage.items
            .filterIsInstance<StreamInfoItem>()
            .map { youtubeItem(it.name, it.url, it.uploaderName) }
    }

    fun itemFromLink(url: String): Item {
        val info = StreamInfo.getInfo(url.trim())
        return youtubeItem(info.name, info.url, info.uploaderName)
    }

    /** Importa os vídeos da primeira página de uma playlist do YouTube. */
    fun loadPlaylist(url: String): Pair<String, List<Item>> {
        val info = PlaylistInfo.getInfo(url.trim())
        val items = info.relatedItems.map { youtubeItem(it.name, it.url, it.uploaderName) }
        return info.name to items
    }

    fun resolve(url: String): ResolvedMedia {
        val info = StreamInfo.getInfo(url)

        // Transmissão ao vivo
        info.hlsUrl?.takeIf { it.isNotBlank() }?.let { return ResolvedMedia(it, null, true) }

        // Melhor qualidade: vídeo e áudio separados, unidos pelo player
        val videoOnly = info.videoOnlyStreams
            .filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.isUrl && it.height in 1..MAX_HEIGHT }
            .sortedWith(compareBy({ it.height }, { it.format?.suffix == "mp4" }))
            .lastOrNull()
        val audio = info.audioStreams
            .filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.isUrl }
            .sortedWith(compareBy({ it.format?.suffix == "m4a" }, { it.averageBitrate }))
            .lastOrNull()

        // Vídeo com áudio embutido (geralmente até 360p)
        val muxed = info.videoStreams
            .filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.isUrl }
            .maxByOrNull { it.height }

        if (videoOnly != null && audio != null && (muxed == null || videoOnly.height > muxed.height)) {
            return ResolvedMedia(videoOnly.content, audio.content, false)
        }
        if (muxed != null) return ResolvedMedia(muxed.content, null, false)

        error("Nenhum stream reproduzível encontrado para este vídeo")
    }
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
