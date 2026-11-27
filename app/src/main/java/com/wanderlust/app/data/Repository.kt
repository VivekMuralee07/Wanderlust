package com.wanderlust.app.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import java.io.File
import java.time.LocalDate

class TravelRepository(private val dao: TravelDao, private val files: FileStore) {

    val trips: Flow<List<Trip>> = dao.trips()
    val allTransports = dao.allTransports()
    val allLodgings = dao.allLodgings()
    val allPlaces = dao.allPlaces()
    fun recentMemories(limit: Int = 20) = dao.recentMemories(limit)

    @OptIn(ExperimentalCoroutinesApi::class)
    fun tripFull(id: Long): Flow<TripFull?> = dao.trip(id).flatMapLatest { trip ->
        if (trip == null) flowOf(null) else {
            val a = combine(dao.transports(id), dao.lodgings(id), dao.places(id), dao.itinerary(id)) { t, l, p, i ->
                TripFull(trip, t, l, p, i)
            }
            val b = combine(dao.checklist(id), dao.journal(id), dao.attachments(id), dao.memories(id)) { c, j, at, m ->
                arrayOf(c, j, at, m)
            }
            combine(a, b) { full, rest ->
                @Suppress("UNCHECKED_CAST")
                full.copy(
                    checklist = rest[0] as List<ChecklistItem>,
                    journal = rest[1] as List<JournalEntry>,
                    attachments = rest[2] as List<Attachment>,
                    memories = rest[3] as List<Memory>,
                )
            }
        }
    }

    suspend fun tripFullOnce(id: Long): TripFull? = tripFull(id).first()

    suspend fun tripsOnce() = dao.tripsOnce()

    // ---- Trips
    suspend fun saveTrip(t: Trip): Long = if (t.id == 0L) dao.insertTrip(t) else { dao.updateTrip(t); t.id }
    suspend fun trip(id: Long) = dao.tripOnce(id)
    suspend fun deleteTrip(t: Trip) {
        // remove files we own, then cascade-delete rows
        tripFullOnce(t.id)?.let { full ->
            full.attachments.forEach { File(it.filePath).delete() }
            full.memories.forEach { File(it.filePath).delete() }
            full.journal.mapNotNull { it.photoPath }.forEach { File(it).delete() }
        }
        t.coverPath?.let { File(it).delete() }
        dao.deleteTrip(t)
    }

    // ---- Bookings
    suspend fun transport(id: Long) = dao.transport(id)
    suspend fun saveTransport(t: Transport) = dao.upsertTransport(t)
    suspend fun deleteTransport(t: Transport) {
        dao.attachmentsFor(OwnerType.TRANSPORT, t.id).forEach { deleteAttachment(it) }
        dao.deleteTransport(t)
    }

    suspend fun lodging(id: Long) = dao.lodging(id)
    suspend fun saveLodging(l: Lodging) = dao.upsertLodging(l)
    suspend fun deleteLodging(l: Lodging) {
        dao.attachmentsFor(OwnerType.LODGING, l.id).forEach { deleteAttachment(it) }
        dao.deleteLodging(l)
    }

    suspend fun attachmentsFor(type: String, ownerId: Long) = dao.attachmentsFor(type, ownerId)
    suspend fun addAttachment(a: Attachment) = dao.insertAttachment(a)
    suspend fun deleteAttachment(a: Attachment) {
        File(a.filePath).delete()
        dao.deleteAttachment(a)
    }

    // ---- Places
    suspend fun place(id: Long) = dao.place(id)
    suspend fun savePlace(p: Place) = dao.upsertPlace(p)
    suspend fun deletePlace(p: Place) = dao.deletePlace(p)

    // ---- Itinerary
    suspend fun itineraryItem(id: Long) = dao.itineraryItem(id)
    suspend fun saveItinerary(i: ItineraryItem) = dao.upsertItinerary(i)
    suspend fun deleteItinerary(i: ItineraryItem) = dao.deleteItinerary(i)

    // ---- Checklist
    suspend fun saveChecklist(c: ChecklistItem) = dao.upsertChecklist(c)
    suspend fun deleteChecklist(c: ChecklistItem) = dao.deleteChecklist(c)

    // ---- Journal
    suspend fun journalEntry(id: Long) = dao.journalEntry(id)
    suspend fun saveJournal(j: JournalEntry) = dao.upsertJournal(j)
    suspend fun deleteJournal(j: JournalEntry) {
        j.photoPath?.let { File(it).delete() }
        dao.deleteJournal(j)
    }

    // ---- Memories
    suspend fun addMemory(m: Memory) = dao.insertMemory(m)
    suspend fun deleteMemory(m: Memory) {
        File(m.filePath).delete()
        dao.deleteMemory(m)
    }

    /** Seeds a starter packing list, like AdventureLog's checklists. */
    suspend fun seedPackingList(tripId: Long) {
        listOf("Passport / ID", "Phone charger & adapter", "Travel insurance docs", "Medication",
            "Toiletries", "Comfortable shoes", "Headphones", "Copies of tickets").forEach {
            dao.upsertChecklist(ChecklistItem(tripId = tripId, listName = "Packing", name = it))
        }
    }

    fun fileStore() = files

    companion object {
        fun tripStatus(trip: Trip, today: LocalDate = LocalDate.now()): TripStatus = when {
            trip.startDate == null -> TripStatus.PLANNING
            today.isBefore(trip.startDate) -> TripStatus.UPCOMING
            trip.endDate != null && today.isAfter(trip.endDate) -> TripStatus.PAST
            trip.endDate == null && today.isAfter(trip.startDate) -> TripStatus.PAST
            else -> TripStatus.ONGOING
        }
    }
}

enum class TripStatus { PLANNING, UPCOMING, ONGOING, PAST }
