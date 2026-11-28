package com.wanderlust.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.wanderlust.app.data.ChecklistItem
import com.wanderlust.app.data.ItineraryItem
import com.wanderlust.app.data.JournalEntry
import com.wanderlust.app.data.Lodging
import com.wanderlust.app.data.Place
import com.wanderlust.app.data.Transport
import com.wanderlust.app.data.Trip
import com.wanderlust.app.domain.ItineraryBuilder
import com.wanderlust.app.domain.JournalSummarizer
import com.wanderlust.app.ui.home.HomeScreen
import com.wanderlust.app.ui.itinerary.VisualItineraryScreen
import com.wanderlust.app.ui.journal.JournalSummaryScreen
import com.wanderlust.app.ui.theme.WanderlustTheme
import com.wanderlust.app.ui.trips.TripDetailScreen
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.time.LocalDate
import java.time.LocalTime

/**
 * Renders the real screens on the JVM with sample data and saves PNGs to app/build/screenshots.
 * Also checks the itinerary builder and journal summary logic.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w400dp-h880dp-xxhdpi")
class ScreenshotTest {

    @get:Rule val compose = createComposeRule()

    private lateinit var app: AppContainer
    private var kyoto = 0L
    private var iceland = 0L

    @Before
    fun seed(): Unit = runBlocking {
        app = ApplicationProvider.getApplicationContext<WanderlustApp>().container
        app.settings.update { it.copy(travelerName = "Vivek") }
        val repo = app.repo
        val start = LocalDate.now().plusDays(23)
        kyoto = repo.saveTrip(Trip(name = "Cherry Blossom Escape", destination = "Kyoto, Japan", startDate = start, endDate = start.plusDays(6),
            description = "Temples, tea houses and sakura season.", themeIndex = 5))
        repo.saveTrip(Trip(name = "Island hopping", destination = "Santorini", startDate = LocalDate.now().plusDays(80), endDate = LocalDate.now().plusDays(88), themeIndex = 1))
        repo.saveTrip(Trip(name = "Weekend in Lisbon", destination = "Lisbon", startDate = LocalDate.now().plusDays(140), endDate = LocalDate.now().plusDays(143), themeIndex = 3))
        repo.saveTransport(Transport(tripId = kyoto, type = "PLANE", name = "Japan Airlines", flightNumber = "JL 30", fromLocation = "Delhi", toLocation = "Tokyo Haneda",
            fromCode = "DEL", toCode = "HND", departure = start.minusDays(1).atTime(21, 15), arrival = start.atTime(8, 40), bookingRef = "QX7P2L", seat = "34K", gate = "B12"))
        repo.saveTransport(Transport(tripId = kyoto, type = "TRAIN", name = "Shinkansen Nozomi", flightNumber = "N 221", fromLocation = "Tokyo", toLocation = "Kyoto",
            fromCode = "TYO", toCode = "KYO", departure = start.atTime(11, 0), arrival = start.atTime(13, 15), seat = "Car 7 · 12A"))
        repo.saveLodging(Lodging(tripId = kyoto, name = "Hotel Kanra Kyoto", type = "HOTEL", address = "Shimogyo Ward, Kyoto", checkIn = start.atTime(15, 0),
            checkOut = start.plusDays(6).atTime(11, 0), reservationNumber = "KX-99812", rating = 5f))
        repo.savePlace(Place(tripId = kyoto, name = "Fushimi Inari Taisha", category = "SPIRITUAL", city = "Kyoto", countryCode = "JP", countryName = "Japan",
            latitude = 34.9671, longitude = 135.7727, visitAt = start.plusDays(1).atTime(7, 30)))
        repo.savePlace(Place(tripId = kyoto, name = "Arashiyama Bamboo Grove", category = "OUTDOOR", city = "Kyoto", countryCode = "JP", countryName = "Japan",
            latitude = 35.0170, longitude = 135.6713, visitAt = start.plusDays(2).atTime(8, 0)))
        repo.saveItinerary(ItineraryItem(tripId = kyoto, date = start.plusDays(1), time = LocalTime.of(12, 30), title = "Matcha tasting in Uji", emoji = "🍵"))
        repo.saveItinerary(ItineraryItem(tripId = kyoto, date = start.plusDays(1), time = LocalTime.of(19, 0), title = "Kaiseki dinner in Gion", emoji = "🍽️"))
        repo.saveItinerary(ItineraryItem(tripId = kyoto, date = start.plusDays(2), time = LocalTime.of(14, 0), title = "Kimono walk, Higashiyama", emoji = "📸"))
        repo.seedPackingList(kyoto)
        app.settings.update { it.copy(homeCurrency = "INR") }
        repo.saveTrip(repo.trip(kyoto)!!.copy(budget = 150000.0))
        listOf(Triple("Kaiseki dinner", 18000.0, "FOOD"), Triple("JR Pass", 50000.0, "TRANSPORT"), Triple("Temple entry", 1000.0, "ACTIVITIES"), Triple("Matcha & sweets", 2400.0, "FOOD"))
            .forEachIndexed { i, (t, jpy, c) ->
                repo.saveExpense(com.wanderlust.app.data.Expense(tripId = kyoto, title = t, amount = jpy, currency = "JPY", amountHome = jpy * 0.56,
                    homeCurrency = "INR", category = c, date = start.plusDays((i % 2).toLong())))
            }
        repo.saveChecklist(ChecklistItem(tripId = kyoto, name = "JR Pass voucher", isChecked = true))

        val past = LocalDate.now().minusDays(120)
        iceland = repo.saveTrip(Trip(name = "Ring Road", destination = "Iceland", startDate = past, endDate = past.plusDays(4), themeIndex = 6))
        repo.savePlace(Place(tripId = iceland, name = "Skógafoss", category = "OUTDOOR", countryCode = "IS", countryName = "Iceland", visited = true, latitude = 63.53, longitude = -19.51))
        repo.savePlace(Place(tripId = iceland, name = "Jökulsárlón", category = "OUTDOOR", countryCode = "IS", countryName = "Iceland", visited = true, latitude = 64.05, longitude = -16.18))
        repo.savePlace(Place(tripId = iceland, name = "Blue Lagoon", category = "BEACH", countryCode = "IS", countryName = "Iceland", visited = true, latitude = 63.88, longitude = -22.45))
        repo.saveJournal(JournalEntry(tripId = iceland, date = past, title = "Landing in Reykjavík", mood = "🤩",
            content = "The light here is unreal. We picked up the camper and drove straight to the coast, chasing waterfalls before sunset."))
        repo.saveJournal(JournalEntry(tripId = iceland, date = past.plusDays(1), title = "Waterfalls all day", mood = "😊",
            content = "Seljalandsfoss in the morning, Skógafoss after lunch. Soaked, cold and completely happy. Waterfalls everywhere."))
        repo.saveJournal(JournalEntry(tripId = iceland, date = past.plusDays(3), title = "Glacier lagoon", mood = "🥰",
            content = "Icebergs drifting in the lagoon, seals popping up. Diamond beach at golden hour was the highlight of the whole trip."))
    }

    private fun settle(ms: Long = 1500) {
        repeat(6) {
            Thread.sleep(150)
            ShadowLooper.idleMainLooper()
            compose.mainClock.advanceTimeBy(ms / 6)
        }
    }

    private fun render(name: String, content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            WanderlustTheme {
                CompositionLocalProvider(LocalApp provides app) { content() }
            }
        }
        settle()
        compose.onRoot().captureRoboImage("build/screenshots/$name.png")
    }

    @Test fun home() = render("01_home") { HomeScreen(rememberNavController()) }

    @Test fun tripOverview() = render("02_trip_overview") { TripDetailScreen(rememberNavController(), kyoto) }

    @Test fun tripBookings() {
        render("03_tmp") { TripDetailScreen(rememberNavController(), kyoto) }
        compose.onAllNodesWithText("Bookings")[0].performClick()
        settle()
        compose.onRoot().captureRoboImage("build/screenshots/03_trip_bookings.png")
    }

    @Test fun tripMap() {
        render("06_tmp") { TripDetailScreen(rememberNavController(), kyoto) }
        compose.onAllNodesWithText("Map")[0].performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        settle()
        compose.onRoot().captureRoboImage("build/screenshots/06_trip_map.png")
    }

    @Test fun tripExpenses() {
        render("07_tmp") { TripDetailScreen(rememberNavController(), kyoto) }
        compose.onAllNodesWithText("Expenses")[0].performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        settle()
        compose.onRoot().captureRoboImage("build/screenshots/07_trip_expenses.png")
    }

    @Test fun settings() = render("08_settings") { com.wanderlust.app.ui.settings.SettingsScreen(rememberNavController()) }

    @Test fun world() {
        runBlocking {
            listOf("TW" to 2019, "IS" to 2025, "FR" to 2018, "IN" to null, "SG" to 2023).forEach { (cc, y) ->
                app.repo.saveCountry(com.wanderlust.app.data.VisitedCountry(countryCode = cc, name = com.wanderlust.app.data.Countries.name(cc), year = y))
            }
        }
        render("09_world") { com.wanderlust.app.ui.world.WorldScreen(rememberNavController()) }
        assertEquals(175, com.wanderlust.app.data.Countries.shapes(ApplicationProvider.getApplicationContext()).size)
    }

    @Test fun visualItinerary() {
        // fake weather so the per-day / per-place weather can be seen without network
        com.wanderlust.app.weather.Weather.testOverride = { full ->
            val start = full.trip.startDate!!
            val days = (-1..6).associate { i ->
                val d = start.plusDays(i.toLong())
                d to com.wanderlust.app.weather.DayWeather(d, listOf(1, 1, 2, 61, 0, 3, 80, 1)[i + 1], 13.0 + (i + 1) % 3, 6.0 + (i + 1) % 2, 20,
                    typical = true, place = if (i < 0) "Tokyo" else "Kyoto")
            }
            com.wanderlust.app.weather.TripWeather(days, full.places.associate { p -> p.id to days.getValue(p.visitAt!!.toLocalDate()).copy(code = 0, maxC = 14.0) })
        }
        try {
            render("04_visual_itinerary") {
                val nav = rememberNavController()
                NavHost(nav, "it") { composable("it") { VisualItineraryScreen(nav, kyoto) } }
            }
        } finally { com.wanderlust.app.weather.Weather.testOverride = null }
    }

    @Test fun journalSummary() = render("05_journal_summary") {
        val nav = rememberNavController()
        NavHost(nav, "s") { composable("s") { JournalSummaryScreen(nav, iceland) } }
    }

    @Test fun itineraryLogic(): Unit = runBlocking {
        val full = app.repo.tripFullOnce(kyoto)!!
        val it = ItineraryBuilder.build(full)
        assertEquals(8, it.days.size) // flight departs the day before the trip starts + 7 trip days
        val day1 = it.days.first { d -> d.index == 1 }
        assertTrue(day1.events.any { e -> e.title.contains("Check in") })
        assertEquals("Hotel Kanra Kyoto", day1.sleepingAt)
        val s = JournalSummarizer.summarize(app.repo.tripFullOnce(iceland)!!)
        assertTrue(s.stats.any { (k, v) -> k == "Journal entries" && v == "3" })
        assertTrue("waterfalls" in s.topWords)
    }
}

class VersionCompareTest {
    @org.junit.Test fun compares() {
        val c = com.wanderlust.app.update.AppUpdater.Companion::compareVersions
        org.junit.Assert.assertTrue(c("1.10.0", "1.9.2") > 0)
        org.junit.Assert.assertTrue(c("1.0.0", "1.0") == 0)
        org.junit.Assert.assertTrue(c("1.0.1", "1.0.1-debug") == 0)
        org.junit.Assert.assertTrue(c("2.0.0", "10.0.0") < 0)
    }
}
