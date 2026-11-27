package com.wanderlust.app

import androidx.test.core.app.ApplicationProvider
import com.wanderlust.app.ai.PlanSchema
import com.wanderlust.app.ai.SourceFile
import com.wanderlust.app.data.OwnerType
import com.wanderlust.app.domain.PlanSaver
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
class PlanImportTest {

    private val json = """
        {"trip":{"destination":"Taipei, Taiwan","start_date":"2026-12-22","end_date":"2026-12-28"},
         "transports":[{"source_file":0,"transport_type":"PLANE","carrier":"EVA Air","number":"BR 18","from_location":"Delhi","to_location":"Taipei",
           "from_code":"del","to_code":"tpe","departure":"2026-12-21T23:30","arrival":"2026-12-22T07:55","seat":"42A","gate":"","booking_reference":"X7Y8Z9","passenger_name":"V M","notes":""}],
         "lodgings":[
           {"source_file":1,"hotel_name":"Grand Hyatt Taipei","lodging_type":"HOTEL","address":"Xinyi","check_in":"2026-12-22T15:00","check_out":"2026-12-25T11:00","booking_reference":"GH1","notes":""},
           {"source_file":1,"hotel_name":"Hualien Resort","lodging_type":"RESORT","address":"Hualien","check_in":"2026-12-25","check_out":"2026-12-28","booking_reference":"HR2","notes":""}],
         "places":[{"source_file":2,"name":"Taipei 101","category":"ATTRACTION","city":"Taipei","country":"Taiwan","date":"2026-12-23","time":"10:00","notes":""}],
         "activities":[{"source_file":2,"date":"2026-12-24","time":"18:00","end_time":"","title":"Shilin night market","emoji":"🍜","notes":""},
                       {"source_file":2,"date":"","time":"","end_time":"","title":"undated is dropped","emoji":"","notes":""}]}
    """.trimIndent()

    @Test fun parsesAndSplitsBookings() {
        val p = PlanSchema.parse(json, fileCount = 3)
        assertEquals("Taipei, Taiwan", p.destination)
        assertEquals(1, p.transports.size)
        assertEquals("TPE", p.transports[0].toCode)
        assertEquals(LocalDateTime.of(2026, 12, 21, 23, 30), p.transports[0].departure)
        assertEquals(2, p.lodgings.size)
        assertEquals("RESORT", p.lodgings[1].lodgingType)
        assertEquals(LocalDate.of(2026, 12, 25), p.lodgings[1].checkIn?.toLocalDate()) // date-only accepted
        assertEquals(1, p.places.size)
        assertEquals(1, p.activities.size) // undated activity dropped
    }

    @Test fun savesNewTripWithOwnFilePerAttachment() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<WanderlustApp>().container
        val dir = File(ApplicationProvider.getApplicationContext<WanderlustApp>().filesDir, "attachments").apply { mkdirs() }
        val files = listOf("ticket.pdf", "hotels.pdf", "itinerary.pdf").map { n ->
            val f = File(dir, n).apply { writeText("%PDF-1.4 $n") }
            SourceFile(f.path, "application/pdf", n)
        }
        val tid = PlanSaver.save(app, 0, PlanSchema.parse(json, 3), files)
        val full = app.repo.tripFullOnce(tid)!!
        assertEquals("Taipei, Taiwan", full.trip.destination)
        assertEquals(LocalDate.of(2026, 12, 22), full.trip.startDate)
        assertEquals(1, full.transports.size)
        assertEquals(2, full.lodgings.size)
        assertEquals(1, full.places.size)
        assertTrue(full.itinerary.any { it.title == "Shilin night market" })
        val hotelFiles = full.attachments.filter { it.ownerType == OwnerType.LODGING }
        assertEquals(2, hotelFiles.size)
        assertNotEquals(hotelFiles[0].filePath, hotelFiles[1].filePath) // second hotel got its own copy
        assertTrue(hotelFiles.all { File(it.filePath).exists() })
        // the itinerary PDF produced no booking → kept as a trip document
        assertEquals(listOf("itinerary.pdf"), full.attachments.filter { it.ownerType == OwnerType.TRIP }.map { it.name })
    }
}
