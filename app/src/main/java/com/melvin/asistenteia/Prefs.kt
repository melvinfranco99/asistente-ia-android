package com.melvin.asistenteia

import android.content.Context

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("asistente", Context.MODE_PRIVATE)

    var apiKey: String
        get() = sp.getString("api_key", "") ?: ""
        set(v) = sp.edit().putString("api_key", v.trim()).apply()

    var model: String
        get() = sp.getString("model", MODELS.first()) ?: MODELS.first()
        set(v) = sp.edit().putString("model", v).apply()

    var wakeWord: String
        get() = sp.getString("wake_word", "asistente") ?: "asistente"
        set(v) = sp.edit().putString("wake_word", v.trim().ifEmpty { "asistente" }).apply()

    var countryCode: String
        get() = sp.getString("country_code", "34") ?: "34"
        set(v) = sp.edit().putString("country_code", v.filter { it.isDigit() }.ifEmpty { "34" }).apply()

    var continuous: Boolean
        get() = sp.getBoolean("continuous", true)
        set(v) = sp.edit().putBoolean("continuous", v).apply()

    var speak: Boolean
        get() = sp.getBoolean("speak", true)
        set(v) = sp.edit().putBoolean("speak", v).apply()

    companion object {
        val MODELS = listOf("claude-opus-5-5", "claude-sonnet-5-5", "claude-haiku-4-5")
    }
}
