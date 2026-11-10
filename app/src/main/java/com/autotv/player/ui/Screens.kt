package com.autotv.player.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.autotv.player.data.Item
import com.autotv.player.data.Playlist
import com.autotv.player.data.Prefs
import com.autotv.player.player.PlayerHub

@Composable
fun AutoTvTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(), content = content)
}

@Composable
fun AppRoot(vm: MainViewModel) {
    // Abre direto em "Seguindo".
    var tab by rememberSaveable { mutableIntStateOf(1) }
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    val now by PlayerHub.nowPlaying.collectAsState()
    val busy by vm.busy.collectAsState()
    val message by vm.message.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); vm.clearMessage() }
    }
    LaunchedEffect(now) { if (now == null) fullscreen = false }
    FullscreenEffect(fullscreen && now != null)

    val current = now
    if (fullscreen && current != null) {
        BackHandler { fullscreen = false }
        PlayerSurface(current, true, { fullscreen = false }, Modifier.fillMaxSize())
        return
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(selected = tab == 0, onClick = { tab = 0 },
                    icon = { Icon(Icons.Default.Home, null) }, label = { Text("Início") })
                NavigationBarItem(selected = tab == 1, onClick = { tab = 1 },
                    icon = { Icon(Icons.Default.Person, null) }, label = { Text("Seguindo") })
                NavigationBarItem(selected = tab == 2, onClick = { tab = 2 },
                    icon = { Icon(Icons.Default.Menu, null) }, label = { Text("TV") })
                NavigationBarItem(selected = tab == 3, onClick = { tab = 3 },
                    icon = { Icon(Icons.Default.Favorite, null) }, label = { Text("Biblioteca") })
            }
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (current != null) PlayerPanel(vm, current) { fullscreen = true }
            if (busy) CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally).padding(8.dp))
            when (tab) {
                0 -> HomeTab(vm)
                1 -> FollowingTab(vm)
                2 -> PlaylistsTab(vm)
                else -> LibraryTab(vm)
            }
        }
    }
}

/** Tela cheia: paisagem + esconde as barras do sistema. */
@Composable
private fun FullscreenEffect(enabled: Boolean) {
    val view = LocalView.current
    val activity = LocalContext.current as? Activity
    LaunchedEffect(enabled) {
        activity ?: return@LaunchedEffect
        activity.requestedOrientation =
            if (enabled) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        val controller = WindowCompat.getInsetsController(activity.window, view)
        if (enabled) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}

// ---------------------------------------------------------------- Início (YouTube)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeTab(vm: MainViewModel) {
    var query by rememberSaveable { mutableStateOf("") }
    val results by vm.ytResults.collectAsState()
    val resultsFor by vm.resultsFor.collectAsState()
    val section by vm.homeSection.collectAsState()
    val feed by vm.feed.collectAsState()
    val history by vm.history.collectAsState()

    LaunchedEffect(Unit) { vm.selectHome(section) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                placeholder = { Text("Buscar no YouTube ou colar link") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { vm.submitYoutube(query) }),
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { vm.submitYoutube(query) }) { Icon(Icons.Default.Search, "Buscar") }
        }

        if (resultsFor != null) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Resultados para \"$resultsFor\"", Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = { vm.clearResults() }) { Text("Limpar") }
            }
            MediaList(vm, results)
        } else {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                HomeSection.values().forEach { s ->
                    FilterChip(
                        selected = section == s,
                        onClick = { vm.selectHome(s, force = section == s) },
                        label = { Text(s.label) }
                    )
                }
            }
            val items: List<Item> = when (section) {
                HomeSection.HISTORY -> history
                else -> feed?.takeIf { it.first == section }?.second.orEmpty()
            }
            if (section == HomeSection.HISTORY && history.isNotEmpty()) {
                MediaList(vm, items, header = {
                    TextButton(onClick = { vm.clearHistory() }) { Text("Limpar histórico") }
                })
            } else {
                if (items.isEmpty()) {
                    Text(
                        if (section == HomeSection.HISTORY) "Nada assistido ainda." else "Carregando… toque na aba de novo para atualizar.",
                        Modifier.padding(16.dp), color = MaterialTheme.colorScheme.outline
                    )
                }
                MediaList(vm, items)
            }
        }
    }
}

// ---------------------------------------------------------------- Seguindo (substitui o login)

@Composable
fun FollowingTab(vm: MainViewModel) {
    val channels by vm.following.collectAsState()
    val feed by vm.followingFeed.collectAsState()
    val signedIn by vm.signedIn.collectAsState()
    val loginCode by vm.loginCode.collectAsState()
    val loading by vm.followingLoading.collectAsState()
    val feedError by vm.followingError.collectAsState()
    var manage by rememberSaveable { mutableStateOf(false) }
    val ctx = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) vm.importSubscriptions(ctx.contentResolver, uri)
    }

    LaunchedEffect(channels, signedIn) { vm.loadFollowingFeed() }

    loginCode?.let { code -> LoginDialog(code.userCode, code.url, onCancel = { vm.cancelLogin() }) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (signedIn) TextButton(onClick = { vm.signOut() }) { Text("Sair da conta") }
            else TextButton(onClick = { vm.startLogin() }) { Text("Entrar com Google") }
            TextButton(onClick = { picker.launch(arrayOf("*/*")) }) { Text("Importar arquivo") }
            if (signedIn || channels.isNotEmpty()) {
                IconButton(onClick = { vm.loadFollowingFeed(force = true) }) { Icon(Icons.Default.Refresh, "Atualizar") }
            }
            if (channels.isNotEmpty()) {
                TextButton(onClick = { manage = !manage }) { Text(if (manage) "Concluir" else "Canais (${channels.size})") }
            }
        }
        if (!signedIn && channels.isEmpty()) {
            Text(
                "Para ver os vídeos dos canais em que você é inscrito:\n\n" +
                    "• Toque em \"Entrar com Google\". O app mostra um código; você digita em google.com/device " +
                    "no navegador, onde faz a senha e a verificação em duas etapas. O app não vê sua senha.\n\n" +
                    "• Ou toque em \"Importar arquivo\" e escolha o subscriptions.csv (ou o .zip inteiro) do Google Takeout.\n\n" +
                    "Você também pode abrir um vídeo e tocar em Seguir.",
                Modifier.padding(16.dp), color = MaterialTheme.colorScheme.outline
            )
        } else if (manage && channels.isNotEmpty()) {
            LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(channels, key = { _, c -> c.url }) { _, c ->
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(c.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        IconButton(onClick = { vm.unfollow(c.url) }) { Icon(Icons.Default.Delete, "Deixar de seguir") }
                    }
                }
            }
        } else {
            if (loading && feed.isEmpty()) {
                Text("Carregando novidades…", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.outline)
            }
            feedError?.let { Text("Não deu para carregar: $it", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
            MediaList(vm, feed)
        }
    }
}

@Composable
private fun LoginDialog(userCode: String, url: String, onCancel: () -> Unit) {
    val ctx = LocalContext.current
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Entrar com Google") },
        text = {
            Column {
                Text("1. Abra $url no navegador (neste celular ou em outro aparelho).")
                Spacer(Modifier.width(8.dp))
                Text("2. Digite este código:")
                Text(userCode, Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.headlineMedium)
                Text("3. Escolha sua conta e confirme. Esta tela fecha sozinha quando o Google autorizar.")
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val clip = ctx.getSystemService(android.content.ClipboardManager::class.java)
                clip?.setPrimaryClip(android.content.ClipData.newPlainText("Código", userCode))
                runCatching {
                    ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(url)))
                }
            }) { Text("Copiar código e abrir") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancelar") } }
    )
}

// ---------------------------------------------------------------- TV (listas M3U)

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
        MediaList(vm, filtered)
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

// ---------------------------------------------------------------- Biblioteca (favoritos)

@Composable
fun LibraryTab(vm: MainViewModel) {
    val favs by vm.favorites.collectAsState()
    var carAdvanced by remember { mutableStateOf(Prefs.carAdvancedUi) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Interface avançada no carro")
                Text(
                    "Botões, barra de progresso, brilho e legendas. Se a tela do carro ficar preta, desligue (vale no próximo vídeo).",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline
                )
            }
            Switch(checked = carAdvanced, onCheckedChange = { carAdvanced = it; Prefs.carAdvancedUi = it })
        }
        if (favs.isEmpty()) {
            Text("Nenhum favorito ainda. Toque no coração de um canal ou vídeo.", Modifier.padding(24.dp))
        }
        MediaList(vm, favs)
    }
}
