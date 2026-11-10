package com.autotv.player.ui

import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.autotv.player.data.Browser
import com.autotv.player.data.LocalVideos
import com.autotv.player.data.Prefs

// ---------------------------------------------------------------- Baixados (vídeos do celular)

@Composable
fun LocalTab(vm: MainViewModel) {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(LocalVideos.hasPermission(ctx)) }
    val items by vm.localVideos.collectAsState()
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }

    LaunchedEffect(granted) { if (granted) vm.loadLocalVideos(ctx) }

    Column(Modifier.fillMaxSize()) {
        if (!granted) {
            Text(
                "Aqui aparecem os vídeos guardados no celular (pastas Download, Filmes, Câmera etc.), " +
                    "para assistir sem internet, inclusive no carro.\n\n" +
                    "Os vídeos baixados dentro do app do YouTube não aparecem: eles ficam protegidos e só o YouTube abre.",
                Modifier.padding(16.dp), color = MaterialTheme.colorScheme.outline
            )
            Button(onClick = { ask.launch(LocalVideos.permission()) }, modifier = Modifier.padding(horizontal = 16.dp)) {
                Text("Permitir acesso aos vídeos")
            }
        } else {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${items.size} vídeos no celular", Modifier.weight(1f), color = MaterialTheme.colorScheme.outline)
                IconButton(onClick = { vm.loadLocalVideos(ctx) }) { Icon(Icons.Default.Refresh, "Atualizar") }
            }
            MediaList(vm, items)
        }
    }
}

// ---------------------------------------------------------------- Web (navegador)

/** O navegador do celular: uma única página que continua viva ao trocar de aba. */
class BrowserState(val web: WebView) {
    var url by mutableStateOf("")
    var canGoBack by mutableStateOf(false)
    var progress by mutableStateOf(100)
    var canGoForward by mutableStateOf(false)
    var bookmarked by mutableStateOf(false)
    /** Tela cheia pedida pelo próprio site (botão do player da página). */
    var siteFullscreen by mutableStateOf<View?>(null)
    var exitSiteFullscreen: (() -> Unit)? = null

}

private fun createBrowser(context: android.content.Context): BrowserState {
    val web = WebView(context)
    val state = BrowserState(web)
    Browser.configure(web)
    web.webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            // Só páginas da web; links que abririam outros apps são ignorados.
            val scheme = request.url.scheme.orEmpty()
            return scheme != "http" && scheme != "https"
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) { state.url = url.orEmpty() }

        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
            state.url = url.orEmpty()
            state.canGoBack = view.canGoBack()
            state.canGoForward = view.canGoForward()
            state.bookmarked = Prefs.isBookmarked(state.url)
            if (!url.isNullOrBlank()) Prefs.browserUrl = url
        }
    }
    web.webChromeClient = object : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) { state.progress = newProgress }

        // O site pediu tela cheia (botão do player da página): mostra o vídeo por cima de tudo.
        override fun onShowCustomView(view: View, callback: WebChromeClient.CustomViewCallback) {
            if (state.siteFullscreen != null) { callback.onCustomViewHidden(); return }
            state.exitSiteFullscreen = { callback.onCustomViewHidden() }
            state.siteFullscreen = view
        }

        override fun onHideCustomView() {
            state.siteFullscreen = null
            state.exitSiteFullscreen = null
        }
    }
    web.loadUrl(Prefs.browserUrl)
    return state
}

@Composable
fun BrowserTab(holder: MutableState<BrowserState?>) {
    val ctx = LocalContext.current
    val focus = LocalFocusManager.current
    val state = holder.value ?: createBrowser(ctx).also { holder.value = it }
    var address by remember { mutableStateOf(state.url) }
    var editing by remember { mutableStateOf(false) }
    if (!editing) address = state.url

    var showBookmarks by remember { mutableStateOf(false) }

    BackHandler(enabled = state.canGoBack) { state.web.goBack() }

    if (showBookmarks) {
        var list by remember { mutableStateOf(Prefs.bookmarks) }
        AlertDialog(
            onDismissRequest = { showBookmarks = false },
            title = { Text("Favoritos") },
            text = {
                if (list.isEmpty()) Text("Nenhuma página salva. Abra um site e toque em \"Salvar página\".")
                else LazyColumn {
                    items(list.size) { i ->
                        val (title, url) = list[i]
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).clickable { showBookmarks = false; state.web.loadUrl(url) }.padding(vertical = 8.dp)) {
                                Text(title, maxLines = 1)
                                Text(url, maxLines = 1, color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.bodySmall)
                            }
                            IconButton(onClick = {
                                Prefs.bookmarks = list.filter { it.second != url }
                                list = Prefs.bookmarks
                                state.bookmarked = Prefs.isBookmarked(state.url)
                            }) { Icon(Icons.Default.Delete, "Remover") }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showBookmarks = false }) { Text("Fechar") } }
        )
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { state.web.goBack() }, enabled = state.canGoBack) { Icon(Icons.Default.ArrowBack, "Página anterior") }
            IconButton(onClick = { state.web.goForward() }, enabled = state.canGoForward) { Icon(Icons.Default.ArrowForward, "Página seguinte") }
            OutlinedTextField(
                value = address,
                onValueChange = { address = it; editing = true },
                singleLine = true,
                placeholder = { Text("Endereço ou busca") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = {
                    editing = false
                    focus.clearFocus()
                    state.web.loadUrl(Browser.toUrl(address))
                }),
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { state.web.reload() }) { Icon(Icons.Default.Refresh, "Recarregar") }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = {
                state.bookmarked = Prefs.toggleBookmark(state.web.title.orEmpty(), state.url)
            }) { Text(if (state.bookmarked) "★ Salvo" else "☆ Salvar página") }
            TextButton(onClick = { showBookmarks = true }) { Text("Favoritos") }
            TextButton(onClick = { state.web.loadUrl(Browser.HOME) }) { Text("Início") }
        }
        if (state.progress < 100) LinearProgressIndicator(progress = { state.progress / 100f }, modifier = Modifier.fillMaxWidth())
        AndroidView(
            factory = {
                (state.web.parent as? ViewGroup)?.removeView(state.web)
                state.web
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}

/** O site pôs um vídeo em tela cheia: mostra só ele, deitado; o botão voltar sai. */
@Composable
fun BrowserFullscreen(state: BrowserState) {
    val site = state.siteFullscreen ?: return
    BackHandler { state.exitSiteFullscreen?.invoke() }
    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
        // A página fica por baixo (se sair da tela, o site pode parar o vídeo).
        AndroidView(
            factory = {
                (state.web.parent as? ViewGroup)?.removeView(state.web)
                state.web
            },
            modifier = Modifier.fillMaxSize()
        )
        androidx.compose.runtime.key(site) {
            AndroidView(
                factory = {
                    (site.parent as? ViewGroup)?.removeView(site)
                    site
                },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
