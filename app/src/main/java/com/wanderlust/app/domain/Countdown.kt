package com.wanderlust.app.domain

import com.wanderlust.app.data.Transport
import com.wanderlust.app.data.Trip
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

data class Countdown(val days: Long, val hours: Long, val minutes: Long, val seconds: Long) {
    val isOver get() = days <= 0 && hours <= 0 && minutes <= 0 && seconds <= 0
}

object Countdowns {
    /**
     * Target moment of the trip start: the first departure of the trip if one is known,
     * otherwise the start of the trip's first day.
     */
    fun target(trip: Trip, transports: List<Transport>): LocalDateTime? {
        val firstDeparture = transports.filter { it.tripId == trip.id }.mapNotNull { it.departure }.minOrNull()
        val startOfDay = trip.startDate?.atStartOfDay()
        return when {
            firstDeparture != null && startOfDay != null && firstDeparture.toLocalDate() <= trip.startDate -> firstDeparture
            else -> startOfDay ?: firstDeparture
        }
    }

    fun until(target: LocalDateTime, now: LocalDateTime = LocalDateTime.now()): Countdown {
        val d = Duration.between(now, target)
        if (d.isNegative) return Countdown(0, 0, 0, 0)
        val total = d.seconds
        return Countdown(total / 86_400, (total % 86_400) / 3600, (total % 3600) / 60, total % 60)
    }

    fun daysUntil(date: LocalDate, today: LocalDate = LocalDate.now()) = ChronoUnit.DAYS.between(today, date)

    fun label(trip: Trip, today: LocalDate = LocalDate.now()): String {
        val s = trip.startDate ?: return "Dates TBD"
        val e = trip.endDate ?: s
        return when {
            today.isBefore(s) -> {
                val n = daysUntil(s, today)
                if (n == 1L) "Tomorrow!" else "In $n days"
            }
            !today.isAfter(e) -> "Day ${ChronoUnit.DAYS.between(s, today) + 1} of ${ChronoUnit.DAYS.between(s, e) + 1}"
            else -> {
                val n = ChronoUnit.DAYS.between(e, today)
                if (n < 31) "$n days ago" else if (n < 365) "${n / 30} months ago" else "${n / 365} yr ago"
            }
        }
    }
}
