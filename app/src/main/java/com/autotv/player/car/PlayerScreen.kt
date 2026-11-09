package com.autotv.player.car

import android.graphics.Color
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.view.Surface
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.MessageInfo
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.autotv.player.R
import com.autotv.player.data.Prefs
import com.autotv.player.player.PlayerHub
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * Mostra o vídeo na "superfície" que o Android Auto cede a apps de navegação (NavigationTemplate).
 *
 * Modo avançado (padrão): a gente desenha a interface inteira (botões, progresso, brilho, legendas)
 *   numa tela virtual e o Android Auto exibe o resultado; toques chegam por SurfaceCallback.onClick.
 * Modo simples: o vídeo vai direto na superfície e os botões são os da barra do Android Auto.
 *
 * Ao voltar para a lista, o vídeo para de ser desenhado mas o ÁUDIO continua tocando.
 */
class PlayerScreen(ctx: CarContext) : Screen(ctx) {

    private val advanced = Prefs.carAdvancedUi
    private var virtualDisplay: VirtualDisplay? = null
    private var ui: CarPlayerPresentation? = null
    private var lastStable: Rect? = null

    private val surfaceCallback = object : SurfaceCallback {
        override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
            val surface: Surface = surfaceContainer.surface ?: return
            if (!advanced) {
                PlayerHub.attachCarSurface(surface)
                return
            }
            releaseAdvanced()
            val dm = carContext.getSystemService(DisplayManager::class.java)
            val vd = dm.createVirtualDisplay(
                "AutoTV",
                surfaceContainer.width,
                surfaceContainer.height,
                surfaceContainer.dpi,
                surface,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
            )
            virtualDisplay = vd
            val p = CarPlayerPresentation(carContext, vd.display) { screenManager.pop() }
            ui = p
            p.show()
            applyInsets(lastStable)
        }

        override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
            if (advanced) releaseAdvanced() else PlayerHub.detachCarSurface()
        }

        override fun onStableAreaChanged(stableArea: Rect) {
            lastStable = Rect(stableArea)
            applyInsets(stableArea)
        }

        @Suppress("OPT_IN_OVERRIDE_ERROR", "OPT_IN_USAGE_ERROR")
        override fun onClick(x: Float, y: Float) {
            ui?.dispatchTap(x, y)
        }
    }

    private fun applyInsets(stable: Rect?) {
        val p = ui ?: return
        val vd = virtualDisplay ?: return
        val dm = android.util.DisplayMetrics()
        vd.display.getRealMetrics(dm)
        if (stable == null) { p.setInsets(0, 0, 0, 0); return }
        p.setInsets(
            stable.left.coerceAtLeast(0),
            stable.top.coerceAtLeast(0),
            (dm.widthPixels - stable.right).coerceAtLeast(0),
            (dm.heightPixels - stable.bottom).coerceAtLeast(0)
        )
    }

    private fun releaseAdvanced() {
        ui?.release()
        ui = null
        virtualDisplay?.release()
        virtualDisplay = null
        PlayerHub.setCarActive(false)
    }

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                carContext.getCarService(AppManager::class.java).setSurfaceCallback(surfaceCallback)
            }

            override fun onDestroy(owner: LifecycleOwner) {
                carContext.getCarService(AppManager::class.java).setSurfaceCallback(null)
                if (advanced) releaseAdvanced() else PlayerHub.detachCarSurface()
            }
        })
        combine(PlayerHub.nowPlaying, PlayerHub.isPlaying, PlayerHub.status) { _, _, _ -> }
            .drop(1)
            .onEach { invalidate() }
            .launchIn(lifecycleScope)
    }

    private fun icon(res: Int): CarIcon =
        CarIcon.Builder(IconCompat.createWithResource(carContext, res)).setTint(CarColor.DEFAULT).build()

    private fun button(res: Int, onClick: () -> Unit): Action =
        Action.Builder().setIcon(icon(res)).setOnClickListener { onClick() }.build()

    override fun onGetTemplate(): Template {
        val playing = PlayerHub.isPlaying.value
        // Botões de reserva do próprio Android Auto (somem sozinhos depois de alguns segundos).
        val strip = ActionStrip.Builder()
            .addAction(button(R.drawable.ic_prev) { PlayerHub.previous() })
            .addAction(button(if (playing) R.drawable.ic_pause else R.drawable.ic_play) { PlayerHub.togglePause() })
            .addAction(button(R.drawable.ic_next) { PlayerHub.next() })
            .addAction(button(R.drawable.ic_list) { screenManager.pop() })
            .build()

        val builder = NavigationTemplate.Builder()
            .setActionStrip(strip)
            .setBackgroundColor(CarColor.createCustom(Color.BLACK, Color.BLACK))

        if (!advanced) {
            // No modo simples o título fica num aviso do Android Auto.
            val title = PlayerHub.nowPlaying.value?.title ?: "AutoTV"
            builder.setNavigationInfo(
                MessageInfo.Builder(title)
                    .apply { PlayerHub.status.value?.let { setText(it) } }
                    .build()
            )
        }
        return builder.build()
    }
}
