package com.wanderlust.app.ai

import android.util.Base64
import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.messages.Base64ImageSource
import com.anthropic.models.messages.Base64PdfSource
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.DocumentBlockParam
import com.anthropic.models.messages.ImageBlockParam
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.TextBlockParam
import com.anthropic.models.messages.ToolResultBlockParam
import com.anthropic.models.messages.WebSearchTool20260209
import com.wanderlust.app.data.AppSettings
import com.wanderlust.app.data.SettingsStore
import com.wanderlust.app.data.TravelRepository
import com.wanderlust.app.domain.ItineraryBuilder
import com.wanderlust.app.domain.JournalSummarizer
import com.wanderlust.app.domain.dateRange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class AiException(message: String) : Exception(message)

/** Fields Claude extracted from a ticket / booking confirmation. */
data class BookingDraft(
    val kind: String,                  // "transport" | "lodging" | "unknown"
    val transportType: String = "PLANE",
    val carrier: String = "",
    val number: String = "",
    val fromLocation: String = "",
    val toLocation: String = "",
    val fromCode: String = "",
    val toCode: String = "",
    val departure: LocalDateTime? = null,
    val arrival: LocalDateTime? = null,
    val seat: String = "",
    val gate: String = "",
    val bookingRef: String = "",
    val hotelName: String = "",
    val address: String = "",
    val checkIn: LocalDateTime? = null,
    val checkOut: LocalDateTime? = null,
    val passenger: String = "",
    val notes: String = "",
)

/**
 * Wraps the Claude API (official Anthropic Java SDK, used from Kotlin).
 * All calls are blocking SDK calls, so they run on Dispatchers.IO.
 */
class ClaudeService(private val settings: SettingsStore, private val repo: TravelRepository) {

    @Volatile private var cached: Pair<String, AnthropicClient>? = null

    private fun client(s: AppSettings): AnthropicClient {
        if (s.anthropicApiKey.isBlank()) throw AiException("Add your Anthropic API key in Settings to use AI features.")
        cached?.let { (key, c) -> if (key == s.anthropicApiKey) return c }
        val c = AnthropicOkHttpClient.builder().apiKey(s.anthropicApiKey).build()
        cached = s.anthropicApiKey to c
        return c
    }

    /** Server-side refusal fallback (beta), on by default for the models that support it. */
    private fun MessageCreateParams.Builder.withFallbacks(model: String) = apply {
        if (model == "claude-opus-5-5" || model == "claude-sonnet-5-5") {
            putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
            putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
        }
    }

    private fun Message.text(): String = content().mapNotNull { b -> b.text().map { it.text() }.orElse(null) }
        .joinToString("\n").trim()

    private fun Message.checkRefusal() {
        if (stopReason().orElse(null) == StopReason.REFUSAL) {
            throw AiException("Claude declined this request. Try rephrasing it.")
        }
    }

    private inline fun <T> friendly(block: () -> T): T = try {
        block()
    } catch (e: AiException) {
        throw e
    } catch (e: UnauthorizedException) {
        throw AiException("Your Anthropic API key was rejected. Check it in Settings.")
    } catch (e: RateLimitException) {
        throw AiException("Rate limited by the Claude API — please wait a moment and try again.")
    } catch (e: AnthropicServiceException) {
        throw AiException("Claude API error (${e.statusCode()}): ${e.message}")
    } catch (e: Exception) {
        throw AiException("Couldn't reach Claude: ${e.message ?: e.javaClass.simpleName}")
    }

    // ------------------------------------------------------------------------------------------
    // Travel agent chat
    // ------------------------------------------------------------------------------------------

    /**
     * One user turn of the travel agent. [history] is the full conversation (including tool calls and
     * thinking blocks, passed back unchanged) and is appended to in place.
     */
    suspend fun chat(history: MutableList<MessageParam>, userText: String, onStatus: (String) -> Unit): String =
        withContext(Dispatchers.IO) {
            val s = settings.state.value
            val client = client(s)
            history += MessageParam.ofUser(userText)
            val system = AgentTools.SYSTEM_PROMPT + "\n\n" + buildContext(s)

            friendly {
                var finalText = ""
                for (step in 0 until 10) {
                    val params = MessageCreateParams.builder()
                        .model(s.model)
                        .maxTokens(16000L)
                        .system(system)
                        .messages(history.toList())
                        .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
                        .addTool(WebSearchTool20260209.builder().maxUses(5L).build())
                        .apply { AgentTools.definitions.forEach { addTool(it) } }
                        .withFallbacks(s.model)
                        .build()

                    val response = client.messages().create(params)
                    history += response.toParam()
                    response.checkRefusal()
                    val text = response.text()
                    if (text.isNotBlank()) finalText = text

                    when (response.stopReason().orElse(null)) {
                        StopReason.TOOL_USE -> {
                            val results = response.content().mapNotNull { it.toolUse().orElse(null) }.map { call ->
                                onStatus(AgentTools.statusFor(call.name()))
                                val input = runCatching {
                                    JSONObject(call._input().convert(Map::class.java) as Map<*, *>)
                                }.getOrElse { JSONObject() }
                                val (output, isError) = try {
                                    AgentTools.execute(repo, call.name(), input) to false
                                } catch (e: Exception) {
                                    "Error: ${e.message}" to true
                                }
                                ContentBlockParam.ofToolResult(
                                    ToolResultBlockParam.builder().toolUseId(call.id()).content(output).isError(isError).build()
                                )
                            }
                            // all tool results go back in a single user message
                            history += MessageParam.ofUserOfContentBlockParams(results)
                        }
                        StopReason.PAUSE_TURN -> onStatus("Searching the web…") // server tool still working; resend
                        StopReason.MAX_TOKENS -> return@friendly finalText.ifBlank { "(The answer was too long — try a narrower question.)" }
                        else -> return@friendly finalText
                    }
                }
                finalText.ifBlank { "I couldn't finish that — please try again." }
            }
        }

    private suspend fun buildContext(s: AppSettings): String {
        val trips = repo.tripsOnce()
        return buildString {
            appendLine("Today is ${LocalDate.now()} (${LocalDate.now().dayOfWeek}).")
            if (s.travelerName.isNotBlank()) appendLine("The traveler's name is ${s.travelerName}.")
            if (s.homeCity.isNotBlank()) appendLine("The traveler's home city is ${s.homeCity}.")
            if (trips.isEmpty()) appendLine("The traveler has no trips saved yet.")
            else {
                appendLine("Saved trips (use get_trip_details for bookings and itinerary):")
                trips.forEach { appendLine("- id ${it.id}: ${it.name} — ${it.destination} (${dateRange(it.startDate, it.endDate)})") }
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Ticket / booking extraction (image or PDF)
    // ------------------------------------------------------------------------------------------

    suspend fun extractBooking(path: String, mimeType: String): BookingDraft = withContext(Dispatchers.IO) {
        val s = settings.state.value
        val client = client(s)
        val bytes = repo.fileStore().readBytes(path)
        if (bytes.size > 20 * 1024 * 1024) throw AiException("That file is too large to analyse (max 20 MB).")
        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)

        val fileBlock = when {
            mimeType == "application/pdf" -> ContentBlockParam.ofDocument(
                DocumentBlockParam.builder().source(Base64PdfSource.builder().data(b64).build()).build()
            )
            mimeType.startsWith("image/") -> {
                val media = when (mimeType) {
                    "image/png" -> Base64ImageSource.MediaType.IMAGE_PNG
                    "image/webp" -> Base64ImageSource.MediaType.IMAGE_WEBP
                    "image/gif" -> Base64ImageSource.MediaType.IMAGE_GIF
                    else -> Base64ImageSource.MediaType.IMAGE_JPEG
                }
                ContentBlockParam.ofImage(
                    ImageBlockParam.builder().source(Base64ImageSource.builder().data(b64).mediaType(media).build()).build()
                )
            }
            else -> throw AiException("Only PDFs and images can be scanned.")
        }

        friendly {
            val params = MessageCreateParams.builder()
                .model(s.model)
                .maxTokens(4000L)
                .system(EXTRACT_PROMPT)
                .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
                .addUserMessageOfContentBlockParams(listOf(
                    fileBlock,
                    ContentBlockParam.ofText(TextBlockParam.builder().text("Extract the booking details from this document as JSON.").build()),
                ))
                .withFallbacks(s.model)
                .build()
            val response = client.messages().create(params)
            response.checkRefusal()
            parseDraft(response.text())
        }
    }

    private fun parseDraft(raw: String): BookingDraft {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        val o = runCatching { JSONObject(raw.substring(start, end + 1)) }.getOrElse { throw AiException("Couldn't read booking details from that file.") }
        fun str(k: String) = o.optString(k, "").takeIf { it != "null" } ?: ""
        fun dt(k: String) = str(k).takeIf { it.isNotBlank() }?.let { v ->
            runCatching { LocalDateTime.parse(v.take(19)) }.getOrNull()
                ?: runCatching { LocalDate.parse(v.take(10)).atTime(LocalTime.NOON) }.getOrNull()
        }
        return BookingDraft(
            kind = str("kind").ifBlank { "unknown" },
            transportType = str("transport_type").uppercase().ifBlank { "PLANE" },
            carrier = str("carrier"), number = str("number"),
            fromLocation = str("from_location"), toLocation = str("to_location"),
            fromCode = str("from_code"), toCode = str("to_code"),
            departure = dt("departure"), arrival = dt("arrival"),
            seat = str("seat"), gate = str("gate"), bookingRef = str("booking_reference"),
            hotelName = str("hotel_name"), address = str("address"),
            checkIn = dt("check_in"), checkOut = dt("check_out"),
            passenger = str("passenger_name"), notes = str("notes"),
        )
    }

    // ------------------------------------------------------------------------------------------
    // Journal story
    // ------------------------------------------------------------------------------------------

    suspend fun journalStory(tripId: Long): String = withContext(Dispatchers.IO) {
        val s = settings.state.value
        val client = client(s)
        val full = repo.tripFullOnce(tripId) ?: throw AiException("Trip not found")
        if (full.journal.isEmpty()) throw AiException("Write a few journal entries first.")
        friendly {
            val params = MessageCreateParams.builder()
                .model(s.model)
                .maxTokens(8000L)
                .system(STORY_PROMPT)
                .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.MEDIUM).build())
                .addUserMessage(JournalSummarizer.journalText(full) + "\n\nItinerary for reference:\n" + ItineraryBuilder.asText(full))
                .withFallbacks(s.model)
                .build()
            val response = client.messages().create(params)
            response.checkRefusal()
            response.text()
        }
    }

    companion object {
        private val EXTRACT_PROMPT = """
            You read travel documents: flight e-tickets, boarding passes, train/bus tickets, ferry tickets and hotel
            or rental booking confirmations. Reply with ONE JSON object and nothing else, using exactly these keys
            (use "" when a value is not in the document; never invent values):
            {
              "kind": "transport" | "lodging" | "unknown",
              "transport_type": "PLANE" | "TRAIN" | "BUS" | "CAR" | "BOAT" | "OTHER",
              "carrier": "airline / rail company / operator name",
              "number": "flight or train number, e.g. LH 400",
              "from_location": "departure city or airport name",
              "to_location": "arrival city or airport name",
              "from_code": "IATA airport or station code",
              "to_code": "IATA airport or station code",
              "departure": "local departure time as ISO-8601 yyyy-MM-ddTHH:mm:ss",
              "arrival": "local arrival time as ISO-8601 yyyy-MM-ddTHH:mm:ss",
              "seat": "", "gate": "",
              "booking_reference": "PNR / confirmation / reservation number",
              "hotel_name": "", "address": "",
              "check_in": "ISO-8601 local date-time", "check_out": "ISO-8601 local date-time",
              "passenger_name": "",
              "notes": "one short line of other useful info (baggage, terminal, breakfast included...)"
            }
            If a document has several flight legs, describe the first leg and list the others in "notes".
        """.trimIndent()

        private val STORY_PROMPT = """
            You turn a traveler's raw journal entries into a beautiful travel-journal summary they will treasure.
            Write in second person ("you"), warm and vivid but faithful to what they wrote — do not invent events,
            people or places that are not in the entries. Structure:
            1. A short evocative title on its own line, starting with "# ".
            2. A 2–3 sentence opening that captures the spirit of the trip.
            3. "## Highlights" — 3 to 6 bullet points of the best moments, each starting with a fitting emoji.
            4. "## Day by day" — one compact paragraph per day that has entries.
            5. "## In one line" — a single memorable sentence.
            Keep it under 450 words. Use plain Markdown only (headings, bullets, bold).
        """.trimIndent()
    }
}
