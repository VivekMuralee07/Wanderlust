package com.wanderlust.app.data

import android.content.Context
import org.json.JSONObject
import java.util.Locale

/** All countries (ISO codes + localized names) and simplified outlines for the world map. */
object Countries {
    data class Country(val code: String, val name: String) {
        val flag get() = flagEmoji(code)
    }

    /** Outer rings as (lat, lon) lists, plus a label point. From Natural Earth 1:110m (public domain). */
    data class Shape(val label: Pair<Double, Double>, val rings: List<List<Pair<Double, Double>>>)

    val all: List<Country> by lazy {
        Locale.getISOCountries().map { Country(it, Locale("", it).getDisplayCountry(Locale.getDefault()).ifBlank { it }) }.sortedBy { it.name }
    }

    fun name(code: String) = Locale("", code).getDisplayCountry(Locale.getDefault()).ifBlank { code }

    @Volatile private var shapes: Map<String, Shape>? = null

    fun shapes(context: Context): Map<String, Shape> = shapes ?: synchronized(this) {
        shapes ?: runCatching {
            val o = JSONObject(context.assets.open("countries.json").bufferedReader().readText())
            o.keys().asSequence().associateWith { cc ->
                val c = o.getJSONObject(cc)
                val l = c.getJSONArray("l")
                val polys = c.getJSONArray("p")
                Shape(
                    label = l.getDouble(1) to l.getDouble(0),
                    rings = (0 until polys.length()).map { i ->
                        val ring = polys.getJSONArray(i)
                        (0 until ring.length()).map { j -> ring.getJSONArray(j).let { pt -> pt.getDouble(1) to pt.getDouble(0) } }
                    },
                )
            }
        }.getOrDefault(emptyMap()).also { shapes = it }
    }
}
