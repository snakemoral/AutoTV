package com.autotv.player.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import android.view.WindowManager
import androidx.lifecycle.lifecycleScope
import com.autotv.player.player.PlayerHub
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIntent(intent)
        setContent { AutoTvTheme { AppRoot(vm) } }
        keepScreenOnWhilePlaying()
    }

    /**
     * Enquanto um vídeo toca (ou carrega para tocar), a tela do celular não apaga sozinha pelo tempo de
     * inatividade do Android. Pausado ou parado, volta ao normal.
     */
    private fun keepScreenOnWhilePlaying() {
        lifecycleScope.launch {
            // Tocando na tela do carro, o celular só mostra um aviso: aí a tela apaga normalmente.
            combine(PlayerHub.isPlaying, PlayerHub.loading, PlayerHub.nowPlaying, PlayerHub.carActive) { playing, loading, item, car ->
                item != null && !car && (playing || loading)
            }.distinctUntilChanged().collect { on ->
                if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND) {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.let { vm.handleShared(it) }
        }
    }
}
