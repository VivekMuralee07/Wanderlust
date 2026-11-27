package com.wanderlust.app.expenses

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

/**
 * Exchange rates from the free open.er-api.com service ("Rates By Exchange Rate API"),
 * cached for 12 hours so expenses work offline with the last known rates.
 */
class ExchangeRates(context: Context) {
    private val prefs = context.getSharedPreferences("wanderlust_fx", Context.MODE_PRIVATE)

    data class Rates(val base: String, val rates: Map<String, Double>, val fetchedAt: Long)

    private fun cached(base: String): Rates? = prefs.getString("rates_$base", null)?.let { parse(base, it, prefs.getLong("at_$base", 0)) }

    private fun parse(base: String, json: String, at: Long): Rates? = runCatching {
        val r = JSONObject(json).getJSONObject("rates")
        Rates(base, r.keys().asSequence().associateWith { r.getDouble(it) }, at)
    }.getOrNull()

    /** Rates where 1 [base] = rates[X] units of X. */
    suspend fun rates(base: String): Rates? = withContext(Dispatchers.IO) {
        val c = cached(base)
        if (c != null && System.currentTimeMillis() - c.fetchedAt < 12 * 3600_000L) return@withContext c
        runCatching {
            val conn = (URL("https://open.er-api.com/v6/latest/$base").openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000; readTimeout = 10_000; setRequestProperty("User-Agent", "Wanderlust-Android")
            }
            try {
                val body = conn.inputStream.bufferedReader().readText()
                if (JSONObject(body).optString("result") != "success") error("rates unavailable")
                val now = System.currentTimeMillis()
                prefs.edit().putString("rates_$base", body).putLong("at_$base", now).apply()
                parse(base, body, now)
            } finally { conn.disconnect() }
        }.getOrNull() ?: c
    }

    /** Converts [amount] in [from] to [to]; null if no rate is known. */
    suspend fun convert(amount: Double, from: String, to: String): Double? {
        if (from == to) return amount
        val r = rates(to) ?: return null
        val perTo = r.rates[from] ?: return null // 1 `to` = perTo `from`
        return amount / perTo
    }

    companion object {
        val COMMON = listOf("USD", "EUR", "GBP", "INR", "JPY", "TWD", "CNY", "HKD", "SGD", "THB", "KRW", "AUD", "NZD", "CAD", "CHF",
            "AED", "MYR", "IDR", "VND", "PHP", "LKR", "NPR", "TRY", "ZAR", "SEK", "NOK", "DKK", "MXN", "BRL", "SAR", "QAR", "EGP")

        fun format(amount: Double, currency: String): String = runCatching {
            NumberFormat.getCurrencyInstance(Locale.getDefault()).apply {
                this.currency = Currency.getInstance(currency)
                maximumFractionDigits = if (amount >= 1000) 0 else 2
            }.format(amount)
        }.getOrElse { "%.2f %s".format(amount, currency) }

        fun name(code: String): String = runCatching { Currency.getInstance(code).getDisplayName(Locale.getDefault()) }.getOrDefault(code)
    }
}
