package com.wanderlust.app.weather

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import com.wanderlust.app.data.Geocoder
import com.wanderlust.app.data.TripFull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

data class DayWeather(
    val date: LocalDate,
    val code: Int,
    val maxC: Double,
    val minC: Double,
    val rainChance: Int?,
    val typical: Boolean,
    /** City the weather is for, e.g. "Taipei". */
    val place: String = "",
) {
    val emoji get() = Weather.emoji(code)
    val label get() = Weather.label(code)
    fun temps(): String = "${Weather.temp(maxC)}/${Weather.temp(minC)}"
}

/** Weather for each trip day (at that day's planned location) and for each planned place. */
data class TripWeather(
    val days: Map<LocalDate, DayWeather> = emptyMap(),
    val places: Map<Long, DayWeather> = emptyMap(),
) {
    fun isEmpty() = days.isEmpty()
}

/** Where the traveler is on a day, and why we think so. */
data class DayLocation(val lat: Double, val lon: Double, val label: String)

/**
 * Weather from Open-Meteo (free, no key) that follows the itinerary: each day uses the city of the
 * places planned that day, else that night's hotel, else where that day's flight/train arrives, else the
 * previous day's city / the trip destination. Days within 16 days get the forecast; later days get the
 * weather on the same dates last year ("typical").
 */
object Weather {
    private val weatherCache = ConcurrentHashMap<String, Pair<Long, Map<LocalDate, DayWeather>>>()
    private val fahrenheit = Locale.getDefault().country in setOf("US", "LR", "MM")

    fun temp(c: Double): String = if (fahrenheit) "${(c * 9 / 5 + 32).roundToInt()}°" else "${c.roundToInt()}°"

    /** Test hook: replaces network weather. */
    @Volatile var testOverride: ((TripFull) -> TripWeather)? = null

    suspend fun forTrip(context: Context, full: TripFull): TripWeather = withContext(Dispatchers.IO) {
        testOverride?.let { return@withContext it(full) }
        val t = full.trip
        val start = t.startDate ?: return@withContext TripWeather()
        val end = t.endDate ?: start
        val today = LocalDate.now()
        if (end.isBefore(today)) return@withContext TripWeather()
        val from = maxOf(start, today)
        val dates = generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(end) }.toList()

        val geo = GeoCache(context)
        val dayLocations = locateDays(full, dates, geo)
        // fetch each distinct city once (rounded to ~10 km)
        fun key(l: DayLocation) = "${(l.lat * 10).roundToInt()},${(l.lon * 10).roundToInt()}"
        val placeLocs = full.places.filter { it.latitude != null && it.visitAt != null && it.visitAt.toLocalDate() in dates }
            .associate { it.id to DayLocation(it.latitude!!, it.longitude!!, it.city) }
        val series = (dayLocations.values + placeLocs.values).distinctBy(::key).associate { l -> key(l) to runCatching { fetch(l, from, end) }.getOrDefault(emptyMap()) }

        TripWeather(
            days = dayLocations.mapNotNull { (d, l) -> series[key(l)]?.get(d)?.copy(place = l.label)?.let { d to it } }.toMap(),
            places = placeLocs.mapNotNull { (id, l) ->
                val d = full.places.first { it.id == id }.visitAt!!.toLocalDate()
                series[key(l)]?.get(d)?.copy(place = l.label)?.let { id to it }
            }.toMap(),
        )
    }

    /** Works out where the traveler is each day from the itinerary. */
    internal suspend fun locateDays(full: TripFull, dates: List<LocalDate>, geo: GeoCache): Map<LocalDate, DayLocation> {
        val out = linkedMapOf<LocalDate, DayLocation>()
        val destination = full.trip.destination.takeIf { it.isNotBlank() }?.let { geo.locate(it) }
        var previous: DayLocation? = null
        for (d in dates) {
            // 1. places planned that day: the city with most stops
            val todays = full.places.filter { it.latitude != null && it.visitAt?.toLocalDate() == d }
            val fromPlaces = todays.groupBy { it.city.ifBlank { "${(it.latitude!! * 10).roundToInt()},${(it.longitude!! * 10).roundToInt()}" } }
                .maxByOrNull { it.value.size }?.value?.let { ps ->
                    DayLocation(ps.map { it.latitude!! }.average(), ps.map { it.longitude!! }.average(), ps.first().city.ifBlank { ps.first().name })
                }
            // 2. that night's hotel
            val fromHotel = if (fromPlaces == null) full.lodgings.firstOrNull { l ->
                val ci = l.checkIn?.toLocalDate(); val co = l.checkOut?.toLocalDate()
                ci != null && !d.isBefore(ci) && (co == null || d.isBefore(co))
            }?.let { l -> geo.locate(l.address.ifBlank { l.name }, fallback = "${l.name}, ${full.trip.destination}") } else null
            // 3. where today's flight/train arrives
            val fromTransport = if (fromPlaces == null && fromHotel == null) full.transports
                .filter { (it.arrival ?: it.departure)?.toLocalDate() == d }.maxByOrNull { it.arrival ?: it.departure!! }
                ?.let { tr -> geo.locate(tr.toLocation.ifBlank { "${tr.toCode} airport" }, fallback = tr.toCode.takeIf { it.isNotBlank() }?.let { "$it airport" }) } else null
            val loc = fromPlaces ?: fromHotel ?: fromTransport ?: previous ?: destination
            if (loc != null) { out[d] = loc; previous = loc }
        }
        // days before the first known location take the first one
        out.values.firstOrNull()?.let { first -> dates.forEach { d -> if (d !in out) out[d] = first } }
        return out
    }

    private fun fetch(l: DayLocation, from: LocalDate, end: LocalDate): Map<LocalDate, DayWeather> {
        val cacheKey = "${(l.lat * 10).roundToInt()},${(l.lon * 10).roundToInt()}-$from-$end"
        weatherCache[cacheKey]?.let { (at, m) -> if (System.currentTimeMillis() - at < 3 * 3600_000L) return m }
        val result = mutableMapOf<LocalDate, DayWeather>()
        val forecastEnd = LocalDate.now().plusDays(15)
        if (!from.isAfter(forecastEnd)) {
            daily("https://api.open-meteo.com/v1/forecast?latitude=${l.lat}&longitude=${l.lon}&timezone=auto" +
                "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max&start_date=$from&end_date=${minOf(end, forecastEnd)}", false)
                .forEach { result[it.date] = it }
        }
        val rest = generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(end) }.filter { it !in result }.toList()
        if (rest.isNotEmpty()) {
            daily("https://archive-api.open-meteo.com/v1/archive?latitude=${l.lat}&longitude=${l.lon}&timezone=auto" +
                "&daily=weather_code,temperature_2m_max,temperature_2m_min&start_date=${rest.first().minusYears(1)}&end_date=${rest.last().minusYears(1)}", true)
                .forEach { w -> val d = w.date.plusYears(1); if (d in rest) result[d] = w.copy(date = d) }
        }
        weatherCache[cacheKey] = System.currentTimeMillis() to result
        return result
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

/** Remembers place-name → coordinates lookups so hotels/airports are geocoded once (Nominatim asks for ≤1 request/s). */
internal class GeoCache(context: Context) {
    private val prefs = context.getSharedPreferences("wanderlust_geocache", Context.MODE_PRIVATE)
    private var lastNetwork = 0L

    suspend fun locate(query: String, fallback: String? = null): DayLocation? =
        lookup(query) ?: fallback?.takeIf { it.isNotBlank() && it != query }?.let { lookup(it) }

    private suspend fun lookup(query: String): DayLocation? {
        val q = query.trim().lowercase()
        if (q.isBlank()) return null
        prefs.getString(q, null)?.let { s ->
            if (s == "-") return null
            val (lat, lon, label) = s.split('|', limit = 3)
            return DayLocation(lat.toDouble(), lon.toDouble(), label)
        }
        val wait = 1100 - (System.currentTimeMillis() - lastNetwork)
        if (wait > 0) delay(wait)
        lastNetwork = System.currentTimeMillis()
        val r = runCatching { Geocoder.search(query).firstOrNull() }.getOrElse { return null } // network error: don't cache
        if (r == null) { prefs.edit().putString(q, "-").apply(); return null }
        val label = r.city.ifBlank { r.name.ifBlank { query } }
        prefs.edit().putString(q, "${r.lat}|${r.lon}|$label").apply()
        return DayLocation(r.lat, r.lon, label)
    }
}

/** Weather for each trip day / planned place; empty while loading, offline, or for past trips. */
@Composable
fun rememberTripWeather(full: TripFull): State<TripWeather> {
    val context = LocalContext.current.applicationContext
    val signature = Triple(
        listOf(full.trip.id, full.trip.startDate, full.trip.endDate, full.trip.destination),
        full.places.map { listOf(it.id, it.latitude, it.longitude, it.visitAt?.toLocalDate()) },
        full.lodgings.map { listOf(it.address, it.name, it.checkIn?.toLocalDate(), it.checkOut?.toLocalDate()) } +
            full.transports.map { listOf(it.toLocation, it.toCode, it.arrival?.toLocalDate()) },
    )
    return produceState(initialValue = TripWeather(), signature) {
        value = runCatching { Weather.forTrip(context, full) }.getOrDefault(TripWeather())
    }
}
