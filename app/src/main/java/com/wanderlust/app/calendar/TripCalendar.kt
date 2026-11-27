package com.wanderlust.app.calendar

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wanderlust.app.LocalApp
import com.wanderlust.app.data.LodgingType
import com.wanderlust.app.data.PlaceCategory
import com.wanderlust.app.data.TransportType
import com.wanderlust.app.data.TripFull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.TimeZone

data class CalEvent(
    val uid: String,
    val title: String,
    val start: LocalDateTime,
    val end: LocalDateTime,
    val allDay: Boolean,
    val location: String = "",
    val description: String = "",
)

/** Turns a trip into calendar events and writes them to the phone's calendar or an .ics file. */
object TripCalendar {

    fun events(f: TripFull): List<CalEvent> {
        val out = mutableListOf<CalEvent>()
        val t = f.trip
        t.startDate?.let { s ->
            out += CalEvent("trip-${t.id}", "🌍 ${t.destination.ifBlank { t.name }}", s.atStartOfDay(), (t.endDate ?: s).plusDays(1).atStartOfDay(), true,
                t.destination, t.description)
        }
        f.transports.forEach { tr ->
            val dep = tr.departure ?: return@forEach
            val type = TransportType.of(tr.type)
            val route = listOf(tr.fromCode.ifBlank { tr.fromLocation }, tr.toCode.ifBlank { tr.toLocation }).filter { it.isNotBlank() }.joinToString(" → ")
            out += CalEvent("tr-${tr.id}", "${type.emoji} ${listOf(tr.flightNumber.ifBlank { type.label }, route).filter { it.isNotBlank() }.joinToString(" ")}",
                dep, tr.arrival?.takeIf { it.isAfter(dep) } ?: dep.plusHours(2), false, tr.fromLocation,
                listOf(tr.name, tr.bookingRef.takeIf { it.isNotBlank() }?.let { "Booking ref: $it" }, tr.seat.takeIf { it.isNotBlank() }?.let { "Seat: $it" },
                    tr.gate.takeIf { it.isNotBlank() }?.let { "Gate: $it" }, tr.notes).filterNotNull().filter { it.isNotBlank() }.joinToString("\n"))
        }
        f.lodgings.forEach { l ->
            val ci = l.checkIn ?: return@forEach
            val co = l.checkOut ?: ci.plusDays(1)
            out += CalEvent("lo-${l.id}", "${LodgingType.of(l.type).emoji} ${l.name}", ci.toLocalDate().atStartOfDay(), co.toLocalDate().atStartOfDay().let { if (it <= ci) it.plusDays(1) else it },
                true, l.address, listOfNotNull("Check-in ${ci.toLocalTime()} · Check-out ${co.toLocalTime()}",
                    l.reservationNumber.takeIf { it.isNotBlank() }?.let { "Reservation: $it" }, l.notes.takeIf { it.isNotBlank() }).joinToString("\n"))
        }
        f.itinerary.forEach { i ->
            if (i.time != null) out += CalEvent("it-${i.id}", "${i.emoji} ${i.title}", i.date.atTime(i.time),
                i.endTime?.let { e -> i.date.atTime(e) }?.takeIf { it.isAfter(i.date.atTime(i.time)) } ?: i.date.atTime(i.time).plusHours(1), false, "", i.notes)
            else out += CalEvent("it-${i.id}", "${i.emoji} ${i.title}", i.date.atStartOfDay(), i.date.plusDays(1).atStartOfDay(), true, "", i.notes)
        }
        f.places.forEach { p ->
            val at = p.visitAt ?: return@forEach
            if (at.toLocalTime() == LocalTime.MIDNIGHT) return@forEach
            out += CalEvent("pl-${p.id}", "${PlaceCategory.of(p.category).emoji} ${p.name}", at, at.plusMinutes(90), false, p.address.ifBlank { p.city }, p.notes)
        }
        return out
    }

    // --------------------------------------------------------------------------------- device calendar

    data class DeviceCalendar(val id: Long, val name: String, val account: String)

    fun calendars(context: Context): List<DeviceCalendar> {
        val proj = arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, CalendarContract.Calendars.ACCOUNT_NAME)
        val sel = "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ${CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR} AND ${CalendarContract.Calendars.VISIBLE} = 1"
        return context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, proj, sel, null, null)?.use { c ->
            generateSequence { if (c.moveToNext()) DeviceCalendar(c.getLong(0), c.getString(1) ?: "Calendar", c.getString(2) ?: "") else null }.toList()
        } ?: emptyList()
    }

    private fun prefs(context: Context) = context.getSharedPreferences("wanderlust_calendar", Context.MODE_PRIVATE)

    fun isSynced(context: Context, tripId: Long) = prefs(context).getStringSet("trip_$tripId", null)?.isNotEmpty() == true

    /** Removes events this app added for the trip earlier. */
    fun remove(context: Context, tripId: Long): Int {
        val ids = prefs(context).getStringSet("trip_$tripId", emptySet())!!
        ids.forEach { id -> runCatching { context.contentResolver.delete(CalendarContract.Events.CONTENT_URI, "${CalendarContract.Events._ID} = ?", arrayOf(id)) } }
        prefs(context).edit().remove("trip_$tripId").apply()
        return ids.size
    }

    /** Adds (or replaces) the trip's events in [calendarId]. Returns how many were written. */
    fun write(context: Context, f: TripFull, calendarId: Long): Int {
        remove(context, f.trip.id)
        val tz = TimeZone.getDefault().id
        val zone = ZoneId.systemDefault()
        val ids = mutableSetOf<String>()
        events(f).forEach { e ->
            val v = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calendarId)
                put(CalendarContract.Events.TITLE, e.title)
                put(CalendarContract.Events.DESCRIPTION, (e.description + "\n\nAdded by Wanderlust").trim())
                put(CalendarContract.Events.EVENT_LOCATION, e.location)
                if (e.allDay) {
                    put(CalendarContract.Events.ALL_DAY, 1)
                    put(CalendarContract.Events.EVENT_TIMEZONE, "UTC")
                    put(CalendarContract.Events.DTSTART, e.start.toLocalDate().atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli())
                    put(CalendarContract.Events.DTEND, e.end.toLocalDate().atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli())
                } else {
                    put(CalendarContract.Events.EVENT_TIMEZONE, tz)
                    put(CalendarContract.Events.DTSTART, e.start.atZone(zone).toInstant().toEpochMilli())
                    put(CalendarContract.Events.DTEND, e.end.atZone(zone).toInstant().toEpochMilli())
                }
            }
            context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, v)?.lastPathSegment?.let { ids += it }
        }
        prefs(context).edit().putStringSet("trip_${f.trip.id}", ids).apply()
        return ids.size
    }

    // -------------------------------------------------------------------------------------- .ics file

    fun ics(f: TripFull): String {
        val dt = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
        val d = DateTimeFormatter.ofPattern("yyyyMMdd")
        fun esc(s: String) = s.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace("\n", "\\n")
        val stamp = LocalDateTime.now(ZoneOffset.UTC).format(dt) + "Z"
        return buildString {
            append("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Wanderlust//Trip//EN\r\nCALSCALE:GREGORIAN\r\n")
            events(f).forEach { e ->
                append("BEGIN:VEVENT\r\nUID:${e.uid}-${f.trip.id}@wanderlust.app\r\nDTSTAMP:$stamp\r\n")
                if (e.allDay) append("DTSTART;VALUE=DATE:${e.start.format(d)}\r\nDTEND;VALUE=DATE:${e.end.format(d)}\r\n")
                else append("DTSTART:${e.start.format(dt)}\r\nDTEND:${e.end.format(dt)}\r\n") // floating local time
                append("SUMMARY:${esc(e.title)}\r\n")
                if (e.location.isNotBlank()) append("LOCATION:${esc(e.location)}\r\n")
                if (e.description.isNotBlank()) append("DESCRIPTION:${esc(e.description)}\r\n")
                append("END:VEVENT\r\n")
            }
            append("END:VCALENDAR\r\n")
        }
    }
}

/** "Add to calendar" dialog: pick a phone calendar, or share an .ics file. */
@Composable
fun CalendarDialog(f: TripFull, onDismiss: () -> Unit) {
    val app = LocalApp.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var calendars by remember { mutableStateOf<List<TripCalendar.DeviceCalendar>?>(null) }
    var denied by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r.values.all { it }) calendars = TripCalendar.calendars(context) else denied = true
    }
    val count = remember(f) { TripCalendar.events(f).size }

    fun shareIcs() {
        val file = File(app.files.exportsDir(), "${f.trip.name.replace(Regex("[^A-Za-z0-9]+"), "_").trim('_').ifBlank { "trip" }}.ics")
        file.writeText(TripCalendar.ics(f))
        app.files.share(context, file, "text/calendar", "${f.trip.name} calendar")
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to calendar") },
        text = {
            Column {
                Text("$count event${if (count == 1) "" else "s"}: the trip, flights, hotel stays, timed activities and place visits. " +
                    "Running this again replaces the earlier events.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (denied) Text("Calendar permission was denied — you can still share an .ics file.", color = MaterialTheme.colorScheme.error, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                calendars?.let { list ->
                    if (list.isEmpty()) Text("No writable calendars found on this phone.", modifier = Modifier.padding(top = 8.dp))
                    list.forEach { c ->
                        Column(Modifier.fillMaxWidth().clickable {
                            scope.launch {
                                val n = withContext(Dispatchers.IO) { runCatching { TripCalendar.write(context, f, c.id) }.getOrElse { -1 } }
                                Toast.makeText(context, if (n >= 0) "Added $n events to ${c.name} 📅" else "Couldn't write to that calendar", Toast.LENGTH_LONG).show()
                                onDismiss()
                            }
                        }.padding(vertical = 10.dp)) {
                            Text(c.name, fontWeight = FontWeight.SemiBold)
                            if (c.account.isNotBlank()) Text(c.account, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (calendars == null) TextButton(onClick = {
                permission.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR))
            }) { Text("Choose calendar") }
        },
        dismissButton = {
            Column {
                TextButton(onClick = ::shareIcs) { Text("Share .ics file") }
                if (TripCalendar.isSynced(context, f.trip.id)) TextButton(onClick = {
                    scope.launch {
                        val n = withContext(Dispatchers.IO) { TripCalendar.remove(context, f.trip.id) }
                        Toast.makeText(context, "Removed $n events", Toast.LENGTH_SHORT).show(); onDismiss()
                    }
                }) { Text("Remove from calendar") }
            }
        },
    )
}

