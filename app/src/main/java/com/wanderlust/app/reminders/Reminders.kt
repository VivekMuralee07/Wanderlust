package com.wanderlust.app.reminders

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.wanderlust.app.MainActivity
import com.wanderlust.app.R
import com.wanderlust.app.WanderlustApp
import com.wanderlust.app.data.Lodging
import com.wanderlust.app.data.Transport
import com.wanderlust.app.data.TransportType
import com.wanderlust.app.data.Trip
import com.wanderlust.app.domain.timeFmt
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.ZoneId

data class Reminder(val key: String, val at: LocalDateTime, val title: String, val text: String, val tripId: Long)

/** Plans and schedules trip notifications with AlarmManager (no exact-alarm permission needed). */
class ReminderScheduler(private val context: Context) {
    private val prefs = context.getSharedPreferences("wanderlust_reminders", Context.MODE_PRIVATE)

    companion object {
        const val CHANNEL = "trip_reminders"
        const val EXTRA_TRIP = "open_trip"

        /** Pure function so it can be unit-tested. */
        fun plan(trips: List<Trip>, transports: List<Transport>, lodgings: List<Lodging>, now: LocalDateTime = LocalDateTime.now()): List<Reminder> {
            val horizon = now.plusDays(60)
            val out = mutableListOf<Reminder>()
            val active = trips.filter { !it.isArchived }.associateBy { it.id }
            active.values.forEach { t ->
                val start = t.startDate ?: return@forEach
                val where = t.destination.ifBlank { t.name }
                listOf(7L to "One week to go ✈️", 3L to "3 days to go 🧳", 1L to "Tomorrow you’re off to $where! 🎉").forEach { (d, title) ->
                    out += Reminder("trip-${t.id}-$d", start.minusDays(d).atTime(9, 0), title,
                        if (d == 1L) "Double-check your packing list and tickets." else "$where is coming up on ${start.dayOfMonth} ${start.month.name.lowercase().replaceFirstChar { it.uppercase() }}.", t.id)
                }
            }
            transports.filter { it.tripId in active }.forEach { tr ->
                val dep = tr.departure ?: return@forEach
                val type = TransportType.of(tr.type)
                val label = listOf(type.label, tr.flightNumber).filter { it.isNotBlank() }.joinToString(" ")
                val to = tr.toCode.ifBlank { tr.toLocation }
                if (type == TransportType.PLANE) {
                    out += Reminder("tr-${tr.id}-checkin", dep.minusHours(24), "Check in for $label${if (to.isNotBlank()) " to $to" else ""}",
                        "Online check-in is usually open now. Departs ${dep.format(timeFmt)} tomorrow" + (tr.bookingRef.takeIf { it.isNotBlank() }?.let { " · Ref $it" } ?: ""), tr.tripId)
                    out += Reminder("tr-${tr.id}-leave", dep.minusHours(3), "Time to head to the airport ✈️",
                        "$label departs at ${dep.format(timeFmt)}" + (tr.gate.takeIf { it.isNotBlank() }?.let { " · Gate $it" } ?: ""), tr.tripId)
                } else {
                    out += Reminder("tr-${tr.id}-leave", dep.minusHours(1), "${type.emoji} $label leaves in 1 hour",
                        "Departs ${dep.format(timeFmt)} from ${tr.fromLocation.ifBlank { tr.fromCode }}", tr.tripId)
                }
            }
            lodgings.filter { it.tripId in active }.forEach { l ->
                l.checkIn?.let { ci -> out += Reminder("lo-${l.id}-in", ci.toLocalDate().atTime(9, 0).coerceAtMost(ci.minusHours(1)), "Check-in today 🏨",
                    "${l.name} from ${ci.format(timeFmt)}" + (l.reservationNumber.takeIf { it.isNotBlank() }?.let { " · Res. $it" } ?: ""), l.tripId) }
                l.checkOut?.let { co -> out += Reminder("lo-${l.id}-out", co.toLocalDate().atTime(7, 30).coerceAtMost(co.minusHours(1)), "Check-out today",
                    "Leave ${l.name} by ${co.format(timeFmt)}", l.tripId) }
            }
            return out.filter { it.at.isAfter(now) && it.at.isBefore(horizon) }.sortedBy { it.at }
        }
    }

    suspend fun reschedule() {
        val app = (context.applicationContext as WanderlustApp).container
        val am = context.getSystemService(AlarmManager::class.java)
        // cancel what we scheduled last time
        prefs.getStringSet("codes", emptySet())!!.forEach { code -> am.cancel(pending(code.toInt(), null)) }
        if (!app.settings.state.value.remindersEnabled) { prefs.edit().putStringSet("codes", emptySet()).apply(); return }
        val list = plan(app.repo.tripsOnce(), app.repo.transportsOnce(), app.repo.lodgingsOnce())
        val codes = mutableSetOf<String>()
        list.forEach { r ->
            val code = r.key.hashCode()
            codes += code.toString()
            val millis = r.at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pending(code, r))
        }
        prefs.edit().putStringSet("codes", codes).apply()
    }

    private fun pending(code: Int, r: Reminder?): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            if (r != null) { putExtra("title", r.title); putExtra("text", r.text); putExtra("trip", r.tripId); putExtra("code", code) }
        }
        return PendingIntent.getBroadcast(context, code, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}

/** Shows a reminder notification. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        ensureChannel(context)
        val tripId = intent.getLongExtra("trip", 0)
        val open = PendingIntent.getActivity(
            context, tripId.toInt(),
            Intent(context, MainActivity::class.java).putExtra(ReminderScheduler.EXTRA_TRIP, tripId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, ReminderScheduler.CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(intent.getStringExtra("title"))
            .setContentText(intent.getStringExtra("text"))
            .setStyle(NotificationCompat.BigTextStyle().bigText(intent.getStringExtra("text")))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(intent.getIntExtra("code", 0), n) }
    }

    companion object {
        fun ensureChannel(context: Context) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(ReminderScheduler.CHANNEL, "Trip reminders", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Countdowns, flight check-in and hotel check-in/out reminders"
                }
            )
        }
    }
}

/** Alarms are cleared on reboot and app update — schedule them again. */
class RescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = (context.applicationContext as WanderlustApp).container
        app.appScope.launch {
            try {
                app.reminders.reschedule()
                com.wanderlust.app.widget.CountdownWidget.refresh(context)
            } finally { pending.finish() }
        }
    }
}
