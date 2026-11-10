package com.autotv.player.data

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import java.net.URLEncoder

/** Ajudas comuns ao navegador do celular e do carro. */
object Browser {
    const val HOME = "https://www.google.com"

    /** Transforma o que foi digitado em endereço: link direto, ou busca no Google. */
    fun toUrl(text: String): String {
        val t = text.trim()
        return when {
            t.isEmpty() -> HOME
            t.startsWith("http://") || t.startsWith("https://") -> t
            !t.contains(' ') && t.contains('.') -> "https://$t"
            else -> "https://www.google.com/search?q=" + URLEncoder.encode(t, "UTF-8")
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun configure(web: WebView) {
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
        }
        CookieManager.getInstance().setAcceptCookie(true)
    }
}
