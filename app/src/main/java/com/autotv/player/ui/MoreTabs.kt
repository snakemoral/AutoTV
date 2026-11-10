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
    /** Tela cheia: só a página aparece, deitada, sem as barras do app. */
    var fill by mutableStateOf(false)
    /** Tela cheia pedida pelo próprio site (botão do player da página). */
    var siteFullscreen by mutableStateOf<View?>(null)
    var exitSiteFullscreen: (() -> Unit)? = null

    fun setFillMode(on: Boolean) {
        fill = on
        web.evaluateJavascript(Browser.jsFill(on), null)
    }
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

    BackHandler(enabled = state.canGoBack) { state.web.goBack() }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { state.web.goBack() }, enabled = state.canGoBack) { Icon(Icons.Default.ArrowBack, "Voltar") }
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
        // Controles para vídeos de sites que não mostram os próprios botões.
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { state.web.evaluateJavascript(Browser.JS_TOGGLE_PLAY, null) }) { Text("⏯  Pausar / tocar") }
            TextButton(onClick = { state.setFillMode(true) }) { Text("⛶  Tela cheia") }
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

/** Navegador em tela cheia: só a página (ou o vídeo que o site pôs em tela cheia) e dois botões discretos. */
@Composable
fun BrowserFullscreen(state: BrowserState) {
    val site = state.siteFullscreen
    BackHandler {
        if (site != null) state.exitSiteFullscreen?.invoke() else state.setFillMode(false)
    }
    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
        // A página fica sempre por baixo (se sair da tela, o site pode parar o vídeo).
        AndroidView(
            factory = {
                (state.web.parent as? ViewGroup)?.removeView(state.web)
                state.web
            },
            modifier = Modifier.fillMaxSize()
        )
        if (site != null) {
            androidx.compose.runtime.key(site) {
                AndroidView(
                    factory = {
                        (site.parent as? ViewGroup)?.removeView(site)
                        site
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        } else {
            Row(Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                TextButton(onClick = { state.web.evaluateJavascript(Browser.JS_TOGGLE_PLAY, null) }) { Text("⏯") }
                TextButton(onClick = { state.setFillMode(false) }) { Text("✕ Sair") }
            }
        }
    }
}
