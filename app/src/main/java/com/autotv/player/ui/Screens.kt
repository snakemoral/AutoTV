package com.autotv.player.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.autotv.player.data.Item
import com.autotv.player.data.Playlist
import com.autotv.player.player.PlayerHub

@Composable
fun AutoTvTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(), content = content)
}

@Composable
fun AppRoot(vm: MainViewModel) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val now by PlayerHub.nowPlaying.collectAsState()
    val busy by vm.busy.collectAsState()
    val message by vm.message.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); vm.clearMessage() }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(selected = tab == 0, onClick = { tab = 0 },
                    icon = { Icon(Icons.Default.Menu, null) }, label = { Text("Listas") })
                NavigationBarItem(selected = tab == 1, onClick = { tab = 1 },
                    icon = { Icon(Icons.Default.PlayArrow, null) }, label = { Text("YouTube") })
                NavigationBarItem(selected = tab == 2, onClick = { tab = 2 },
                    icon = { Icon(Icons.Default.Favorite, null) }, label = { Text("Favoritos") })
            }
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (now != null) PlayerPanel(vm, now!!)
            if (busy) CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally).padding(8.dp))
            when (tab) {
                0 -> PlaylistsTab(vm)
                1 -> YouTubeTab(vm)
                else -> FavoritesTab(vm)
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
fun PlayerPanel(vm: MainViewModel, item: Item) {
    val carActive by PlayerHub.carActive.collectAsState()
    val status by PlayerHub.status.collectAsState()
    val favs by vm.favorites.collectAsState()
    val isFav = favs.any { it.id == item.id }

    Column(Modifier.fillMaxWidth().background(Color.Black)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f), contentAlignment = Alignment.Center) {
            if (carActive) {
                Text("Reproduzindo na tela do carro", color = Color.White)
            } else {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            player = PlayerHub.player
                            useController = true
                        }
                    },
                    onRelease = { it.player = null }
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
            }
            TextButton(onClick = { PlayerHub.previous() }) { Text("⏮") }
            TextButton(onClick = { PlayerHub.next() }) { Text("⏭") }
            IconButton(onClick = { vm.toggleFavorite(item) }) {
                Icon(if (isFav) Icons.Default.Favorite else Icons.Default.FavoriteBorder, "Favorito")
            }
            TextButton(onClick = { PlayerHub.stop() }) { Text("✕") }
        }
    }
}

// ---------------------------------------------------------------- Listas

@Composable
fun PlaylistsTab(vm: MainViewModel) {
    val lists by vm.playlists.collectAsState()
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    val open = lists.firstOrNull { it.id == openId }

    Box(Modifier.fillMaxSize()) {
        if (open == null) {
            if (lists.isEmpty()) {
                Text(
                    "Nenhuma lista ainda.\nToque em + para adicionar uma URL M3U, um arquivo, ou o link de uma playlist do YouTube.",
                    Modifier.padding(24.dp)
                )
            }
            LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(lists, key = { _, p -> p.id }) { _, p ->
                    Column(Modifier.fillMaxWidth().clickable { openId = p.id }.padding(16.dp)) {
                        Text(p.name, style = MaterialTheme.typography.titleMedium)
                        Text("${p.items.size} itens", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            FloatingActionButton(
                onClick = { showAdd = true },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
            ) { Icon(Icons.Default.Add, "Adicionar lista") }
        } else {
            PlaylistDetail(vm, open, onBack = { openId = null })
        }
    }

    if (showAdd) AddPlaylistDialog(vm, onDismiss = { showAdd = false })
}

@Composable
fun PlaylistDetail(vm: MainViewModel, playlist: Playlist, onBack: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val favs by vm.favorites.collectAsState()
    val favIds = remember(favs) { favs.map { it.id }.toSet() }
    val filtered = remember(playlist, query) {
        if (query.isBlank()) playlist.items
        else playlist.items.filter {
            it.title.contains(query, ignoreCase = true) || (it.group?.contains(query, ignoreCase = true) == true)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Voltar") }
            Text(playlist.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium)
            IconButton(onClick = { vm.refresh(playlist) }) { Icon(Icons.Default.Refresh, "Atualizar") }
            IconButton(onClick = { vm.remove(playlist); onBack() }) { Icon(Icons.Default.Delete, "Remover") }
        }
        OutlinedTextField(
            value = query, onValueChange = { query = it }, singleLine = true,
            label = { Text("Buscar canal ou categoria") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
        )
        ItemList(vm, filtered, favIds)
    }
}

@Composable
fun AddPlaylistDialog(vm: MainViewModel, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    val ctx = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) { vm.addPlaylistFromFile(ctx.contentResolver, uri, name); onDismiss() }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Adicionar lista") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Nome (opcional)") }, singleLine = true)
                OutlinedTextField(url, { url = it }, label = { Text("URL da lista M3U ou playlist do YouTube") })
                OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Escolher arquivo M3U…")
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = url.isNotBlank(),
                onClick = { vm.addPlaylistFromUrl(name, url); onDismiss() }
            ) { Text("Adicionar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

// ---------------------------------------------------------------- YouTube

@Composable
fun YouTubeTab(vm: MainViewModel) {
    var input by rememberSaveable { mutableStateOf("") }
    val results by vm.ytResults.collectAsState()
    val favs by vm.favorites.collectAsState()
    val favIds = remember(favs) { favs.map { it.id }.toSet() }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input, onValueChange = { input = it }, singleLine = true,
                label = { Text("Buscar ou colar link do YouTube") },
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = { vm.submitYoutube(input) }) { Text("Ir") }
        }
        Text(
            "Dica: compartilhe um vídeo do app do YouTube para o AutoTV. Link de playlist vira uma lista salva.",
            Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(8.dp))
        ItemList(vm, results, favIds)
    }
}

// ---------------------------------------------------------------- Favoritos

@Composable
fun FavoritesTab(vm: MainViewModel) {
    val favs by vm.favorites.collectAsState()
    val favIds = remember(favs) { favs.map { it.id }.toSet() }
    if (favs.isEmpty()) {
        Text("Nenhum favorito ainda. Toque no coração de um canal ou vídeo.", Modifier.padding(24.dp))
    }
    ItemList(vm, favs, favIds)
}

// ---------------------------------------------------------------- Lista de itens

@Composable
fun ItemList(vm: MainViewModel, items: List<Item>, favIds: Set<String>) {
    LazyColumn(Modifier.fillMaxSize()) {
        itemsIndexed(items, key = { i, it -> "${it.id}#$i" }) { index, item ->
            Row(
                Modifier.fillMaxWidth().clickable { vm.play(items, index) }.padding(start = 16.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    item.group?.let {
                        Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    }
                }
                IconButton(onClick = { vm.toggleFavorite(item) }) {
                    Icon(
                        if (item.id in favIds) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        "Favorito"
                    )
                }
            }
        }
    }
}
