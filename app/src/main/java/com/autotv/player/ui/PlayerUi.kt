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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import androidx.compose.runtime.LaunchedEffect
import com.autotv.player.data.Store
import androidx.compose.foundation.layout.height
import androidx.compose.ui.unit.sp
import com.autotv.player.data.Channel
import com.autotv.player.data.Item
import com.autotv.player.data.Kind
import com.autotv.player.data.VideoDetails
import com.autotv.player.data.compactCount
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
                        // Ao abrir um vídeo os controles aparecem "sem prazo" enquanto ele carrega, e às vezes o
                        // player não trocava para o prazo normal quando começava a tocar. Quando começa a tocar,
                        // os controles visíveis recebem de novo o prazo de 3 s.
                        val view = this
                        val listener = object : androidx.media3.common.Player.Listener {
                            override fun onIsPlayingChanged(isPlaying: Boolean) {
                                if (isPlaying) view.postDelayed({
                                    if (view.player?.isPlaying == true && controlsVisible) view.showController()
                                }, 250)
                            }
                        }
                        PlayerHub.player.addListener(listener)
                        tag = listener
                    }
                },
                onRelease = {
                    (it.tag as? androidx.media3.common.Player.Listener)?.let { l -> PlayerHub.player.removeListener(l) }
                    it.player = null
                }
            )
            // Rádio (só áudio): logotipo grande e nome no centro, no lugar da tela preta.
            val audioOnly by PlayerHub.audioOnly.collectAsState()
            if (audioOnly) {
                Column(Modifier.align(Alignment.Center).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier.fillMaxHeight(if (fullscreen) 0.5f else 0.55f).aspectRatio(1f)
                            .clip(RoundedCornerShape(20.dp)).background(Color(0xFF26262C)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("♪", color = Color(0xFF9A9AA2), fontSize = 48.sp)
                        item.thumb?.takeIf { it.isNotBlank() }?.let {
                            AsyncImage(model = it, contentDescription = item.title,
                                modifier = Modifier.fillMaxSize().background(Color(0xFF26262C)).padding(8.dp),
                                contentScale = ContentScale.Fit)
                        }
                    }
                    Text(item.title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                }
            }
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

/**
 * Player em tamanho normal (16:9) e, embaixo, as informações do vídeo:
 * título (toque para ver a descrição), canal (toque para ver os vídeos dele), inscrição e gostei / não gostei.
 */
@Composable
fun PlayerPanel(vm: MainViewModel, item: Item, onFullscreen: () -> Unit, onOpenChannel: () -> Unit) {
    val favs by vm.favorites.collectAsState()
    val signedIn by vm.signedIn.collectAsState()
    val details by PlayerHub.details.collectAsState()
    val rating by PlayerHub.rating.collectAsState()
    val subscribed by PlayerHub.subscribed.collectAsState()
    val isFav = favs.any { it.id == item.id }
    val isYoutube = item.kind == Kind.YOUTUBE
    val channelName = details?.channelName ?: item.group
    val channelUrl = details?.channelUrl ?: item.channelUrl
    var showDescription by remember(item.id) { mutableStateOf(false) }
    val isLive by PlayerHub.isLive.collectAsState()
    val chatAvailable = isYoutube && isLive
    var chatOpen by rememberSaveable { mutableStateOf(LiveChatPrefs.open) }
    LaunchedEffect(chatOpen) { LiveChatPrefs.open = chatOpen }

    Column(Modifier.fillMaxWidth().background(Color.Black)) {
        PlayerSurface(item, false, onFullscreen, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
        Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(
                Modifier.weight(1f).clickable(enabled = isYoutube) { showDescription = true }.padding(vertical = 6.dp)
            ) {
                Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall, color = Color.White)
                if (!isYoutube) item.group?.let {
                    Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall, color = Color.LightGray)
                }
            }
            if (chatAvailable) {
                // Transmissão ao vivo: abre e fecha o chat embaixo do vídeo.
                Text(
                    "💬", fontSize = 20.sp,
                    modifier = Modifier.clip(CircleShape)
                        .background(if (chatOpen) Color.White else Color.Transparent)
                        .clickable { chatOpen = !chatOpen }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
            IconButton(onClick = { vm.toggleFavorite(item) }) {
                Icon(if (isFav) Icons.Default.Favorite else Icons.Default.FavoriteBorder, "Favorito", tint = Color.White)
            }
            TextButton(onClick = { PlayerHub.stop() }) { Text("✕") }
        }
        if (isYoutube) {
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).clickable(enabled = channelUrl != null) {
                        channelUrl?.let { vm.openChannel(Channel(it, channelName ?: "Canal")); onOpenChannel() }
                    },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(36.dp).clip(CircleShape).background(Color(0xFF333333))) {
                        details?.avatar?.let {
                            AsyncImage(model = it, contentDescription = "Canal",
                                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        }
                    }
                    Column(Modifier.padding(start = 8.dp)) {
                        Text(channelName ?: "Canal", maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium, color = Color.White)
                        val subs = details?.subscribers ?: -1
                        if (subs >= 0) {
                            Text("${compactCount(subs)} inscritos", maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall, color = Color.LightGray)
                        }
                    }
                }
                if (channelUrl != null) {
                    InfoPill(
                        when {
                            signedIn -> if (subscribed) "Inscrito" else "Inscrever-se"
                            else -> if (subscribed) "Seguindo" else "Seguir"
                        },
                        selected = subscribed
                    ) { PlayerHub.toggleSubscribe() }
                }
                val likes = details?.likes ?: -1
                InfoPill("👍" + if (likes >= 0) " ${compactCount(likes)}" else "", selected = rating > 0) { PlayerHub.rate(1) }
                InfoPill("👎", selected = rating < 0) { PlayerHub.rate(-1) }
            }
        }
        if (chatAvailable && chatOpen) Store.youtubeId(item)?.let { LiveChatPanel(it) }
    }

    if (showDescription) DescriptionDialog(item, details) { showDescription = false }
}

/** Lembra, enquanto o app está aberto, se o chat ao vivo estava aberto (vale para o próximo ao vivo). */
object LiveChatPrefs { var open = false }

/** Chat da transmissão ao vivo (só leitura), com a mensagem mais nova embaixo. */
@Composable
private fun LiveChatPanel(videoId: String) {
    var messages by remember(videoId) { mutableStateOf<List<com.autotv.player.data.ChatMessage>>(emptyList()) }
    var info by remember(videoId) { mutableStateOf<String?>("Conectando ao chat…") }
    LaunchedEffect(videoId) {
        try {
            com.autotv.player.data.LiveChat.watch(videoId).collect { messages = it; info = null }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            info = e.message ?: "Não foi possível abrir o chat"
        }
    }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(messages.size) { if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1) }
    Box(Modifier.fillMaxWidth().height(240.dp).background(Color(0xFF111114))) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 6.dp)) {
            itemsIndexed(messages, key = { i, m -> m.id.ifBlank { "i$i" } }) { _, m ->
                Text(
                    androidx.compose.ui.text.buildAnnotatedString {
                        m.amount?.let {
                            pushStyle(androidx.compose.ui.text.SpanStyle(color = Color(0xFFFFC107), fontWeight = androidx.compose.ui.text.font.FontWeight.Bold))
                            append("$it  "); pop()
                        }
                        pushStyle(androidx.compose.ui.text.SpanStyle(color = Color(0xFFAAAAAA)))
                        append(m.author); pop()
                        append("  ")
                        append(m.text)
                    },
                    color = Color.White, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 3.dp)
                )
            }
        }
        info?.let {
            Text(it, color = Color.LightGray, modifier = Modifier.align(Alignment.Center).padding(16.dp))
        }
    }
}

/** Botão em forma de pílula; [selected] deixa em destaque. */
@Composable
private fun InfoPill(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text, maxLines = 1,
        color = if (selected) Color.Black else Color.White,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(start = 6.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(if (selected) Color.White else Color(0xFF2A2A2A))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    )
}

/** Descrição do vídeo: título, canal, visualizações, data e o texto. */
@Composable
private fun DescriptionDialog(item: Item, details: VideoDetails?, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(item.title, style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                if (details == null) {
                    Text("Carregando as informações do vídeo…")
                } else {
                    val facts = listOfNotNull(
                        details.channelName,
                        details.views.takeIf { it >= 0 }?.let { "${compactCount(it)} visualizações" },
                        details.uploaded
                    )
                    if (facts.isNotEmpty()) {
                        Text(facts.joinToString(" • "), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(bottom = 10.dp))
                    }
                    Text(details.description.ifBlank { "Este vídeo não tem descrição." })
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fechar") } }
    )
}
