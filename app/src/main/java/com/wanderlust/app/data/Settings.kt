package com.wanderlust.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AppSettings(
    val travelerName: String = "",
    val homeCity: String = "",
    val anthropicApiKey: String = "",
    val model: String = DEFAULT_MODEL,
) {
    val aiEnabled get() = anthropicApiKey.isNotBlank()

    companion object {
        const val DEFAULT_MODEL = "claude-opus-5-5"
        val MODELS = listOf(
            "claude-opus-5-5" to "Claude Opus 5.5 (best answers)",
            "claude-sonnet-5-5" to "Claude Sonnet 5.5 (faster, cheaper)",
            "claude-haiku-5-5" to "Claude Haiku 5.5 (fastest, cheapest)",
        )
    }
}

/**
 * Stores preferences in private SharedPreferences. App backup is disabled in the manifest,
 * so the API key never leaves the device except in requests to api.anthropic.com.
 */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("wanderlust_settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<AppSettings> = _state.asStateFlow()

    private fun load() = AppSettings(
        travelerName = prefs.getString("name", "") ?: "",
        homeCity = prefs.getString("home", "") ?: "",
        anthropicApiKey = prefs.getString("api_key", "") ?: "",
        model = prefs.getString("model", AppSettings.DEFAULT_MODEL) ?: AppSettings.DEFAULT_MODEL,
    )

    fun update(transform: (AppSettings) -> AppSettings) {
        val s = transform(_state.value)
        prefs.edit()
            .putString("name", s.travelerName)
            .putString("home", s.homeCity)
            .putString("api_key", s.anthropicApiKey)
            .putString("model", s.model)
            .apply()
        _state.value = s
    }
}
