package com.wanderlust.app.domain

import com.wanderlust.app.AppContainer
import com.wanderlust.app.ai.PlanDraft
import com.wanderlust.app.ai.SourceFile
import com.wanderlust.app.data.Attachment
import com.wanderlust.app.data.ItineraryItem
import com.wanderlust.app.data.Lodging
import com.wanderlust.app.data.OwnerType
import com.wanderlust.app.data.Place
import com.wanderlust.app.data.Transport
import com.wanderlust.app.data.Trip
import com.wanderlust.app.ui.editors.applyDraft
import java.io.File
import java.util.UUID

/** Saves what Claude extracted (only the items the user kept) into a trip, attaching source files. */
object PlanSaver {

    /**
     * @param tripId existing trip, or 0 to create one from the plan
     * @param copyAll attach copies and leave the original files alone (used from an editor that still owns them)
     * @return the trip id
     */
    suspend fun save(app: AppContainer, tripId: Long, plan: PlanDraft, files: List<SourceFile>, keepUnusedFilesOnTrip: Boolean = true, copyAll: Boolean = false): Long {
        val repo = app.repo
        val tid = if (tripId != 0L) tripId else repo.saveTrip(
            Trip(
                name = plan.destination.ifBlank { "New trip" },
                destination = plan.destination,
                startDate = plan.startDate ?: (plan.transports.mapNotNull { it.departure } + plan.lodgings.mapNotNull { it.checkIn }).minOrNull()?.toLocalDate(),
                endDate = plan.endDate ?: (plan.transports.mapNotNull { it.arrival } + plan.lodgings.mapNotNull { it.checkOut }).maxOrNull()?.toLocalDate(),
                themeIndex = (0..6).random(),
            )
        ).also { repo.seedPackingList(it) }

        // each attachment owns its file (deleting a booking deletes its file), so later uses get a copy
        val used = mutableSetOf<Int>()
        suspend fun attach(index: Int, ownerType: String, ownerId: Long) {
            val f = files.getOrNull(index) ?: return
            val path = if (used.add(index) && !copyAll) f.path else {
                val src = File(f.path)
                File(src.parentFile, "${UUID.randomUUID()}.${src.extension}").also { src.copyTo(it) }.absolutePath
            }
            repo.addAttachment(Attachment(tripId = tid, ownerType = ownerType, ownerId = ownerId, name = f.name, filePath = path, mimeType = f.mimeType))
        }

        plan.transports.forEach { d -> attach(d.sourceFile, OwnerType.TRANSPORT, repo.saveTransport(Transport(tripId = tid).applyDraft(d))) }
        plan.lodgings.forEach { d -> attach(d.sourceFile, OwnerType.LODGING, repo.saveLodging(Lodging(tripId = tid).applyDraft(d))) }
        plan.places.forEach { p ->
            repo.savePlace(Place(tripId = tid, name = p.name, category = p.category, city = p.city, countryName = p.country, visitAt = p.visitAt, notes = p.notes))
        }
        plan.activities.forEach { a ->
            repo.saveItinerary(ItineraryItem(tripId = tid, date = a.date, time = a.time, endTime = a.endTime, title = a.title, emoji = a.emoji, notes = a.notes))
        }
        // itinerary documents etc. that didn't produce a booking: keep them as trip documents
        if (!copyAll) files.forEachIndexed { i, f ->
            if (i !in used) {
                if (keepUnusedFilesOnTrip) attach(i, OwnerType.TRIP, tid) else File(f.path).delete()
            }
        }
        return tid
    }
}
