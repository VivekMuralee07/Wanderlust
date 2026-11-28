package com.wanderlust.app

import androidx.test.core.app.ApplicationProvider
import com.wanderlust.app.data.Place
import com.wanderlust.app.data.Trip
import com.wanderlust.app.data.TripFull
import com.wanderlust.app.weather.GeoCache
import com.wanderlust.app.weather.Weather
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WeatherLocationTest {
    @Test fun eachDayFollowsThePlannedPlaces() = runBlocking {
        val d0 = LocalDate.of(2026, 12, 22)
        val trip = Trip(id = 1, name = "Taiwan Trip", destination = "", startDate = d0, endDate = d0.plusDays(3))
        val places = listOf(
            Place(id = 1, tripId = 1, name = "Taipei 101", city = "Taipei", latitude = 25.03, longitude = 121.56, visitAt = d0.atTime(10, 0)),
            Place(id = 2, tripId = 1, name = "Longshan Temple", city = "Taipei", latitude = 25.04, longitude = 121.50, visitAt = d0.atTime(15, 0)),
            Place(id = 3, tripId = 1, name = "Taroko Gorge", city = "Hualien", latitude = 24.16, longitude = 121.62, visitAt = d0.plusDays(2).atTime(9, 0)),
            Place(id = 4, tripId = 1, name = "Qixingtan Beach", city = "Hualien", latitude = 24.03, longitude = 121.63, visitAt = d0.plusDays(2).atTime(16, 0)),
            Place(id = 5, tripId = 1, name = "Some Taipei cafe", city = "Taipei", latitude = 25.05, longitude = 121.52, visitAt = d0.plusDays(2).atTime(20, 0)),
        )
        val dates = (0..3).map { d0.plusDays(it.toLong()) }
        val locs = Weather.locateDays(TripFull(trip, places = places), dates, GeoCache(ApplicationProvider.getApplicationContext()))
        assertEquals("Taipei", locs[d0]!!.label)
        assertEquals("Taipei", locs[d0.plusDays(1)]!!.label)   // nothing planned → previous day's city
        assertEquals("Hualien", locs[d0.plusDays(2)]!!.label)  // city with most stops that day wins
        assertEquals(24.095, locs[d0.plusDays(2)]!!.lat, 0.001)
        assertEquals("Hualien", locs[d0.plusDays(3)]!!.label)
    }
}
