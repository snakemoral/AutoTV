package com.autotv.player

import android.app.Application
import com.autotv.player.data.Http
import com.autotv.player.data.Prefs
import com.autotv.player.data.Store
import com.autotv.player.data.YouTube
import com.autotv.player.player.PlayerHub

class AutoTvApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        Store.init(this)
        com.autotv.player.data.Account.init(this)
        YouTube.init(Http.client)
        PlayerHub.init(this)
        // Segunda garantia: ao sair do Android Auto, pausa.
        var wasInCar = false
        runCatching {
            androidx.car.app.connection.CarConnection(this).type.observeForever { type ->
                val inCar = type == androidx.car.app.connection.CarConnection.CONNECTION_TYPE_PROJECTION
                if (wasInCar && !inCar) PlayerHub.pause()
                wasInCar = inCar
            }
        }
    }
}
