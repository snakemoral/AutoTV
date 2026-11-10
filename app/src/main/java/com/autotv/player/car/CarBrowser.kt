package com.autotv.player.car

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.SearchTemplate
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.autotv.player.R
import com.autotv.player.data.Browser
import com.autotv.player.data.Prefs
import org.json.JSONObject
import kotlin.math.roundToInt

/** Tela do teclado do carro: devolve o texto digitado e fecha. */
class TextInputScreen(ctx: CarContext, private val hint: String, private val onSubmit: (String) -> Unit) : Screen(ctx) {
    override fun onGetTemplate(): Template =
        SearchTemplate.Builder(object : SearchTemplate.SearchCallback {
            override fun onSearchTextChanged(searchText: String) {}
            override fun onSearchSubmitted(searchText: String) {
                screenManager.pop()
                if (searchText.isNotBlank()) onSubmit(searchText)
            }
        })
            .setHeaderAction(Action.BACK)
            .setSearchHint(hint)
            .setShowKeyboardByDefault(true)
            .build()
}

/**
 * Navegador de internet no carro. A página é desenhada pelo app numa tela virtual;
 * para digitar usa-se o teclado do próprio Android Auto ([TextInputScreen]).
 */
class BrowserScreen(ctx: CarContext) : Screen(ctx) {
    /** true enquanto o teclado do carro está aberto por cima: a página continua viva por baixo. */
    private var typing = false

    private val client = PresentationClient(carContext) { c, display ->
        CarBrowserPresentation(
            c, display,
            onExit = { screenManager.pop() },
            onAskText = { hint, done ->
                typing = true
                screenManager.push(TextInputScreen(carContext, hint, done))
            }
        )
    }

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                typing = false
                CarSurface.setClient(client)
            }

            override fun onStop(owner: LifecycleOwner) {
                if (!typing) CarSurface.clearClient(client)
            }

            override fun onDestroy(owner: LifecycleOwner) {
                CarSurface.clearClient(client)
            }
        })
    }

    override fun onGetTemplate(): Template {
        val exit = Action.Builder()
            .setIcon(CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.ic_list)).setTint(CarColor.DEFAULT).build())
            .setOnClickListener { screenManager.pop() }
            .build()
        return NavigationTemplate.Builder()
            .setActionStrip(ActionStrip.Builder().addAction(exit).build())
            .setBackgroundColor(CarColor.createCustom(Color.BLACK, Color.BLACK))
            .build()
    }
}

class CarBrowserPresentation(
    ctx: Context,
    display: Display,
    private val onExit: () -> Unit,
    private val onAskText: (hint: String, done: (String) -> Unit) -> Unit
) : Presentation(ctx, display), SurfaceUi {

    private val handler = Handler(Looper.getMainLooper())
    private val density get() = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).roundToInt()

    private lateinit var root: FrameLayout
    private lateinit var content: LinearLayout
    private lateinit var bar: LinearLayout
    private lateinit var address: TextView
    private lateinit var web: WebView
    private lateinit var fullscreen: FrameLayout
    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        buildViews()
        setContentView(root)
        web.loadUrl(Prefs.browserUrl)
    }

    override fun open() = show()

    override fun release() {
        handler.removeCallbacksAndMessages(null)
        if (::web.isInitialized) {
            try {
                web.stopLoading()
                (web.parent as? android.view.ViewGroup)?.removeView(web)
                web.destroy()
            } catch (_: Exception) { }
        }
        try { dismiss() } catch (_: Exception) { }
    }

    override fun setInsets(left: Int, top: Int, right: Int, bottom: Int) {
        if (::content.isInitialized) content.setPadding(left, top, right, bottom)
    }

    override fun dispatchTap(x: Float, y: Float) {
        val t = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        dispatchTouchEvent(down)
        down.recycle()
        handler.postDelayed({
            val up = MotionEvent.obtain(t, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0)
                .apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            dispatchTouchEvent(up)
            up.recycle()
        }, 50)
    }

    override fun onScroll(distanceX: Float, distanceY: Float) {
        if (::web.isInitialized) web.scrollBy(distanceX.roundToInt(), distanceY.roundToInt())
    }

    override fun onFling(velocityX: Float, velocityY: Float) {
        if (::web.isInitialized) web.flingScroll(-velocityX.roundToInt(), -velocityY.roundToInt())
    }

    // ------------------------------------------------------------------ construção

    private fun pill(text: String, onClick: () -> Unit) = TextView(context).apply {
        this.text = text
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        gravity = Gravity.CENTER
        minWidth = dp(52)
        setPadding(dp(14), dp(9), dp(14), dp(9))
        background = GradientDrawable().apply { setColor(0x33FFFFFF); cornerRadius = dp(22).toFloat() }
        setOnClickListener { onClick() }
    }

    private fun buildViews() {
        root = FrameLayout(context).apply { setBackgroundColor(Color.BLACK) }
        content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        root.addView(content, FrameLayout.LayoutParams(-1, -1))

        bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
            setBackgroundColor(0xFF1A1A1E.toInt())
        }
        val gap = LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) }
        bar.addView(pill("‹") { if (web.canGoBack()) web.goBack() else onExit() })
        bar.addView(pill("⟳") { web.reload() }, LinearLayout.LayoutParams(gap))
        address = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(16), dp(9), dp(16), dp(9))
            background = GradientDrawable().apply { setColor(0x22FFFFFF); cornerRadius = dp(22).toFloat() }
            text = "Endereço ou busca"
            setOnClickListener {
                onAskText("Endereço ou busca") { typed -> web.loadUrl(Browser.toUrl(typed)) }
            }
        }
        bar.addView(address, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(8) })
        bar.addView(pill("⌨ Digitar") { onAskText("Texto para o campo da página") { typed -> typeIntoPage(typed) } }, LinearLayout.LayoutParams(gap))
        bar.addView(pill("↵") { pressEnter() }, LinearLayout.LayoutParams(gap))
        bar.addView(pill("✕") { onExit() }, LinearLayout.LayoutParams(gap))
        content.addView(bar, LinearLayout.LayoutParams(-1, -2))

        val body = FrameLayout(context)
        web = WebView(context)
        Browser.configure(web)
        web.setBackgroundColor(Color.WHITE)
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val scheme = request.url.scheme.orEmpty()
                return scheme != "http" && scheme != "https"
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                if (url.isNullOrBlank()) return
                Prefs.browserUrl = url
                address.text = url.removePrefix("https://").removePrefix("http://")
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            // Vídeo em tela cheia dentro da página.
            override fun onShowCustomView(view: View, callback: WebChromeClient.CustomViewCallback) {
                if (customView != null) { callback.onCustomViewHidden(); return }
                customView = view
                customCallback = callback
                fullscreen.addView(view, 0, FrameLayout.LayoutParams(-1, -1))
                fullscreen.visibility = View.VISIBLE
            }

            override fun onHideCustomView() { exitFullscreen(notify = false) }

            // Janelas de aviso da página não podem abrir aqui: respondem "OK" sozinhas.
            override fun onJsAlert(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
                result.confirm(); return true
            }

            override fun onJsConfirm(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
                result.confirm(); return true
            }

            override fun onJsPrompt(view: WebView, url: String?, message: String?, defaultValue: String?, result: JsPromptResult): Boolean {
                result.confirm(defaultValue); return true
            }
        }
        body.addView(web, FrameLayout.LayoutParams(-1, -1))

        // Setas para rolar a página (além de arrastar o dedo).
        val arrows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        fun arrow(text: String, dir: Int) = TextView(context).apply {
            this.text = text
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xAA202024.toInt()) }
            setOnClickListener {
                // Rola também as páginas que têm rolagem própria por dentro.
                web.evaluateJavascript("window.scrollBy({top:${dir}*window.innerHeight*0.8,behavior:'smooth'})", null)
            }
        }
        arrows.addView(arrow("▲", -1), LinearLayout.LayoutParams(dp(52), dp(52)).apply { bottomMargin = dp(14) })
        arrows.addView(arrow("▼", +1), LinearLayout.LayoutParams(dp(52), dp(52)))
        body.addView(arrows, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.CENTER_VERTICAL).apply { rightMargin = dp(10) })
        content.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))

        fullscreen = FrameLayout(context).apply {
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
        }
        fullscreen.addView(
            pill("✕ Sair da tela cheia") { exitFullscreen(notify = true) }.apply { alpha = 0.7f },
            FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { setMargins(dp(16), dp(16), 0, 0) }
        )
        root.addView(fullscreen, FrameLayout.LayoutParams(-1, -1))
    }

    private fun exitFullscreen(notify: Boolean) {
        val view = customView ?: return
        fullscreen.removeView(view)
        fullscreen.visibility = View.GONE
        customView = null
        val cb = customCallback
        customCallback = null
        if (notify) cb?.onCustomViewHidden()
    }

    /** Escreve o texto no campo que está selecionado na página. */
    private fun typeIntoPage(text: String) {
        val js = """
            (function(t){
              var e=document.activeElement;
              if(!e||e===document.body){return;}
              if(e.isContentEditable){document.execCommand('insertText',false,t);return;}
              var p=e.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;
              var d=Object.getOwnPropertyDescriptor(p,'value');
              if(d&&d.set){d.set.call(e,t);}else{e.value=t;}
              e.dispatchEvent(new Event('input',{bubbles:true}));
              e.dispatchEvent(new Event('change',{bubbles:true}));
            })(${JSONObject.quote(text)});
        """.trimIndent()
        web.evaluateJavascript(js, null)
    }

    private fun pressEnter() {
        web.requestFocus()
        web.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        web.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
    }
}
