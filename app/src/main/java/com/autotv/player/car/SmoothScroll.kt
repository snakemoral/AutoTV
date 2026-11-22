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
 *
 * Multimídias diferentes também mandam os dados de formas diferentes. Em algumas (visto num BYD Dolphin)
 * a lista subia e depois voltava: a velocidade do arremesso chega com o sinal trocado, ou chega um último
 * arrasto ao contrário logo depois de soltar. Por isso o arremesso sempre segue a direção do arrasto que
 * acabou de acontecer, e arrastos ao contrário logo após soltar o dedo são ignorados.
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
    /** Arrasto recente (soma dos últimos instantes) e quando foi: diz a direção real do gesto. */
    private var recentSum = 0f
    private var recentAt = 0L
    /** Quando o dedo foi solto e em que direção a lista seguiu (+1 desce, -1 sobe, 0 nenhuma). */
    private var releasedAt = 0L
    private var releasedDir = 0
    private val frame = Choreographer.FrameCallback { step() }

    /** Arrasto: [dy] positivo = conteúdo sobe (rolar para baixo). */
    fun drag(dy: Float) {
        val v = target() ?: return
        val now = android.os.SystemClock.uptimeMillis()
        // Logo depois de soltar: um "arrasto" ao contrário é resto do gesto, não um gesto novo.
        if (now - releasedAt < IGNORE_AFTER_RELEASE_MS && releasedDir != 0 && sign(dy) == -releasedDir) return
        if (now - recentAt > RECENT_MS) recentSum = 0f
        recentSum += dy
        recentAt = now
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
        val now = android.os.SystemClock.uptimeMillis()
        val density = v.resources.displayMetrics.density
        val max = 4_500f * density
        var speed = (-velocityY * 0.55f).coerceIn(-max, max)
        // O arremesso segue a direção do arrasto que acabou de acontecer (há multimídias que mandam a
        // velocidade com o sinal trocado, o que fazia a lista voltar).
        val dragDir = if (now - recentAt <= RECENT_MS) sign(recentSum) else 0
        if (dragDir != 0 && sign(speed) != dragDir) speed = -speed
        releasedAt = now
        recentSum = 0f
        if (abs(speed) > 250f * density) {
            releasedDir = sign(speed)
            fling(v, speed.roundToInt())
        } else releasedDir = dragDir
    }

    private fun sign(x: Float) = if (x > 0f) 1 else if (x < 0f) -1 else 0

    private companion object {
        /** Quanto do arrasto conta como "recente" para saber a direção do gesto. */
        const val RECENT_MS = 250L
        /** Depois de soltar, por quanto tempo um arrasto ao contrário é ignorado. */
        const val IGNORE_AFTER_RELEASE_MS = 180L
    }

    fun cancel() {
        if (posted) Choreographer.getInstance().removeFrameCallback(frame)
        posted = false
        pending = 0f
    }
}
