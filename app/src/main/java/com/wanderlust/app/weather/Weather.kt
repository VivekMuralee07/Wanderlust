package com.wanderlust.app.weather

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import com.wanderlust.app.data.Geocoder
import com.wanderlust.app.data.TripFull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

data class DayWeather(val date: LocalDate, val code: Int, val maxC: Double, val minC: Double, val rainChance: Int?, val typical: Boolean) {
    val emoji get() = Weather.emoji(code)
    val label get() = Weather.label(code)
    fun temps(): String = "${Weather.temp(maxC)}/${Weather.temp(minC)}"
}

/**
 * Weather for trip days from Open-Meteo (free, no key): the real forecast for the next 16 days,
 * and for dates further out, what the weather was on the same dates last year ("typical").
 */
object Weather {
    private val cache = ConcurrentHashMap<String, Pair<Long, Map<LocalDate, DayWeather>>>()
    private val fahrenheit = Locale.getDefault().country in setOf("US", "LR", "MM")

    fun temp(c: Double): String = if (fahrenheit) "${(c * 9 / 5 + 32).toInt()}°" else "${c.toInt()}°"

    suspend fun forTrip(full: TripFull): Map<LocalDate, DayWeather> = withContext(Dispatchers.IO) {
        val t = full.trip
        val start = t.startDate ?: return@withContext emptyMap()
        val end = t.endDate ?: start
        val today = LocalDate.now()
        if (end.isBefore(today)) return@withContext emptyMap()
        val key = "${t.id}-$start-$end-${t.destination}"
        cache[key]?.let { (at, m) -> if (System.currentTimeMillis() - at < 3 * 3600_000L) return@withContext m }

        val (lat, lon) = full.places.firstOrNull { it.latitude != null }?.let { it.latitude!! to it.longitude!! }
            ?: t.destination.takeIf { it.isNotBlank() }?.let { d -> runCatching { Geocoder.search(d).firstOrNull() }.getOrNull()?.let { it.lat to it.lon } }
            ?: return@withContext emptyMap()

        val result = mutableMapOf<LocalDate, DayWeather>()
        val forecastEnd = today.plusDays(15)
        val from = maxOf(start, today)
        if (!from.isAfter(forecastEnd)) {
            val to = minOf(end, forecastEnd)
            runCatching {
                daily("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&timezone=auto" +
                    "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max&start_date=$from&end_date=$to", typical = false)
            }.getOrNull()?.forEach { result[it.date] = it }
        }
        // typical weather (same dates last year) for days beyond the forecast range
        val rest = generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(end) }.filter { it !in result }.toList()
        if (rest.isNotEmpty()) runCatching {
            daily("https://archive-api.open-meteo.com/v1/archive?latitude=$lat&longitude=$lon&timezone=auto" +
                "&daily=weather_code,temperature_2m_max,temperature_2m_min&start_date=${rest.first().minusYears(1)}&end_date=${rest.last().minusYears(1)}", typical = true)
        }.getOrNull()?.forEach { w -> val d = w.date.plusYears(1); if (d in rest) result[d] = w.copy(date = d) }

        cache[key] = System.currentTimeMillis() to result
        result
    }

    private fun daily(url: String, typical: Boolean): List<DayWeather> {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 10_000; readTimeout = 10_000; setRequestProperty("User-Agent", "Wanderlust-Android") }
        try {
            val d = JSONObject(conn.inputStream.bufferedReader().readText()).getJSONObject("daily")
            val days = d.getJSONArray("time")
            val codes = d.getJSONArray("weather_code"); val max = d.getJSONArray("temperature_2m_max"); val min = d.getJSONArray("temperature_2m_min")
            val rain = d.optJSONArray("precipitation_probability_max")
            return (0 until days.length()).mapNotNull { i ->
                if (max.isNull(i) || min.isNull(i)) return@mapNotNull null
                DayWeather(LocalDate.parse(days.getString(i)), codes.optInt(i, 0), max.getDouble(i), min.getDouble(i),
                    rain?.takeIf { !it.isNull(i) }?.getInt(i), typical)
            }
        } finally { conn.disconnect() }
    }

    /** WMO weather interpretation codes. */
    fun emoji(code: Int) = when (code) {
        0 -> "☀️"; 1 -> "🌤️"; 2 -> "⛅"; 3 -> "☁️"; 45, 48 -> "🌫️"
        in 51..57 -> "🌦️"; in 61..67, in 80..82 -> "🌧️"; in 71..77, 85, 86 -> "🌨️"; in 95..99 -> "⛈️"; else -> "🌡️"
    }

    fun label(code: Int) = when (code) {
        0 -> "Clear"; 1 -> "Mostly clear"; 2 -> "Partly cloudy"; 3 -> "Cloudy"; 45, 48 -> "Fog"
        in 51..57 -> "Drizzle"; in 61..67 -> "Rain"; in 80..82 -> "Showers"; in 71..77, 85, 86 -> "Snow"; in 95..99 -> "Thunderstorms"; else -> ""
    }
}

/** Weather for each trip day; empty while loading, offline, or for past trips. */
@Composable
fun rememberTripWeather(full: TripFull): State<Map<LocalDate, DayWeather>> =
    produceState(initialValue = emptyMap(), full.trip.id, full.trip.startDate, full.trip.endDate, full.trip.destination, full.places.count { it.latitude != null }) {
        value = runCatching { Weather.forTrip(full) }.getOrDefault(emptyMap())
    }
