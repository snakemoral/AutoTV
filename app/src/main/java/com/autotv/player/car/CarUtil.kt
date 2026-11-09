package com.autotv.player.car

import androidx.car.app.Screen
import com.autotv.player.data.Item
import com.autotv.player.data.formatDuration
import com.autotv.player.player.PlayerHub

fun itemSubtitle(item: Item): String? {
    val parts = ArrayList<String>()
    if (item.isLive) parts += "AO VIVO"
    else if (item.durationSec > 0) parts += formatDuration(item.durationSec)
    item.group?.let { parts += it }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" • ")
}

/** Toca [items] a partir de [index] e abre a tela de vídeo. */
fun Screen.playAndOpen(items: List<Item>, index: Int) {
    PlayerHub.play(items, index)
    screenManager.push(PlayerScreen(carContext))
}

fun itemEntries(items: List<Item>): List<Entry> =
    items.mapIndexed { i, item -> Entry(item.title, itemSubtitle(item), item.thumb) { it.playAndOpen(items, i) } }
