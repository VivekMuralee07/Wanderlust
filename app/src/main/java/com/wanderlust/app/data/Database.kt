package com.wanderlust.app.data

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class Converters {
    @TypeConverter fun dateToString(v: LocalDate?): String? = v?.toString()
    @TypeConverter fun stringToDate(v: String?): LocalDate? = v?.let(LocalDate::parse)
    @TypeConverter fun dateTimeToString(v: LocalDateTime?): String? = v?.toString()
    @TypeConverter fun stringToDateTime(v: String?): LocalDateTime? = v?.let(LocalDateTime::parse)
    @TypeConverter fun timeToString(v: LocalTime?): String? = v?.toString()
    @TypeConverter fun stringToTime(v: String?): LocalTime? = v?.let(LocalTime::parse)
}

@Dao
interface TravelDao {
    // Trips
    @Query("SELECT * FROM trips ORDER BY CASE WHEN startDate IS NULL THEN 1 ELSE 0 END, startDate ASC")
    fun trips(): Flow<List<Trip>>

    @Query("SELECT * FROM trips")
    suspend fun tripsOnce(): List<Trip>

    @Query("SELECT * FROM trips WHERE id = :id")
    fun trip(id: Long): Flow<Trip?>

    @Query("SELECT * FROM trips WHERE id = :id")
    suspend fun tripOnce(id: Long): Trip?

    @Insert suspend fun insertTrip(t: Trip): Long
    @Update suspend fun updateTrip(t: Trip)
    @Delete suspend fun deleteTrip(t: Trip)

    // Transports
    @Query("SELECT * FROM transports WHERE tripId = :tripId ORDER BY departure")
    fun transports(tripId: Long): Flow<List<Transport>>
    @Query("SELECT * FROM transports ORDER BY departure")
    fun allTransports(): Flow<List<Transport>>
    @Query("SELECT * FROM transports WHERE id = :id") suspend fun transport(id: Long): Transport?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertTransport(t: Transport): Long
    @Delete suspend fun deleteTransport(t: Transport)

    // Lodgings
    @Query("SELECT * FROM lodgings WHERE tripId = :tripId ORDER BY checkIn")
    fun lodgings(tripId: Long): Flow<List<Lodging>>
    @Query("SELECT * FROM lodgings") fun allLodgings(): Flow<List<Lodging>>
    @Query("SELECT * FROM lodgings WHERE id = :id") suspend fun lodging(id: Long): Lodging?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertLodging(l: Lodging): Long
    @Delete suspend fun deleteLodging(l: Lodging)

    // Places
    @Query("SELECT * FROM places WHERE tripId = :tripId ORDER BY visitAt")
    fun places(tripId: Long): Flow<List<Place>>
    @Query("SELECT * FROM places") fun allPlaces(): Flow<List<Place>>
    @Query("SELECT * FROM places WHERE id = :id") suspend fun place(id: Long): Place?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertPlace(p: Place): Long
    @Delete suspend fun deletePlace(p: Place)

    // Itinerary
    @Query("SELECT * FROM itinerary_items WHERE tripId = :tripId ORDER BY date, time")
    fun itinerary(tripId: Long): Flow<List<ItineraryItem>>
    @Query("SELECT * FROM itinerary_items WHERE id = :id") suspend fun itineraryItem(id: Long): ItineraryItem?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertItinerary(i: ItineraryItem): Long
    @Delete suspend fun deleteItinerary(i: ItineraryItem)

    // Checklist
    @Query("SELECT * FROM checklist_items WHERE tripId = :tripId ORDER BY listName, createdAt")
    fun checklist(tripId: Long): Flow<List<ChecklistItem>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertChecklist(c: ChecklistItem): Long
    @Delete suspend fun deleteChecklist(c: ChecklistItem)

    // Journal
    @Query("SELECT * FROM journal_entries WHERE tripId = :tripId ORDER BY date, createdAt")
    fun journal(tripId: Long): Flow<List<JournalEntry>>
    @Query("SELECT * FROM journal_entries WHERE id = :id") suspend fun journalEntry(id: Long): JournalEntry?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertJournal(j: JournalEntry): Long
    @Delete suspend fun deleteJournal(j: JournalEntry)

    // Attachments
    @Query("SELECT * FROM attachments WHERE tripId = :tripId ORDER BY addedAt")
    fun attachments(tripId: Long): Flow<List<Attachment>>
    @Query("SELECT * FROM attachments WHERE ownerType = :type AND ownerId = :ownerId")
    suspend fun attachmentsFor(type: String, ownerId: Long): List<Attachment>
    @Insert suspend fun insertAttachment(a: Attachment): Long
    @Delete suspend fun deleteAttachment(a: Attachment)

    // Memories
    @Query("SELECT * FROM memories WHERE tripId = :tripId ORDER BY addedAt DESC")
    fun memories(tripId: Long): Flow<List<Memory>>
    @Query("SELECT * FROM memories ORDER BY addedAt DESC LIMIT :limit")
    fun recentMemories(limit: Int): Flow<List<Memory>>
    @Insert suspend fun insertMemory(m: Memory): Long
    @Delete suspend fun deleteMemory(m: Memory)

    // Expenses (v2)
    @Query("SELECT * FROM expenses WHERE tripId = :tripId ORDER BY date DESC, createdAt DESC")
    fun expenses(tripId: Long): Flow<List<Expense>>
    @Query("SELECT * FROM expenses WHERE id = :id") suspend fun expense(id: Long): Expense?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertExpense(e: Expense): Long
    @Delete suspend fun deleteExpense(e: Expense)

    // Assistant chat (v2)
    @Query("SELECT * FROM chat_messages ORDER BY createdAt, id") suspend fun chatMessages(): List<ChatMessageEntity>
    @Insert suspend fun insertChat(m: ChatMessageEntity): Long
    @Query("DELETE FROM chat_messages") suspend fun clearChat()

    // Visited countries (v3)
    @Query("SELECT * FROM visited_countries ORDER BY name") fun visitedCountries(): Flow<List<VisitedCountry>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertCountry(c: VisitedCountry): Long
    @Delete suspend fun deleteCountry(c: VisitedCountry)

    // Lists for backups / reminders / widgets
    @Query("SELECT * FROM transports") suspend fun transportsOnce(): List<Transport>
    @Query("SELECT * FROM lodgings") suspend fun lodgingsOnce(): List<Lodging>
}

@Database(
    entities = [Trip::class, Transport::class, Lodging::class, Place::class, ItineraryItem::class,
        ChecklistItem::class, JournalEntry::class, Attachment::class, Memory::class, Expense::class, ChatMessageEntity::class, VisitedCountry::class],
    version = WanderlustDatabase.VERSION,
    exportSchema = true,
    // Every schema change must add an entry here (or a manual Migration) — never destructive migrations,
    // so updates keep users' data. Schemas live in app/schemas/ and are checked by MigrationTest.
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3),
    ],
)
@TypeConverters(Converters::class)
abstract class WanderlustDatabase : RoomDatabase() {
    abstract fun dao(): TravelDao

    companion object {
        const val VERSION = 3
        const val NAME = "wanderlust.db"
        fun create(context: Context) = Room.databaseBuilder(context, WanderlustDatabase::class.java, NAME).build()
    }
}
