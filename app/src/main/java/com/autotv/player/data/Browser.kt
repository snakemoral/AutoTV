package com.autotv.player.data

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import java.net.URLEncoder

/** Ajudas comuns ao navegador do celular e do carro. */
object Browser {
    const val HOME = "https://www.google.com"

    private const val BIGGEST_VIDEO =
        "var l=[].slice.call(document.querySelectorAll('video'));" +
            "l.sort(function(a,b){return b.clientWidth*b.clientHeight-a.clientWidth*a.clientHeight});var v=l[0];"

    /** Pausa ou continua o maior vídeo da página (para sites que não mostram o botão). */
    const val JS_TOGGLE_PLAY = "(function(){" + BIGGEST_VIDEO + "if(!v)return;if(v.paused){v.play();}else{v.pause();}})()"

    /** Tela cheia "na marra": estica o maior vídeo da página por cima de todo o resto. */
    fun jsFill(on: Boolean): String = if (on) {
        "(function(){" + BIGGEST_VIDEO + "if(!v)return;" +
            "var s=document.getElementById('autotv-fill');if(!s){s=document.createElement('style');s.id='autotv-fill';" +
            "s.textContent='.autotv-fill{position:fixed!important;left:0!important;top:0!important;width:100vw!important;" +
            "height:100vh!important;max-width:none!important;max-height:none!important;z-index:2147483647!important;" +
            "background:#000!important;object-fit:contain!important;transform:none!important}';document.head.appendChild(s);}" +
            "v.classList.add('autotv-fill');" +
            // Pais com "transform" prenderiam o vídeo dentro deles: neutraliza enquanto durar a tela cheia.
            "for(var p=v.parentElement;p&&p!==document.documentElement;p=p.parentElement){" +
            "p.setAttribute('data-autotv-style',p.getAttribute('style')||'');" +
            "p.style.setProperty('transform','none','important');p.style.setProperty('contain','none','important');" +
            "p.style.setProperty('filter','none','important');p.style.setProperty('overflow','visible','important');}" +
            "})()"
    } else {
        "(function(){var l=document.querySelectorAll('.autotv-fill');for(var i=0;i<l.length;i++)l[i].classList.remove('autotv-fill');" +
            "var a=document.querySelectorAll('[data-autotv-style]');for(var j=0;j<a.length;j++){" +
            "a[j].setAttribute('style',a[j].getAttribute('data-autotv-style'));a[j].removeAttribute('data-autotv-style');}})()"
    }

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
