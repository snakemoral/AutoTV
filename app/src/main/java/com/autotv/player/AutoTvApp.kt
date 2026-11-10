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
    }
}
