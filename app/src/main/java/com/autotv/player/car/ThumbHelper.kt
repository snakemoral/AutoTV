package com.autotv.player.car

import androidx.car.app.Screen
import androidx.car.app.model.CarIcon
import androidx.lifecycle.lifecycleScope
import com.autotv.player.data.Thumbs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Entrega a miniatura em cache ou um quadro cinza, e baixa as que faltam em segundo plano.
 * Redesenha a tela no máximo uma vez a cada 0,5 s para não estourar o limite de atualizações do carro.
 */
class ThumbHelper(private val screen: Screen) {
    private val pending = HashSet<String>()
    private var scheduled = false

    fun icon(url: String?): CarIcon {
        if (url.isNullOrBlank()) return Thumbs.placeholderIcon()
        Thumbs.cached(url)?.let { return Thumbs.carIcon(url, it) }
        if (pending.add(url)) {
            screen.lifecycleScope.launch {
                val bmp = withContext(Dispatchers.IO) { Thumbs.load(url) }
                if (bmp != null) scheduleInvalidate()
            }
        }
        return Thumbs.placeholderIcon()
    }

    private fun scheduleInvalidate() {
        if (scheduled) return
        scheduled = true
        screen.lifecycleScope.launch {
            delay(500)
            scheduled = false
            screen.invalidate()
        }
    }
}
