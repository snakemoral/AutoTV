package com.autotv.player.car

import android.graphics.Color
import androidx.car.app.CarContext
import androidx.car.app.Screen
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
 *   numa tela virtual; os toques e arrastos chegam por [CarSurface].
 * Modo simples: o vídeo vai direto na superfície e os botões são os da barra do Android Auto.
 *
 * Ao voltar para a lista, o vídeo para de ser desenhado mas o ÁUDIO continua tocando.
 */
class PlayerScreen(ctx: CarContext) : Screen(ctx) {

    private val advanced = Prefs.carAdvancedUi
    private val client: CarSurface.Client =
        if (advanced) PresentationClient(carContext) { c, display -> CarPlayerPresentation(c, display) { screenManager.pop() } }
        else RawVideoClient()

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) { CarSurface.setClient(client) }
            override fun onStop(owner: LifecycleOwner) { CarSurface.clearClient(client) }
        })
        if (!advanced) {
            // No modo simples os botões e o título são do Android Auto e precisam acompanhar o player.
            combine(PlayerHub.nowPlaying, PlayerHub.isPlaying, PlayerHub.status) { _, _, _ -> }
                .drop(1)
                .onEach { invalidate() }
                .launchIn(lifecycleScope)
        }
    }

    private fun icon(res: Int): CarIcon =
        CarIcon.Builder(IconCompat.createWithResource(carContext, res)).setTint(CarColor.DEFAULT).build()

    private fun button(res: Int, onClick: () -> Unit): Action =
        Action.Builder().setIcon(icon(res)).setOnClickListener { onClick() }.build()

    override fun onGetTemplate(): Template {
        val strip = ActionStrip.Builder()
        if (advanced) {
            // O Android Auto exige pelo menos um botão na barra dele; os controles de verdade são os nossos.
            strip.addAction(button(R.drawable.ic_list) { screenManager.pop() })
        } else {
            val playing = PlayerHub.isPlaying.value
            strip.addAction(button(R.drawable.ic_prev) { PlayerHub.previous() })
                .addAction(button(if (playing) R.drawable.ic_pause else R.drawable.ic_play) { PlayerHub.togglePause() })
                .addAction(button(R.drawable.ic_next) { PlayerHub.next() })
                .addAction(button(R.drawable.ic_list) { screenManager.pop() })
        }

        val builder = NavigationTemplate.Builder()
            .setActionStrip(strip.build())
            .setBackgroundColor(CarColor.createCustom(Color.BLACK, Color.BLACK))

        if (!advanced) {
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
