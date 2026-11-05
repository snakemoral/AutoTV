package com.autotv.player.data

enum class Kind { STREAM, YOUTUBE }

/** Um canal de TV (stream) ou um vídeo/live do YouTube. */
data class Item(
    val id: String,
    val title: String,
    val url: String,
    val group: String?,
    val kind: Kind
)

data class Playlist(
    val id: String,
    val name: String,
    /** URL de origem (para atualizar). Vazio se veio de um arquivo local. */
    val source: String,
    val items: List<Item>
)

fun streamItem(title: String, url: String, group: String?) =
    Item("s${url.hashCode()}", title, url, group?.takeIf { it.isNotBlank() }, Kind.STREAM)

fun youtubeItem(title: String, url: String, uploader: String?) =
    Item("y$url", title, url, uploader?.takeIf { it.isNotBlank() }, Kind.YOUTUBE)
