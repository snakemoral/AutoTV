package com.autotv.player.car

import android.content.Context
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.util.DisplayMetrics
import android.view.Display
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import com.autotv.player.player.PlayerHub

/** Uma interface desenhada pelo app na tela do carro (dentro de uma tela virtual). */
interface SurfaceUi {
    fun open()
    fun release()
    fun setInsets(left: Int, top: Int, right: Int, bottom: Int)
    fun dispatchTap(x: Float, y: Float)
    /** Arrasto do dedo: distâncias em pixels desde o último aviso (para cima = distanceY positivo). */
    fun onScroll(distanceX: Float, distanceY: Float) {}
    /** Dedo solto com velocidade (pixels por segundo; para baixo = velocityY positivo). */
    fun onFling(velocityX: Float, velocityY: Float) {}
}

/**
 * Dono único da "superfície" que o Android Auto cede ao app. A tela que estiver na frente se
 * registra como cliente e recebe a superfície, os toques e os arrastos.
 */
object CarSurface {
    interface Client {
        fun attach(container: SurfaceContainer)
        fun detach()
        fun insets(stable: Rect?) {}
        fun tap(x: Float, y: Float) {}
        fun scroll(dx: Float, dy: Float) {}
        fun fling(vx: Float, vy: Float) {}
    }

    private var container: SurfaceContainer? = null
    private var stable: Rect? = null
    private var client: Client? = null

    private val callback = object : SurfaceCallback {
        override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
            if (container != null) client?.detach()
            container = surfaceContainer
            if (surfaceContainer.surface != null) {
                client?.attach(surfaceContainer)
                client?.insets(stable)
            }
        }

        override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
            client?.detach()
            container = null
        }

        override fun onVisibleAreaChanged(visibleArea: Rect) {}

        override fun onStableAreaChanged(stableArea: Rect) {
            stable = Rect(stableArea)
            client?.insets(stable)
        }

        override fun onScroll(distanceX: Float, distanceY: Float) {
            client?.scroll(distanceX, distanceY)
        }

        override fun onFling(velocityX: Float, velocityY: Float) {
            client?.fling(velocityX, velocityY)
        }

        @Suppress("OPT_IN_OVERRIDE_ERROR", "OPT_IN_USAGE_ERROR")
        override fun onClick(x: Float, y: Float) {
            client?.tap(x, y)
        }
    }

    /** Chamado uma vez quando o carro conecta. */
    fun init(ctx: CarContext) {
        client = null
        container = null
        stable = null
        ctx.getCarService(AppManager::class.java).setSurfaceCallback(callback)
    }

    fun setClient(c: Client) {
        if (client === c) return
        val had = container
        if (had != null) client?.detach()
        client = c
        if (had?.surface != null) {
            c.attach(had)
            c.insets(stable)
        }
    }

    fun clearClient(c: Client) {
        if (client !== c) return
        if (container != null) c.detach()
        client = null
    }
}

/** Cliente que mostra uma interface própria ([SurfaceUi]) numa tela virtual sobre a superfície do carro. */
class PresentationClient(
    private val ctx: Context,
    private val factory: (Context, Display) -> SurfaceUi
) : CarSurface.Client {
    private var virtualDisplay: VirtualDisplay? = null
    var ui: SurfaceUi? = null
        private set

    override fun attach(container: SurfaceContainer) {
        detach()
        val surface = container.surface ?: return
        val dm = ctx.getSystemService(DisplayManager::class.java)
        val vd = dm.createVirtualDisplay(
            "AutoTV", container.width, container.height, container.dpi, surface,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
        )
        virtualDisplay = vd
        ui = factory(ctx, vd.display).also { it.open() }
    }

    override fun detach() {
        ui?.release()
        ui = null
        virtualDisplay?.release()
        virtualDisplay = null
    }

    override fun insets(stable: Rect?) {
        val p = ui ?: return
        val vd = virtualDisplay ?: return
        if (stable == null) { p.setInsets(0, 0, 0, 0); return }
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        vd.display.getRealMetrics(dm)
        p.setInsets(
            stable.left.coerceAtLeast(0),
            stable.top.coerceAtLeast(0),
            (dm.widthPixels - stable.right).coerceAtLeast(0),
            (dm.heightPixels - stable.bottom).coerceAtLeast(0)
        )
    }

    override fun tap(x: Float, y: Float) { ui?.dispatchTap(x, y) }
    override fun scroll(dx: Float, dy: Float) { ui?.onScroll(dx, dy) }
    override fun fling(vx: Float, vy: Float) { ui?.onFling(vx, vy) }
}

/** Modo simples: o vídeo vai direto na superfície, sem interface própria. */
class RawVideoClient : CarSurface.Client {
    override fun attach(container: SurfaceContainer) {
        container.surface?.let { PlayerHub.attachCarSurface(it) }
    }

    override fun detach() {
        PlayerHub.detachCarSurface()
    }
}
