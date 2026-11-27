package com.wanderlust.app.ai

import com.anthropic.core.JsonValue
import com.anthropic.models.messages.Tool
import com.wanderlust.app.data.ChecklistItem
import com.wanderlust.app.data.ItineraryItem
import com.wanderlust.app.data.Place
import com.wanderlust.app.data.PlaceCategory
import com.wanderlust.app.data.TravelRepository
import com.wanderlust.app.domain.ItineraryBuilder
import com.wanderlust.app.domain.dateRange
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime

/**
 * Client-side tools the travel agent can call. They read and write the traveler's on-device data.
 * (Web search is an Anthropic server tool and is declared separately.)
 */
object AgentTools {

    val SYSTEM_PROMPT = """
        You are Wanderlust, a friendly, expert travel assistant inside the traveler's trip-planning app.
        You help with anything travel-related: destinations, itineraries, visas and entry rules, packing,
        local customs, budgets, transport between places, food, safety, weather and timing.

        You can see the traveler's saved trips through tools. Look up a trip with get_trip_details before
        answering questions about its bookings or plans. When the traveler asks you to plan, add or remember
        something, use the write tools (add_itinerary_item, add_checklist_item, add_place) and then tell them
        what you added. Only add items when they ask for it or clearly agree.

        Use web search for anything time-sensitive or factual that may have changed (entry requirements,
        opening hours, events, prices, weather) and mention where the information came from.
        Keep answers concise and skimmable for a phone screen: short paragraphs, bullets, a few emoji at most.
    """.trimIndent()

    private fun prop(type: String, description: String, enum: List<String>? = null): JsonValue =
        JsonValue.from(buildMap {
            put("type", type)
            put("description", description)
            if (enum != null) put("enum", enum)
        })

    private fun tool(name: String, description: String, required: List<String>, vararg props: Pair<String, JsonValue>): Tool =
        Tool.builder()
            .name(name)
            .description(description)
            // answers are streamed; stream tool inputs too (they are parsed defensively in ClaudeService)
            .eagerInputStreaming(true)
            .inputSchema(
                Tool.InputSchema.builder()
                    .properties(Tool.InputSchema.Properties.builder().apply {
                        props.forEach { (k, v) -> putAdditionalProperty(k, v) }
                    }.build())
                    .required(required)
                    .build()
            )
            .build()

    val definitions: List<Tool> = listOf(
        tool("list_trips", "List all of the traveler's saved trips with ids, destinations and dates.", emptyList()),
        tool(
            "get_trip_details",
            "Get the full day-by-day itinerary of one trip: flights and other transport, hotels, places, activities and checklist.",
            listOf("trip_id"),
            "trip_id" to prop("integer", "Trip id from list_trips or the context"),
        ),
        tool(
            "add_itinerary_item",
            "Add an activity to a trip's itinerary on a given day.",
            listOf("trip_id", "date", "title"),
            "trip_id" to prop("integer", "Trip id"),
            "date" to prop("string", "Day of the activity, yyyy-MM-dd"),
            "time" to prop("string", "Optional start time, HH:mm (24h)"),
            "title" to prop("string", "Short title, e.g. 'Sunset at Oia'"),
            "notes" to prop("string", "Optional details / tips"),
            "emoji" to prop("string", "One emoji that represents the activity"),
        ),
        tool(
            "add_checklist_item",
            "Add an item to a trip's checklist (e.g. packing list or to-dos).",
            listOf("trip_id", "name"),
            "trip_id" to prop("integer", "Trip id"),
            "name" to prop("string", "Checklist item text"),
            "list_name" to prop("string", "List name, e.g. 'Packing' or 'To-do'. Defaults to Packing."),
        ),
        tool(
            "add_place",
            "Save a place to visit (attraction, restaurant, museum...) to a trip.",
            listOf("trip_id", "name"),
            "trip_id" to prop("integer", "Trip id"),
            "name" to prop("string", "Place name"),
            "category" to prop("string", "Category", PlaceCategory.entries.map { it.name }),
            "city" to prop("string", "City"),
            "notes" to prop("string", "Why it's worth visiting / tips"),
            "date" to prop("string", "Optional planned visit date, yyyy-MM-dd"),
        ),
    )

    fun statusFor(tool: String) = when (tool) {
        "list_trips" -> "Looking at your trips…"
        "get_trip_details" -> "Reading your itinerary…"
        "add_itinerary_item" -> "Adding to your itinerary…"
        "add_checklist_item" -> "Updating your checklist…"
        "add_place" -> "Saving the place…"
        else -> "Working…"
    }

    suspend fun execute(repo: TravelRepository, name: String, input: JSONObject): String {
        fun tripId(): Long = input.optLong("trip_id", -1).takeIf { it > 0 } ?: error("trip_id is required")
        return when (name) {
            "list_trips" -> repo.tripsOnce().takeIf { it.isNotEmpty() }?.joinToString("\n") {
                "id ${it.id}: ${it.name} — ${it.destination.ifBlank { "?" }} (${dateRange(it.startDate, it.endDate)})"
            } ?: "No trips saved yet."

            "get_trip_details" -> repo.tripFullOnce(tripId())?.let { ItineraryBuilder.asText(it) } ?: error("No trip with that id")

            "add_itinerary_item" -> {
                val trip = repo.trip(tripId()) ?: error("No trip with that id")
                val date = LocalDate.parse(input.getString("date"))
                val time = input.optString("time").takeIf { it.isNotBlank() }?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
                val id = repo.saveItinerary(
                    ItineraryItem(
                        tripId = trip.id, date = date, time = time,
                        title = input.getString("title"),
                        notes = input.optString("notes"),
                        emoji = input.optString("emoji").ifBlank { "⭐" },
                    )
                )
                "Added itinerary item $id on $date to '${trip.name}'."
            }

            "add_checklist_item" -> {
                val trip = repo.trip(tripId()) ?: error("No trip with that id")
                repo.saveChecklist(ChecklistItem(tripId = trip.id, name = input.getString("name"),
                    listName = input.optString("list_name").ifBlank { "Packing" }))
                "Added '${input.getString("name")}' to the checklist of '${trip.name}'."
            }

            "add_place" -> {
                val trip = repo.trip(tripId()) ?: error("No trip with that id")
                val date = input.optString("date").takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                repo.savePlace(
                    Place(
                        tripId = trip.id, name = input.getString("name"),
                        category = PlaceCategory.of(input.optString("category")).name,
                        city = input.optString("city"), notes = input.optString("notes"),
                        visitAt = date?.atStartOfDay(),
                    )
                )
                "Saved '${input.getString("name")}' to '${trip.name}'."
            }

            else -> error("Unknown tool $name")
        }
    }
}
