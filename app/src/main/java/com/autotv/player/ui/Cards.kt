package com.autotv.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.autotv.player.data.Item
import com.autotv.player.data.Kind
import com.autotv.player.data.formatDuration

@Composable
private fun Badge(text: String, background: Color, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(background)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        color = Color.White,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold
    )
}

/** Cartão de vídeo no estilo do YouTube: miniatura grande, título, canal e favorito. */
@Composable
fun VideoCard(item: Item, isFavorite: Boolean, onClick: () -> Unit, onFavorite: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(bottom = 12.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color(0xFF222222))) {
            if (item.thumb != null) {
                AsyncImage(
                    model = item.thumb, contentDescription = null,
                    modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop
                )
            }
            when {
                item.isLive -> Badge("AO VIVO", Color(0xFFCC0000), Modifier.align(Alignment.BottomEnd).padding(8.dp))
                item.durationSec > 0 -> Badge(formatDuration(item.durationSec), Color(0xCC000000), Modifier.align(Alignment.BottomEnd).padding(8.dp))
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, top = 8.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                item.group?.let {
                    Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
            }
            IconButton(onClick = onFavorite) {
                Icon(if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, "Favorito")
            }
        }
    }
}

/** Linha compacta (canais de TV, com logo quando a lista tem). */
@Composable
fun ChannelRow(item: Item, isFavorite: Boolean, onClick: () -> Unit, onFavorite: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (item.thumb != null) {
            AsyncImage(
                model = item.thumb, contentDescription = null,
                modifier = Modifier.width(56.dp).height(36.dp), contentScale = ContentScale.Fit
            )
            Box(Modifier.size(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
            item.group?.let {
                Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
        }
        IconButton(onClick = onFavorite) {
            Icon(if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, "Favorito")
        }
    }
}

/** Lista que usa o cartão grande para vídeos do YouTube e a linha compacta para TV. */
@Composable
fun MediaList(vm: MainViewModel, items: List<Item>, modifier: Modifier = Modifier, header: (@Composable () -> Unit)? = null) {
    val favs by vm.favorites.collectAsState()
    val favIds = remember(favs) { favs.map { it.id }.toSet() }
    LazyColumn(modifier.fillMaxSize()) {
        if (header != null) item { header() }
        itemsIndexed(items, key = { i, it -> "${it.id}#$i" }) { index, item ->
            if (item.kind == Kind.YOUTUBE) {
                VideoCard(item, item.id in favIds, { vm.play(items, index) }, { vm.toggleFavorite(item) })
            } else {
                ChannelRow(item, item.id in favIds, { vm.play(items, index) }, { vm.toggleFavorite(item) })
            }
        }
    }
}
