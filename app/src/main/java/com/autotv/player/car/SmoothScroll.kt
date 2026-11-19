package com.autotv.player.car

import android.view.Choreographer
import android.view.View
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Rolagem suave para as telas do carro.
 *
 * O Android Auto manda o arrasto do dedo em rajadas irregulares (às vezes nada por um instante, depois
 * vários pedaços de uma vez) e, ao soltar, uma velocidade de arremesso alta para a tela do carro. Aplicado
 * direto, isso dava trancos e, ao soltar, a lista deslizava além do esperado.
 *
 * Aqui o arrasto é distribuído pelos quadros da tela (metade do que falta a cada quadro, então responde na
 * hora mas sem trancos), um arremesso em andamento é parado assim que o dedo volta a arrastar, e a
 * velocidade do arremesso é moderada e limitada.
 */
class SmoothScroll(
    private val target: () -> View?,
    /** Arremessa com a velocidade dada (pixels/s, positivo = rolar para baixo). */
    private val fling: (View, Int) -> Unit,
    /** Para um arremesso em andamento. */
    private val stop: (View) -> Unit
) {
    private var pending = 0f
    private var posted = false
    private val frame = Choreographer.FrameCallback { step() }

    /** Arrasto: [dy] positivo = conteúdo sobe (rolar para baixo). */
    fun drag(dy: Float) {
        val v = target() ?: return
        if (!posted && pending == 0f) stop(v) // dedo voltou: para o arremesso anterior
        pending += dy
        if (!posted) {
            posted = true
            Choreographer.getInstance().postFrameCallback(frame)
        }
    }

    private fun step() {
        posted = false
        val v = target() ?: run { pending = 0f; return }
        val move = if (abs(pending) <= 3f) pending else pending * 0.5f
        val px = move.roundToInt()
        if (px != 0) v.scrollBy(0, px)
        pending -= px
        if (abs(pending) < 1f) pending = 0f
        if (pending != 0f) {
            posted = true
            Choreographer.getInstance().postFrameCallback(frame)
        }
    }

    /** Dedo solto com velocidade (pixels/s; [velocityY] positivo = dedo para baixo = rolar para cima). */
    fun release(velocityY: Float) {
        val v = target() ?: return
        // O que ainda faltava do arrasto vai de uma vez, antes do arremesso.
        if (pending != 0f) {
            v.scrollBy(0, pending.roundToInt())
            pending = 0f
        }
        val density = v.resources.displayMetrics.density
        val max = 4_500f * density
        val speed = (-velocityY * 0.55f).coerceIn(-max, max)
        if (abs(speed) > 250f * density) fling(v, speed.roundToInt())
    }

    fun cancel() {
        if (posted) Choreographer.getInstance().removeFrameCallback(frame)
        posted = false
        pending = 0f
    }
}
