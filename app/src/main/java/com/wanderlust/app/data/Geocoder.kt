package com.wanderlust.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Place search using OpenStreetMap Nominatim — the same geocoder AdventureLog uses by default.
 * Please respect the Nominatim usage policy (max 1 request/second, identifying User-Agent).
 */
object Geocoder {
    data class Result(
        val displayName: String,
        val name: String,
        val lat: Double,
        val lon: Double,
        val city: String,
        val country: String,
        val countryCode: String,
    )

    suspend fun search(query: String): List<Result> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val url = URL(
            "https://nominatim.openstreetmap.org/search?format=jsonv2&addressdetails=1&limit=6&q=" +
                URLEncoder.encode(query, "UTF-8")
        )
        val conn = (url.openConnection() as HttpURLConnection).apply {
            setRequestProperty("User-Agent", "Wanderlust-Android/1.0")
            connectTimeout = 10_000
            readTimeout = 10_000
        }
        try {
            val body = conn.inputStream.bufferedReader().readText()
            val arr = JSONArray(body)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val addr = o.optJSONObject("address")
                Result(
                    displayName = o.optString("display_name"),
                    name = o.optString("name").ifBlank { o.optString("display_name").substringBefore(',') },
                    lat = o.optString("lat").toDouble(),
                    lon = o.optString("lon").toDouble(),
                    city = addr?.let { it.optString("city").ifBlank { it.optString("town").ifBlank { it.optString("village") } } } ?: "",
                    country = addr?.optString("country") ?: "",
                    countryCode = addr?.optString("country_code")?.uppercase() ?: "",
                )
            }
        } finally {
            conn.disconnect()
        }
    }
}

/** Converts an ISO country code ("JP") into its flag emoji (🇯🇵). */
fun flagEmoji(countryCode: String): String {
    if (countryCode.length != 2) return "🏳️"
    val cc = countryCode.uppercase()
    val first = Character.codePointAt(cc, 0) - 0x41 + 0x1F1E6
    val second = Character.codePointAt(cc, 1) - 0x41 + 0x1F1E6
    return String(Character.toChars(first)) + String(Character.toChars(second))
}
