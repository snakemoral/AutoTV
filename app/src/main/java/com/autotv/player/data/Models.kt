package com.autotv.player.data

enum class Kind { STREAM, YOUTUBE }

/** Um canal de TV (stream) ou um vídeo/live do YouTube. */
data class Item(
    val id: String,
    val title: String,
    val url: String,
    /** Categoria (M3U) ou nome do canal (YouTube). */
    val group: String?,
    val kind: Kind,
    /** URL da miniatura (YouTube) ou do logo do canal (M3U). */
    val thumb: String? = null,
    val isLive: Boolean = false,
    /** Duração em segundos (0 ou negativo = desconhecida / ao vivo). */
    val durationSec: Long = 0,
    /** Canal do YouTube que publicou o vídeo (para seguir). */
    val channelUrl: String? = null
) {
    /** Atalho de compatibilidade. */
    val live: Boolean get() = isLive
}

/** Um vídeo do histórico da conta do YouTube e quanto dele já foi visto (0 a 100; -1 = não informado). */
class Watched(val item: Item, val percent: Int)

/** Canal do YouTube seguido. */
data class Channel(val url: String, val name: String)

data class Playlist(
    val id: String,
    val name: String,
    /** URL de origem (para atualizar). Vazio se veio de um arquivo local. */
    val source: String,
    val items: List<Item>
)

fun streamItem(title: String, url: String, group: String?, logo: String? = null) =
    Item("s${url.hashCode()}", title, url, group?.takeIf { it.isNotBlank() }, Kind.STREAM, logo?.takeIf { it.isNotBlank() })

fun youtubeItem(
    title: String,
    url: String,
    uploader: String?,
    thumb: String? = null,
    live: Boolean = false,
    durationSec: Long = 0,
    channelUrl: String? = null
) = Item("y$url", title, url, uploader?.takeIf { it.isNotBlank() }, Kind.YOUTUBE, thumb, live, durationSec, channelUrl)

/** Informações do vídeo do YouTube mostradas junto do player (canal, descrição, contagens). */
data class VideoDetails(
    val channelName: String?,
    val channelUrl: String?,
    /** Foto do canal. */
    val avatar: String?,
    val description: String,
    /** Contagens; -1 = o YouTube não informou. */
    val likes: Long,
    val views: Long,
    val subscribers: Long,
    /** Data de publicação já formatada, ou null. */
    val uploaded: String?
)

/** "950", "12,3 mil", "1,2 mi", "3 bi". Vazio se o número é desconhecido (negativo). */
fun compactCount(n: Long): String {
    fun short(v: Double): String {
        val text = if (v >= 100) "%.0f".format(java.util.Locale.US, v) else "%.1f".format(java.util.Locale.US, v)
        return text.removeSuffix(".0").replace('.', ',')
    }
    return when {
        n < 0 -> ""
        n < 1_000 -> n.toString()
        n < 1_000_000 -> short(n / 1_000.0) + " mil"
        n < 1_000_000_000 -> short(n / 1_000_000.0) + " mi"
        else -> short(n / 1_000_000_000.0) + " bi"
    }
}

/** "3:05" ou "1:02:03". Valores negativos viram "0:00". */
fun formatDuration(sec: Long): String {
    val t = if (sec < 0) 0 else sec
    val h = t / 3600
    val m = (t % 3600) / 60
    val s = t % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
