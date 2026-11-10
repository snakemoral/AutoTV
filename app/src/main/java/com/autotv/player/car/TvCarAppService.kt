package com.autotv.player.car

import android.content.Intent
import androidx.car.app.CarAppService
import androidx.car.app.Screen
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
                    com.autotv.player.player.PlayerHub.pause()
                }
            })
        }

        override fun onCreateScreen(intent: Intent): Screen {
            // A superfície do carro é registrada uma vez por conexão; as telas se revezam nela.
            CarSurface.init(carContext)
            return HomeScreen(carContext)
        }
    }
}
