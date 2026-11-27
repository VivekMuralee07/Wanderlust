package com.wanderlust.app.domain

import com.wanderlust.app.data.LodgingType
import com.wanderlust.app.data.PlaceCategory
import com.wanderlust.app.data.TransportType
import com.wanderlust.app.data.TripFull
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

enum class EventKind { TRANSPORT, CHECK_IN, CHECK_OUT, STAY, PLACE, ACTIVITY }

data class TimelineEvent(
    val kind: EventKind,
    val time: LocalTime?,
    val endTime: LocalTime? = null,
    val emoji: String,
    val title: String,
    val subtitle: String = "",
    val detail: String = "",
    val refId: Long = 0,
    val optional: Boolean = false,
)

data class ItineraryDay(
    val index: Int,               // 1-based day number
    val date: LocalDate,
    val events: List<TimelineEvent>,
    val sleepingAt: String? = null,
)

data class VisualItinerary(
    val days: List<ItineraryDay>,
    val unscheduled: List<TimelineEvent>,
)

val timeFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
val dayFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, d MMM")
val longDayFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy")
val shortDateFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy")
val dateTimeFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy · HH:mm")

fun dateRange(start: LocalDate?, end: LocalDate?): String = when {
    start == null -> "Dates not set"
    end == null || end == start -> start.format(shortDateFmt)
    start.year == end.year && start.month == end.month -> "${start.dayOfMonth}–${end.format(shortDateFmt)}"
    start.year == end.year -> "${start.format(DateTimeFormatter.ofPattern("d MMM"))} – ${end.format(shortDateFmt)}"
    else -> "${start.format(shortDateFmt)} – ${end.format(shortDateFmt)}"
}

fun tripLengthDays(start: LocalDate?, end: LocalDate?): Int =
    if (start == null) 0 else (ChronoUnit.DAYS.between(start, end ?: start) + 1).toInt()

/**
 * Builds a day-by-day itinerary out of everything the user entered: flights/trains, hotel check-ins
 * and check-outs, places with a visit time, and custom activities. This is the data behind the
 * "visual itinerary" screen and the PDF export.
 */
object ItineraryBuilder {

    fun build(full: TripFull): VisualItinerary {
        val byDate = mutableMapOf<LocalDate, MutableList<TimelineEvent>>()
        val unscheduled = mutableListOf<TimelineEvent>()
        fun add(date: LocalDate?, e: TimelineEvent) {
            if (date == null) unscheduled += e else byDate.getOrPut(date) { mutableListOf() } += e
        }

        full.transports.forEach { t ->
            val type = TransportType.of(t.type)
            val route = listOf(t.fromCode.ifBlank { t.fromLocation }, t.toCode.ifBlank { t.toLocation })
                .filter { it.isNotBlank() }.joinToString(" → ")
            val title = buildString {
                append(type.label)
                if (t.flightNumber.isNotBlank()) append(" ${t.flightNumber}")
                if (route.isNotBlank()) append(" · $route")
            }
            val details = listOfNotNull(
                t.name.takeIf { it.isNotBlank() },
                t.arrival?.let { "Arrives ${it.format(if (it.toLocalDate() == t.departure?.toLocalDate()) timeFmt else dateTimeFmt)}" },
                t.seat.takeIf { it.isNotBlank() }?.let { "Seat $it" },
                t.gate.takeIf { it.isNotBlank() }?.let { "Gate $it" },
                t.bookingRef.takeIf { it.isNotBlank() }?.let { "Ref $it" },
            )
            add(t.departure?.toLocalDate(), TimelineEvent(EventKind.TRANSPORT, t.departure?.toLocalTime(),
                t.arrival?.toLocalTime(), type.emoji, title, details.joinToString(" · "), t.notes, t.id))
        }

        full.lodgings.forEach { l ->
            val type = LodgingType.of(l.type)
            add(l.checkIn?.toLocalDate(), TimelineEvent(EventKind.CHECK_IN, l.checkIn?.toLocalTime(), null, type.emoji,
                "Check in · ${l.name.ifBlank { type.label }}",
                listOfNotNull(l.address.takeIf { it.isNotBlank() }, l.reservationNumber.takeIf { it.isNotBlank() }?.let { "Res. $it" }).joinToString(" · "),
                l.notes, l.id))
            l.checkOut?.let { co ->
                add(co.toLocalDate(), TimelineEvent(EventKind.CHECK_OUT, co.toLocalTime(), null, "🧳",
                    "Check out · ${l.name.ifBlank { type.label }}", "", "", l.id))
            }
        }

        full.places.forEach { p ->
            val cat = PlaceCategory.of(p.category)
            add(p.visitAt?.toLocalDate(), TimelineEvent(EventKind.PLACE, p.visitAt?.toLocalTime()?.takeIf { it != LocalTime.MIDNIGHT },
                null, cat.emoji, p.name, listOf(cat.label, p.city.ifBlank { p.address }).filter { it.isNotBlank() }.joinToString(" · "),
                p.notes, p.id))
        }

        full.itinerary.forEach { i ->
            add(i.date, TimelineEvent(EventKind.ACTIVITY, i.time, i.endTime, i.emoji, i.title, "", i.notes, i.id, i.isOptional))
        }

        val trip = full.trip
        val allDates = byDate.keys.toMutableSet()
        val start = trip.startDate
        if (start != null) {
            var d: LocalDate = start
            val end = trip.endDate ?: start
            while (!d.isAfter(end)) { allDates += d; d = d.plusDays(1) }
        }
        val sorted = allDates.sorted()
        val first = trip.startDate ?: sorted.firstOrNull()
        val days = sorted.map { date ->
            val events = byDate[date].orEmpty().sortedWith(compareBy(nullsLast()) { it.time })
            val sleeping = full.lodgings.firstOrNull { l ->
                val ci = l.checkIn?.toLocalDate(); val co = l.checkOut?.toLocalDate()
                ci != null && !date.isBefore(ci) && (co == null || date.isBefore(co))
            }?.name
            ItineraryDay(
                index = if (first != null) ChronoUnit.DAYS.between(first, date).toInt() + 1 else 0,
                date = date, events = events, sleepingAt = sleeping,
            )
        }
        return VisualItinerary(days, unscheduled)
    }

    /** Plain-text version, used as context for the AI assistant and for sharing. */
    fun asText(full: TripFull): String = buildString {
        val t = full.trip
        appendLine("Trip: ${t.name} (id ${t.id})")
        if (t.destination.isNotBlank()) appendLine("Destination: ${t.destination}")
        appendLine("Dates: ${dateRange(t.startDate, t.endDate)}")
        if (t.description.isNotBlank()) appendLine("About: ${t.description}")
        val it = build(full)
        it.days.forEach { d ->
            appendLine((if (d.index >= 1) "Day ${d.index}" else "Travel day") + " – ${d.date.format(longDayFmt)}${d.sleepingAt?.let { " (staying at $it)" } ?: ""}")
            if (d.events.isEmpty()) appendLine("  (free day)")
            d.events.forEach { e ->
                append("  ${e.time?.format(timeFmt) ?: "—"} ${e.emoji} ${e.title}")
                if (e.subtitle.isNotBlank()) append(" — ${e.subtitle}")
                if (e.detail.isNotBlank()) append(" [${e.detail}]")
                appendLine()
            }
        }
        if (it.unscheduled.isNotEmpty()) {
            appendLine("Not yet scheduled:")
            it.unscheduled.forEach { e -> appendLine("  ${e.emoji} ${e.title} ${e.subtitle}") }
        }
        val packing = full.checklist
        if (packing.isNotEmpty()) {
            appendLine("Checklist: " + packing.joinToString { (if (it.isChecked) "[x] " else "[ ] ") + it.name })
        }
    }
}
