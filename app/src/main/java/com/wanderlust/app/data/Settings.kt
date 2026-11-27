package com.wanderlust.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Currency
import java.util.Locale

data class AppSettings(
    val travelerName: String = "",
    val homeCity: String = "",
    val anthropicApiKey: String = "",
    val model: String = DEFAULT_MODEL,
    val homeCurrency: String = defaultCurrency(),
    val remindersEnabled: Boolean = true,
    val appLockEnabled: Boolean = false,
) {
    val aiEnabled get() = anthropicApiKey.isNotBlank()

    companion object {
        const val DEFAULT_MODEL = "claude-opus-5-5"
        val MODELS = listOf(
            "claude-opus-5-5" to "Claude Opus 5.5 (best answers)",
            "claude-sonnet-5-5" to "Claude Sonnet 5.5 (faster, cheaper)",
            "claude-haiku-5-5" to "Claude Haiku 5.5 (fastest, cheapest)",
        )
        fun defaultCurrency(): String = runCatching { Currency.getInstance(Locale.getDefault()).currencyCode }.getOrDefault("USD")
    }
}

/**
 * Preferences in private SharedPreferences. The API key is encrypted with a key in the Android Keystore,
 * and these preferences are excluded from cloud backups (see res/xml/backup_rules.xml).
 */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("wanderlust_settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<AppSettings> = _state.asStateFlow()

    private fun load(): AppSettings {
        val stored = prefs.getString("api_key", "") ?: ""
        val key = runCatching { KeyCipher.decrypt(stored) }.getOrNull() ?: ""
        val s = AppSettings(
            travelerName = prefs.getString("name", "") ?: "",
            homeCity = prefs.getString("home", "") ?: "",
            anthropicApiKey = key,
            model = prefs.getString("model", AppSettings.DEFAULT_MODEL) ?: AppSettings.DEFAULT_MODEL,
            homeCurrency = prefs.getString("home_currency", null) ?: AppSettings.defaultCurrency(),
            remindersEnabled = prefs.getBoolean("reminders", true),
            appLockEnabled = prefs.getBoolean("app_lock", false),
        )
        // migrate a key saved in plain text by v1.0/1.1
        if (stored.isNotEmpty() && !stored.startsWith("v1:")) runCatching { prefs.edit().putString("api_key", KeyCipher.encrypt(key)).apply() }
        return s
    }

    fun update(transform: (AppSettings) -> AppSettings) {
        val s = transform(_state.value)
        prefs.edit()
            .putString("name", s.travelerName)
            .putString("home", s.homeCity)
            .putString("api_key", runCatching { KeyCipher.encrypt(s.anthropicApiKey) }.getOrDefault(s.anthropicApiKey))
            .putString("model", s.model)
            .putString("home_currency", s.homeCurrency)
            .putBoolean("reminders", s.remindersEnabled)
            .putBoolean("app_lock", s.appLockEnabled)
            .apply()
        _state.value = s
    }
}
