package com.wanderlust.app

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import com.wanderlust.app.data.WanderlustDatabase
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Upgrades a real v1 database to the current version and checks that data survives. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), WanderlustDatabase::class.java)

    @Test fun migrate1ToLatest() {
        helper.createDatabase("migration-test", 1).apply {
            execSQL("INSERT INTO trips (id, name, destination, description, startDate, endDate, coverPath, themeIndex, isArchived, createdAt) " +
                "VALUES (1, 'Taiwan Trip', 'Taiwan', '', '2026-12-22', '2026-12-28', NULL, 2, 0, 0)")
            execSQL("INSERT INTO lodgings (id, tripId, name, type, address, checkIn, checkOut, reservationNumber, rating, notes, link) " +
                "VALUES (1, 1, 'Grand Hyatt', 'HOTEL', '', NULL, NULL, 'GH1', NULL, '', '')")
            close()
        }
        val db = helper.runMigrationsAndValidate("migration-test", WanderlustDatabase.VERSION, true)
        db.query("SELECT name, budget FROM trips WHERE id = 1").use { c ->
            c.moveToFirst(); assertEquals("Taiwan Trip", c.getString(0)); assertEquals(true, c.isNull(1))
        }
        db.query("SELECT COUNT(*) FROM lodgings").use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
        db.query("SELECT COUNT(*) FROM expenses").use { c -> c.moveToFirst(); assertEquals(0, c.getInt(0)) }
    }
}
