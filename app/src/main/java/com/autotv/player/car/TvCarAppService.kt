package com.autotv.player.car

import android.content.Intent
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.ScreenManager
import com.autotv.player.data.Account
import com.autotv.player.data.Store
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

class TvCarAppService : CarAppService() {
    // Permite qualquer host: necessário para APK instalado fora da Play Store.
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(): Session = object : Session() {
        init {
            // Carro desconectado: pausa, para o vídeo não continuar tocando sozinho no celular.
            lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
                override fun onDestroy(owner: androidx.lifecycle.LifecycleOwner) {
                    com.autotv.player.player.PlayerHub.onCarDisconnected()
                }
            })
        }

        override fun onCreateScreen(intent: Intent): Screen {
            // A superfície do carro é registrada uma vez por conexão; as telas se revezam nela.
            CarSurface.init(carContext)
            com.autotv.player.data.HistorySync.syncInBackground()
            // Modo avançado: tela inicial com abas, aberta em "Em alta". Modo simples: menu em lista.
            val home = if (com.autotv.player.data.Prefs.carAdvancedUi) HubScreen(carContext) else HomeScreen(carContext)
            // Vídeo interrompido pela desconexão: volta a tocar de onde parou e abre direto no player
            // (a tela inicial fica atrás, para o botão de voltar).
            if (com.autotv.player.player.PlayerHub.resumeInCar()) {
                carContext.getCarService(ScreenManager::class.java).push(home)
                return PlayerScreen(carContext)
            }
            return home
        }
    }
}
