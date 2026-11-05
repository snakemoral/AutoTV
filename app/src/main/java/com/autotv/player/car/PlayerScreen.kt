package com.autotv.player.car

import android.graphics.Color
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
import com.autotv.player.player.PlayerHub
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * Mostra o vídeo na "superfície" que o Android Auto cede a apps de navegação (NavigationTemplate).
 * Ao voltar para a lista, o vídeo para de ser desenhado mas o ÁUDIO continua tocando.
 */
class PlayerScreen(ctx: CarContext) : Screen(ctx) {

    private val surfaceCallback = object : SurfaceCallback {
        override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
            val surface: Surface? = surfaceContainer.surface
            if (surface != null) PlayerHub.attachCarSurface(surface)
        }

        override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
            PlayerHub.detachCarSurface()
        }
    }

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                carContext.getCarService(AppManager::class.java).setSurfaceCallback(surfaceCallback)
            }

            override fun onDestroy(owner: LifecycleOwner) {
                carContext.getCarService(AppManager::class.java).setSurfaceCallback(null)
                PlayerHub.detachCarSurface()
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
        val strip = ActionStrip.Builder()
            .addAction(button(R.drawable.ic_prev) { PlayerHub.previous() })
            .addAction(button(if (playing) R.drawable.ic_pause else R.drawable.ic_play) { PlayerHub.togglePause() })
            .addAction(button(R.drawable.ic_next) { PlayerHub.next() })
            .addAction(button(R.drawable.ic_list) { screenManager.pop() })
            .build()

        val title = PlayerHub.nowPlaying.value?.title ?: "AutoTV"
        val info = MessageInfo.Builder(title)
            .apply { PlayerHub.status.value?.let { setText(it) } }
            .build()

        return NavigationTemplate.Builder()
            .setNavigationInfo(info)
            .setActionStrip(strip)
            .setBackgroundColor(CarColor.createCustom(Color.BLACK, Color.BLACK))
            .build()
    }
}
