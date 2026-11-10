package com.autotv.player.ui

import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.autotv.player.data.Item
import com.autotv.player.player.Option
import com.autotv.player.player.PlayerHub

private const val MIN_BRIGHTNESS = 0.15f

/**
 * Vídeo + controles do player (barra de progresso, play/pause, anterior/próximo, tela cheia),
 * mais: título e engrenagem (qualidade/áudio/legendas) e barra de brilho no lado direito.
 * Tudo isso some junto com os controles.
 */
@OptIn(UnstableApi::class)
@Composable
fun PlayerSurface(
    item: Item,
    fullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    modifier: Modifier = Modifier
) {
    val carActive by PlayerHub.carActive.collectAsState()
    val status by PlayerHub.status.collectAsState()
    val loading by PlayerHub.loading.collectAsState()
    val qOpts by PlayerHub.qualityOptions.collectAsState()
    val aOpts by PlayerHub.audioOptions.collectAsState()
    val sOpts by PlayerHub.subtitleOptions.collectAsState()
    val hasOptions = qOpts.isNotEmpty() || aOpts.isNotEmpty() || sOpts.isNotEmpty()
    val toggleFullscreen by rememberUpdatedState(onToggleFullscreen)
    var controlsVisible by remember { mutableStateOf(true) }
    var brightness by rememberSaveable { mutableFloatStateOf(1f) }
    var showOptions by remember { mutableStateOf(false) }

    Box(modifier.background(Color.Black)) {
        if (carActive) {
            Text("Reproduzindo na tela do carro", color = Color.White, modifier = Modifier.align(Alignment.Center))
        } else {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        player = PlayerHub.player
                        useController = true
                        setControllerShowTimeoutMs(3000)
                        setShowSubtitleButton(false)
                        setControllerVisibilityListener(
                            PlayerView.ControllerVisibilityListener { v -> controlsVisible = v == View.VISIBLE }
                        )
                        setFullscreenButtonClickListener { toggleFullscreen() }
                    }
                },
                onRelease = { it.player = null }
            )
            // Brilho da imagem: escurece o vídeo
            if (brightness < 0.999f) {
                Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 1f - brightness)))
            }
        }

        // Ao vivo: indicador no canto; se você voltou no tempo, vira o botão para retornar ao vivo.
        var liveState by remember { androidx.compose.runtime.mutableIntStateOf(0) } // 0 = não é ao vivo, 1 = ao vivo, 2 = atrasado
        androidx.compose.runtime.LaunchedEffect(item.id) {
            while (true) {
                liveState = if (!PlayerHub.liveNow()) 0 else if (PlayerHub.behindLive()) 2 else 1
                kotlinx.coroutines.delay(1000)
            }
        }
        if (liveState != 0 && controlsVisible && !carActive) {
            Text(
                if (liveState == 2) "▶▶ Voltar ao vivo" else "● AO VIVO",
                color = if (liveState == 2) Color.White else Color(0xFFFF2D55),
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = 64.dp)
                    .clip(RoundedCornerShape(14.dp)).background(Color(0xAA000000))
                    .clickable { PlayerHub.seekToLive() }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
        if (loading) {
            androidx.compose.material3.CircularProgressIndicator(
                color = Color.White,
                modifier = Modifier.align(Alignment.Center).padding(bottom = 96.dp)
            )
        }
        status?.let {
            Text(
                it, color = Color.White,
                modifier = Modifier.align(Alignment.Center)
                    .clip(RoundedCornerShape(8.dp)).background(Color(0x99000000)).padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }

        if (controlsVisible && !carActive) {
            Row(
                Modifier.align(Alignment.TopStart).fillMaxWidth().padding(start = 12.dp, top = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    item.title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f)
                )
                if (hasOptions) {
                    IconButton(onClick = { showOptions = true }) {
                        Icon(Icons.Default.Settings, "Qualidade, áudio e legendas", tint = Color.White)
                    }
                }
            }
            Column(
                Modifier.align(Alignment.CenterEnd).padding(end = 8.dp).fillMaxHeight(0.5f).width(34.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("☀", color = Color.White)
                BrightnessBar(brightness, { brightness = it }, Modifier.weight(1f).fillMaxWidth())
            }
        }
    }

    if (showOptions) OptionsDialog(onDismiss = { showOptions = false })
}

@Composable
private fun BrightnessBar(value: Float, onChange: (Float) -> Unit, modifier: Modifier = Modifier) {
    var heightPx by remember { mutableFloatStateOf(1f) }
    fun fromY(y: Float) = (1f - y / heightPx).coerceIn(MIN_BRIGHTNESS, 1f)

    Box(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0x66FFFFFF))
            .onSizeChanged { heightPx = it.height.toFloat().coerceAtLeast(1f) }
            .pointerInput(Unit) { detectTapGestures { offset -> onChange(fromY(offset.y)) } }
            .pointerInput(Unit) {
                detectVerticalDragGestures { change, _ ->
                    onChange(fromY(change.position.y))
                    change.consume()
                }
            }
    ) {
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .fillMaxHeight(value.coerceIn(MIN_BRIGHTNESS, 1f)).background(Color.White)
        )
    }
}

/** Menu da engrenagem: qualidade, trilha de áudio (idioma) e legendas. */
@Composable
fun OptionsDialog(onDismiss: () -> Unit) {
    val qualities by PlayerHub.qualityOptions.collectAsState()
    val audios by PlayerHub.audioOptions.collectAsState()
    val subs by PlayerHub.subtitleOptions.collectAsState()
    var page by remember { mutableStateOf("root") }

    val title = when (page) {
        "quality" -> "Qualidade"
        "audio" -> "Idioma do áudio"
        "subs" -> "Legendas"
        else -> "Opções"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.heightIn(max = 360.dp)) {
                if (qualities.isEmpty() && audios.isEmpty() && subs.isEmpty()) {
                    Text("Nenhuma opção disponível para este conteúdo.")
                } else when (page) {
                    "root" -> {
                        if (qualities.isNotEmpty()) {
                            MenuRow("Qualidade", qualities.firstOrNull { it.selected }?.label ?: "-") { page = "quality" }
                        }
                        MenuRow(
                            "Idioma do áudio",
                            if (audios.isEmpty()) "Único" else audios.firstOrNull { it.selected }?.label ?: "-"
                        ) { if (audios.isNotEmpty()) page = "audio" }
                        MenuRow(
                            "Legendas",
                            if (subs.isEmpty()) "Indisponíveis" else subs.firstOrNull { it.selected }?.label ?: "Desativadas"
                        ) { if (subs.isNotEmpty()) page = "subs" }
                    }
                    "quality" -> OptionList(qualities) { PlayerHub.selectQuality(it); page = "root" }
                    "audio" -> OptionList(audios) { PlayerHub.selectAudio(it); page = "root" }
                    "subs" -> OptionList(subs) { PlayerHub.selectSubtitle(it); page = "root" }
                    else -> Unit
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { if (page == "root") onDismiss() else page = "root" }) {
                Text(if (page == "root") "Fechar" else "Voltar")
            }
        }
    )
}

@Composable
private fun MenuRow(label: String, value: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun OptionList(options: List<Option>, onPick: (String) -> Unit) {
    LazyColumn {
        itemsIndexed(options) { _, o ->
            Row(
                Modifier.fillMaxWidth().clickable { onPick(o.id) }.padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = o.selected, onClick = null)
                Text(o.label, Modifier.padding(start = 12.dp))
            }
        }
    }
}

/** Player em tamanho normal (16:9) com a barra de informações embaixo. */
@Composable
fun PlayerPanel(vm: MainViewModel, item: Item, onFullscreen: () -> Unit) {
    val favs by vm.favorites.collectAsState()
    val following by vm.following.collectAsState()
    val isFav = favs.any { it.id == item.id }
    val isFollowing = vm.isFollowing(item.channelUrl, following)

    Column(Modifier.fillMaxWidth().background(Color.Black)) {
        PlayerSurface(item, false, onFullscreen, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall, color = Color.White)
                item.group?.let {
                    Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall, color = Color.LightGray)
                }
            }
            if (item.channelUrl != null) {
                TextButton(onClick = { vm.toggleFollow(item) }) { Text(if (isFollowing) "Seguindo" else "Seguir") }
            }
            IconButton(onClick = { vm.toggleFavorite(item) }) {
                Icon(if (isFav) Icons.Default.Favorite else Icons.Default.FavoriteBorder, "Favorito", tint = Color.White)
            }
            TextButton(onClick = { PlayerHub.stop() }) { Text("✕") }
        }
    }
}
