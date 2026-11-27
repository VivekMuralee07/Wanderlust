package com.wanderlust.app

import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.wanderlust.app.calendar.TripCalendar
import com.wanderlust.app.data.Attachment
import com.wanderlust.app.data.Expense
import com.wanderlust.app.data.Lodging
import com.wanderlust.app.data.OwnerType
import com.wanderlust.app.data.Transport
import com.wanderlust.app.data.Trip
import com.wanderlust.app.data.WanderlustDatabase
import com.wanderlust.app.reminders.ReminderScheduler
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FeaturesTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<WanderlustApp>()

    @Test fun backupRoundTrip(): Unit = runBlocking {
        val app = ctx.container
        val tid = app.repo.saveTrip(Trip(name = "Taiwan Trip", destination = "Taiwan", startDate = LocalDate.of(2026, 12, 22)))
        val ticket = File(ctx.filesDir, "attachments").apply { mkdirs() }.resolve("t.pdf").apply { writeText("%PDF ticket") }
        val lid = app.repo.saveLodging(Lodging(tripId = tid, name = "Grand Hyatt"))
        app.repo.addAttachment(Attachment(tripId = tid, ownerType = OwnerType.LODGING, ownerId = lid, name = "t.pdf", filePath = ticket.path, mimeType = "application/pdf"))
        app.repo.saveExpense(Expense(tripId = tid, title = "Dumplings", amount = 300.0, currency = "TWD", amountHome = 800.0, homeCurrency = "INR", date = LocalDate.of(2026, 12, 23)))

        val zip = File(ctx.cacheDir, "b.zip")
        app.backups.exportTo(Uri.fromFile(zip))
        assertTrue(zip.length() > 0)

        // wipe the data, then restore
        app.repo.deleteTrip(app.repo.trip(tid)!!)
        assertTrue(!ticket.exists())
        app.backups.restoreFrom(Uri.fromFile(zip))

        val db = Room.databaseBuilder(ctx, WanderlustDatabase::class.java, WanderlustDatabase.NAME).build()
        val trips = db.dao().tripsOnce()
        assertEquals(listOf("Taiwan Trip"), trips.map { it.name })
        val atts = db.dao().attachmentsFor(OwnerType.LODGING, lid)
        assertEquals(1, atts.size)
        assertEquals("%PDF ticket", File(atts[0].filePath).readText())
        db.close()
    }

    @Test fun remindersArePlanned() {
        val now = LocalDateTime.of(2026, 12, 1, 12, 0)
        val trip = Trip(id = 1, name = "Taiwan", destination = "Taipei", startDate = LocalDate.of(2026, 12, 22), endDate = LocalDate.of(2026, 12, 28))
        val flight = Transport(id = 5, tripId = 1, type = "PLANE", flightNumber = "BR 18", toCode = "TPE", departure = LocalDateTime.of(2026, 12, 21, 23, 30))
        val hotel = Lodging(id = 7, tripId = 1, name = "Grand Hyatt", checkIn = LocalDateTime.of(2026, 12, 22, 15, 0), checkOut = LocalDateTime.of(2026, 12, 25, 11, 0))
        val r = ReminderScheduler.plan(listOf(trip), listOf(flight), listOf(hotel), now)
        val keys = r.map { it.key }.toSet()
        assertTrue(keys.containsAll(setOf("trip-1-7", "trip-1-3", "trip-1-1", "tr-5-checkin", "tr-5-leave", "lo-7-in", "lo-7-out")))
        assertEquals(LocalDateTime.of(2026, 12, 20, 23, 30), r.first { it.key == "tr-5-checkin" }.at)
        // nothing in the past
        assertTrue(ReminderScheduler.plan(listOf(trip), listOf(flight), listOf(hotel), LocalDateTime.of(2026, 12, 30, 0, 0)).isEmpty())
    }

    @Test fun calendarFile(): Unit = runBlocking {
        val app = ctx.container
        val tid = app.repo.saveTrip(Trip(name = "Taiwan", destination = "Taipei; Taiwan", startDate = LocalDate.of(2026, 12, 22), endDate = LocalDate.of(2026, 12, 28)))
        app.repo.saveTransport(Transport(tripId = tid, type = "PLANE", flightNumber = "BR 18", fromCode = "DEL", toCode = "TPE",
            departure = LocalDateTime.of(2026, 12, 21, 23, 30), arrival = LocalDateTime.of(2026, 12, 22, 7, 55)))
        val ics = TripCalendar.ics(app.repo.tripFullOnce(tid)!!)
        assertTrue(ics.contains("DTSTART;VALUE=DATE:20261222") && ics.contains("DTEND;VALUE=DATE:20261229"))
        assertTrue(ics.contains("DTSTART:20261221T233000") && ics.contains("SUMMARY:✈️ BR 18 DEL → TPE"))
        assertTrue(ics.contains("Taipei\\; Taiwan"))
        assertEquals(2, ics.split("BEGIN:VEVENT").size - 1)
    }
}
