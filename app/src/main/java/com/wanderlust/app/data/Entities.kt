package com.wanderlust.app.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Data model. Mirrors the core concepts of AdventureLog:
 *  - Trip            ~ AdventureLog "Collection" (a trip with start/end dates)
 *  - Transport       ~ "Transportation" (flights, trains, buses... with codes & booking refs)
 *  - Lodging         ~ "Lodging" (hotels etc. with check-in/out & reservation number)
 *  - Place           ~ "Location" + "Visit" (places to see, with category, rating, coordinates)
 *  - ItineraryItem   ~ "CollectionItineraryItem" (custom activity placed on a trip day)
 *  - ChecklistItem   ~ "Checklist"/"ChecklistItem" (packing lists, to-dos)
 *  - JournalEntry    ~ "Note" (daily travel journal)
 *  - Attachment      ~ "ContentAttachment" (uploaded tickets: PDFs / images)
 *  - Memory          ~ "ContentImage" (photos for memories)
 */

@Entity(tableName = "trips")
data class Trip(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val destination: String = "",
    val description: String = "",
    val startDate: LocalDate? = null,
    val endDate: LocalDate? = null,
    val coverPath: String? = null,
    val themeIndex: Int = 0,
    val isArchived: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
)

enum class TransportType(val label: String, val emoji: String) {
    PLANE("Flight", "✈️"), TRAIN("Train", "🚆"), BUS("Bus", "🚌"), CAR("Car", "🚗"),
    BOAT("Ferry / Boat", "⛴️"), MOTORCYCLE("Motorcycle", "🏍️"), BIKE("Bike", "🚲"),
    WALKING("Walking", "🚶"), OTHER("Other", "🧭");

    companion object {
        fun of(value: String?) = entries.firstOrNull { it.name.equals(value, true) } ?: OTHER
    }
}

@Entity(
    tableName = "transports",
    foreignKeys = [ForeignKey(entity = Trip::class, parentColumns = ["id"], childColumns = ["tripId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("tripId")],
)
data class Transport(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: Long,
    val type: String = TransportType.PLANE.name,
    val name: String = "",                 // e.g. "Lufthansa" or "Eurostar"
    val flightNumber: String = "",
    val fromLocation: String = "",
    val toLocation: String = "",
    val fromCode: String = "",             // airport / station code
    val toCode: String = "",
    val departure: LocalDateTime? = null,
    val arrival: LocalDateTime? = null,
    val bookingRef: String = "",
    val seat: String = "",
    val gate: String = "",
    val notes: String = "",
    val link: String = "",
)

enum class LodgingType(val label: String, val emoji: String) {
    HOTEL("Hotel", "🏨"), HOSTEL("Hostel", "🛏️"), RESORT("Resort", "🏝️"), BNB("Bed & Breakfast", "🍳"),
    CAMPGROUND("Campground", "🏕️"), CABIN("Cabin", "🛖"), APARTMENT("Apartment", "🏢"),
    HOUSE("House", "🏡"), VILLA("Villa", "🏰"), MOTEL("Motel", "🛣️"), OTHER("Other", "🔑");

    companion object {
        fun of(value: String?) = entries.firstOrNull { it.name.equals(value, true) } ?: OTHER
    }
}

@Entity(
    tableName = "lodgings",
    foreignKeys = [ForeignKey(entity = Trip::class, parentColumns = ["id"], childColumns = ["tripId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("tripId")],
)
data class Lodging(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: Long,
    val name: String = "",
    val type: String = LodgingType.HOTEL.name,
    val address: String = "",
    val checkIn: LocalDateTime? = null,
    val checkOut: LocalDateTime? = null,
    val reservationNumber: String = "",
    val rating: Float? = null,
    val notes: String = "",
    val link: String = "",
)

/** Categories, as in AdventureLog's location categories. */
enum class PlaceCategory(val label: String, val emoji: String) {
    GENERAL("General", "🌍"), ATTRACTION("Attraction", "🎢"), DINING("Dining", "🍽️"),
    CULTURE("Culture", "🎭"), HISTORICAL("Historical site", "🏛️"), MUSEUM("Art & museums", "🎨"),
    OUTDOOR("Outdoor", "🏞️"), HIKING("Hiking", "🥾"), BEACH("Beach & water", "🏖️"),
    SHOPPING("Shopping", "🛍️"), NIGHTLIFE("Nightlife", "🌃"), EVENT("Event & festival", "🎉"),
    WILDLIFE("Wildlife", "🦒"), SPIRITUAL("Spiritual", "🧘"), OTHER("Other", "📍");

    companion object {
        fun of(value: String?) = entries.firstOrNull { it.name.equals(value, true) } ?: GENERAL
    }
}

@Entity(
    tableName = "places",
    foreignKeys = [ForeignKey(entity = Trip::class, parentColumns = ["id"], childColumns = ["tripId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("tripId")],
)
data class Place(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: Long,
    val name: String = "",
    val category: String = PlaceCategory.GENERAL.name,
    val address: String = "",
    val latitude: Double? = null,
    val longitude: Double? = null,
    val countryCode: String = "",
    val countryName: String = "",
    val city: String = "",
    val visitAt: LocalDateTime? = null,
    val visited: Boolean = false,
    val rating: Float? = null,
    val notes: String = "",
    val link: String = "",
)

@Entity(
    tableName = "itinerary_items",
    foreignKeys = [ForeignKey(entity = Trip::class, parentColumns = ["id"], childColumns = ["tripId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("tripId")],
)
data class ItineraryItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: Long,
    val date: LocalDate,
    val time: LocalTime? = null,
    val endTime: LocalTime? = null,
    val title: String,
    val notes: String = "",
    val emoji: String = "⭐",
    val isOptional: Boolean = false,
)

@Entity(
    tableName = "checklist_items",
    foreignKeys = [ForeignKey(entity = Trip::class, parentColumns = ["id"], childColumns = ["tripId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("tripId")],
)
data class ChecklistItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: Long,
    val listName: String = "Packing",
    val name: String,
    val isChecked: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "journal_entries",
    foreignKeys = [ForeignKey(entity = Trip::class, parentColumns = ["id"], childColumns = ["tripId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("tripId")],
)
data class JournalEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: Long,
    val date: LocalDate,
    val title: String,
    val content: String = "",
    val mood: String = "😊",
    val photoPath: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

object OwnerType {
    const val TRIP = "trip"
    const val TRANSPORT = "transport"
    const val LODGING = "lodging"
}

@Entity(
    tableName = "attachments",
    foreignKeys = [ForeignKey(entity = Trip::class, parentColumns = ["id"], childColumns = ["tripId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("tripId"), Index("ownerType", "ownerId")],
)
data class Attachment(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: Long,
    val ownerType: String,
    val ownerId: Long,
    val name: String,
    val filePath: String,
    val mimeType: String,
    val addedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "memories",
    foreignKeys = [ForeignKey(entity = Trip::class, parentColumns = ["id"], childColumns = ["tripId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("tripId")],
)
data class Memory(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: Long,
    val filePath: String,
    val caption: String = "",
    val addedAt: Long = System.currentTimeMillis(),
)

/** Everything belonging to one trip, assembled by the repository. */
data class TripFull(
    val trip: Trip,
    val transports: List<Transport> = emptyList(),
    val lodgings: List<Lodging> = emptyList(),
    val places: List<Place> = emptyList(),
    val itinerary: List<ItineraryItem> = emptyList(),
    val checklist: List<ChecklistItem> = emptyList(),
    val journal: List<JournalEntry> = emptyList(),
    val attachments: List<Attachment> = emptyList(),
    val memories: List<Memory> = emptyList(),
)
