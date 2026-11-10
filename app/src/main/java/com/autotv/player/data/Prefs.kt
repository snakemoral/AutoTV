package com.autotv.player.data

import android.content.Context
import android.content.SharedPreferences

/** Preferências simples compartilhadas pelo celular e pelo carro. */
object Prefs {
    private lateinit var sp: SharedPreferences

    fun init(context: Context) {
        sp = context.applicationContext.getSharedPreferences("autotv", Context.MODE_PRIVATE)
    }

    /** Interface avançada do carro (botões, barra de progresso, brilho, legendas). false = modo simples. */
    var carAdvancedUi: Boolean
        get() = sp.getBoolean("car_adv", true)
        set(v) { sp.edit().putBoolean("car_adv", v).apply() }

    /** Brilho do vídeo no carro: 0.15 (escuro) a 1.0 (sem escurecer). */
    var brightness: Float
        get() = sp.getFloat("bright", 1f)
        set(v) { sp.edit().putFloat("bright", v.coerceIn(0.15f, 1f)).apply() }

    /** Altura de vídeo preferida (0 = automático, o melhor até 1080p). */
    var preferredHeight: Int
        get() = sp.getInt("pref_h", 0)
        set(v) { sp.edit().putInt("pref_h", v).apply() }

    /** Última página aberta no navegador. */
    var browserUrl: String
        get() = sp.getString("browser_url", null) ?: Browser.HOME
        set(v) { sp.edit().putString("browser_url", v).apply() }

    /** Idioma de legenda preferido ("" = desligada). */
    var subtitleLang: String
        get() = sp.getString("sub_lang", "") ?: ""
        set(v) { sp.edit().putString("sub_lang", v).apply() }
}
