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
import com.anthropic.models.messages.JsonOutputFormat
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

/** A flight/transport or a stay that Claude extracted from a document. */
data class BookingDraft(
    val kind: String,                  // "transport" | "lodging" | "unknown"
    val sourceFile: Int = -1,          // index of the uploaded file it came from
    val lodgingType: String = "HOTEL",
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

data class PlaceDraft(val sourceFile: Int, val name: String, val category: String, val city: String, val country: String, val visitAt: LocalDateTime?, val notes: String)

data class ActivityDraft(val sourceFile: Int, val date: LocalDate, val time: LocalTime?, val endTime: LocalTime?, val title: String, val emoji: String, val notes: String)

/** Everything Claude found across a set of uploaded tickets / confirmations / itineraries. */
data class PlanDraft(
    val destination: String,
    val startDate: LocalDate?,
    val endDate: LocalDate?,
    val transports: List<BookingDraft>,
    val lodgings: List<BookingDraft>,
    val places: List<PlaceDraft>,
    val activities: List<ActivityDraft>,
) {
    val isEmpty get() = transports.isEmpty() && lodgings.isEmpty() && places.isEmpty() && activities.isEmpty()
    val count get() = transports.size + lodgings.size + places.size + activities.size
}

/** A file to analyse. */
data class SourceFile(val path: String, val mimeType: String, val name: String)

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
    // Document extraction: tickets, hotel confirmations, whole itineraries (images and PDFs)
    // ------------------------------------------------------------------------------------------

    /**
     * Reads any number of images / PDFs in one request and returns every flight, stay, place and
     * activity found, each tagged with the index of the file it came from. [tripStart]/[tripEnd]
     * help Claude resolve "Day 1"-style itineraries and dates without a year.
     */
    suspend fun extractPlan(files: List<SourceFile>, tripDestination: String = "", tripStart: LocalDate? = null, tripEnd: LocalDate? = null): PlanDraft =
        withContext(Dispatchers.IO) {
            if (files.isEmpty()) throw AiException("Add at least one file.")
            val s = settings.state.value
            val client = client(s)
            var totalBytes = 0L
            val blocks = mutableListOf<ContentBlockParam>()
            files.forEachIndexed { i, f ->
                blocks += ContentBlockParam.ofText(TextBlockParam.builder().text("File $i: ${f.name}").build())
                when {
                    f.mimeType == "application/pdf" -> {
                        val bytes = java.io.File(f.path).readBytes()
                        totalBytes += bytes.size
                        blocks += ContentBlockParam.ofDocument(
                            DocumentBlockParam.builder().source(Base64PdfSource.builder().data(Base64.encodeToString(bytes, Base64.NO_WRAP)).build()).build()
                        )
                    }
                    f.mimeType.startsWith("image/") -> {
                        val jpeg = ImageShrinker.toJpeg(f.path) ?: throw AiException("Couldn't read image ${f.name}")
                        totalBytes += jpeg.size
                        blocks += ContentBlockParam.ofImage(
                            ImageBlockParam.builder().source(
                                Base64ImageSource.builder().data(Base64.encodeToString(jpeg, Base64.NO_WRAP)).mediaType(Base64ImageSource.MediaType.IMAGE_JPEG).build()
                            ).build()
                        )
                    }
                    else -> throw AiException("${f.name}: only PDFs and images can be read.")
                }
            }
            if (totalBytes > 22L * 1024 * 1024) throw AiException("Those files are too large to read in one go (max ~22 MB). Try fewer files at a time.")
            val context = buildString {
                append("Today is ${LocalDate.now()}.")
                if (tripDestination.isNotBlank()) append(" The trip is to $tripDestination.")
                if (tripStart != null) append(" The trip runs ${tripStart}${tripEnd?.let { " to $it" } ?: ""}; if the documents say \"Day 1\", \"Day 2\"... without dates, Day 1 is $tripStart.")
                append(" Extract everything from the ${files.size} file(s) above.")
            }
            blocks += ContentBlockParam.ofText(TextBlockParam.builder().text(context).build())

            friendly {
                val params = MessageCreateParams.builder()
                    .model(s.model)
                    .maxTokens(16000L)
                    .system(PLAN_PROMPT)
                    .outputConfig(
                        OutputConfig.builder()
                            .effort(OutputConfig.Effort.MEDIUM)
                            .format(JsonOutputFormat.builder().schema(PlanSchema.schema).build())
                            .build()
                    )
                    .addUserMessageOfContentBlockParams(blocks)
                    .withFallbacks(s.model)
                    .build()
                val response = client.messages().create(params)
                response.checkRefusal()
                if (response.stopReason().orElse(null) == StopReason.MAX_TOKENS) throw AiException("That itinerary is too long to read at once — try fewer pages per import.")
                PlanSchema.parse(response.text(), files.size)
            }
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
        private val PLAN_PROMPT = """
            You read travel documents — flight e-tickets, boarding passes, train/bus/ferry tickets, hotel and
            rental confirmations, and full trip itineraries from travel agents or tour operators — and extract
            structured trip data. Rules:
            - Extract every distinct booking: one entry per flight leg / train ride, one entry per stay.
              A single file may contain several bookings; several files may describe the same booking — merge duplicates.
            - source_file is the number of the file the item came from (files are labelled "File 0", "File 1", ...).
            - Use local times exactly as printed, formatted yyyy-MM-ddTHH:mm for date-times, yyyy-MM-dd for dates, HH:mm for times.
              Use "" for anything not stated. Never invent bookings, places, times or reference numbers.
            - Put sightseeing stops, attractions, restaurants and landmarks named in an itinerary into "places"
              (with the day they are planned, if given). Put other scheduled things (tours, transfers, meals,
              free time, shows, meetings) into "activities" with a fitting single emoji.
            - Do not repeat flights or hotel stays as activities or places.
            - trip: overall destination (e.g. "Taipei, Taiwan") and first/last dates covered, if apparent.
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
