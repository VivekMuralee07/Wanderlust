package com.wanderlust.app.domain

import com.wanderlust.app.data.PlaceCategory
import com.wanderlust.app.data.TransportType
import com.wanderlust.app.data.TripFull
import java.time.temporal.ChronoUnit

data class JournalSummary(
    val headline: String,
    val stats: List<Pair<String, String>>,     // label -> value
    val moodTimeline: List<Pair<String, String>>, // day label -> mood emoji
    val highlights: List<String>,
    val topWords: List<String>,
    val categoryBreakdown: List<Pair<String, Int>>,
    val narrative: String,
)

/**
 * Builds an on-device summary of a trip journal: key stats, mood timeline, highlights and a short
 * narrative. Works fully offline; the AI story (if a key is configured) is generated separately.
 */
object JournalSummarizer {

    private val stopWords = setOf(
        "the", "and", "a", "an", "to", "of", "in", "on", "for", "with", "at", "is", "it", "was", "we", "i",
        "my", "our", "were", "that", "this", "had", "have", "but", "so", "as", "be", "by", "from", "then",
        "there", "they", "you", "me", "us", "are", "or", "not", "just", "very", "really", "got", "went",
        "after", "before", "into", "out", "up", "about", "all", "some", "more", "which", "when", "what",
        "day", "today", "also", "did", "its", "it's", "been", "would", "could", "one", "two", "back", "over",
    )

    fun summarize(full: TripFull): JournalSummary {
        val t = full.trip
        val entries = full.journal.sortedBy { it.date }
        val words = entries.flatMap { (it.title + " " + it.content).lowercase().split(Regex("[^\\p{L}']+")) }
            .filter { it.length > 3 && it !in stopWords }
        val topWords = words.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(8).map { it.key }
        val totalWords = entries.sumOf { it.content.split(Regex("\\s+")).count { w -> w.isNotBlank() } }

        val flights = full.transports.count { TransportType.of(it.type) == TransportType.PLANE }
        val visited = full.places.count { it.visited }
        val nights = full.lodgings.sumOf { l ->
            val ci = l.checkIn?.toLocalDate(); val co = l.checkOut?.toLocalDate()
            if (ci != null && co != null) ChronoUnit.DAYS.between(ci, co).coerceAtLeast(0) else 0L
        }
        val countries = full.places.mapNotNull { it.countryName.takeIf { c -> c.isNotBlank() } }.distinct()
        val days = tripLengthDays(t.startDate, t.endDate)

        val stats = buildList {
            if (days > 0) add("Days" to "$days")
            add("Journal entries" to "${entries.size}")
            add("Words written" to "$totalWords")
            add("Places visited" to "$visited / ${full.places.size}")
            if (flights > 0) add("Flights" to "$flights")
            if (nights > 0) add("Hotel nights" to "$nights")
            if (countries.isNotEmpty()) add("Countries" to "${countries.size}")
            add("Photos" to "${full.memories.size}")
        }

        val moods = entries.map { it.date.format(dayFmt) to it.mood }
        val highlights = entries.mapNotNull { e ->
            val sentence = e.content.split(Regex("(?<=[.!?])\\s+")).firstOrNull { it.length > 20 } ?: e.content.takeIf { it.isNotBlank() }
            sentence?.let { "${e.mood} ${e.date.format(dayFmt)} — ${e.title}: ${it.trim().take(180)}" }
        }.take(6)

        val categories = full.places.groupingBy { PlaceCategory.of(it.category).let { c -> "${c.emoji} ${c.label}" } }
            .eachCount().entries.sortedByDescending { it.value }.map { it.key to it.value }

        val dominantMood = entries.groupingBy { it.mood }.eachCount().maxByOrNull { it.value }?.key
        val headline = when {
            entries.isEmpty() -> "Your story of ${t.destination.ifBlank { t.name }} is waiting to be written"
            dominantMood != null -> "$dominantMood ${days.takeIf { it > 0 }?.let { "$it days" } ?: "A journey"} in ${t.destination.ifBlank { t.name }}"
            else -> t.name
        }

        val narrative = buildString {
            val where = t.destination.ifBlank { t.name }
            if (days > 0) append("Over $days days in $where") else append("On this trip to $where")
            append(", you wrote ${entries.size} journal ${if (entries.size == 1) "entry" else "entries"}")
            if (visited > 0) append(" and explored $visited ${if (visited == 1) "place" else "places"}")
            append(". ")
            if (categories.isNotEmpty()) append("Your favourite kind of stop was ${categories.first().first.substringAfter(' ').lowercase()}. ")
            if (topWords.size >= 3) append("The words that kept coming back: ${topWords.take(3).joinToString(", ")}. ")
            entries.firstOrNull()?.let { append("It began with “${it.title}”") }
            entries.lastOrNull()?.takeIf { entries.size > 1 }?.let { append(" and closed with “${it.title}”") }
            if (entries.isNotEmpty()) append(".")
        }

        return JournalSummary(headline, stats, moods, highlights, topWords, categories, narrative)
    }

    /** Journal as plain text, for the AI story prompt. */
    fun journalText(full: TripFull): String = buildString {
        appendLine("Trip: ${full.trip.name} — ${full.trip.destination} (${dateRange(full.trip.startDate, full.trip.endDate)})")
        full.journal.sortedBy { it.date }.forEach {
            appendLine()
            appendLine("## ${it.date.format(longDayFmt)} ${it.mood} ${it.title}")
            appendLine(it.content)
        }
        val visited = full.places.filter { it.visited }
        if (visited.isNotEmpty()) {
            appendLine()
            appendLine("Places visited: " + visited.joinToString { p -> p.name + (p.rating?.let { r -> " (${r}★)" } ?: "") })
        }
    }
}
