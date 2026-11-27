package com.wanderlust.app.ai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.anthropic.core.JsonValue
import com.anthropic.models.messages.JsonOutputFormat
import com.wanderlust.app.data.LodgingType
import com.wanderlust.app.data.PlaceCategory
import com.wanderlust.app.data.TransportType
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** JSON schema (structured outputs) for document extraction, and its parser. */
object PlanSchema {

    private fun str(desc: String) = mapOf("type" to "string", "description" to desc)
    private fun enumOf(values: List<String>) = mapOf("type" to "string", "enum" to values)
    private fun obj(props: Map<String, Any>) = mapOf(
        "type" to "object",
        "additionalProperties" to false,
        "required" to props.keys.toList(),
        "properties" to props,
    )
    private fun arrayOf(item: Map<String, Any>) = mapOf("type" to "array", "items" to item)
    private val sourceFile = mapOf("type" to "integer", "description" to "Index of the file this came from")

    private val root: Map<String, Any> = obj(
        mapOf(
            "trip" to obj(mapOf(
                "destination" to str("Overall destination, e.g. 'Taipei, Taiwan'"),
                "start_date" to str("yyyy-MM-dd or empty"),
                "end_date" to str("yyyy-MM-dd or empty"),
            )),
            "transports" to arrayOf(obj(mapOf(
                "source_file" to sourceFile,
                "transport_type" to enumOf(listOf("PLANE", "TRAIN", "BUS", "CAR", "BOAT", "OTHER")),
                "carrier" to str("Airline / rail company / operator"),
                "number" to str("Flight or train number"),
                "from_location" to str("Departure city or airport/station name"),
                "to_location" to str("Arrival city or airport/station name"),
                "from_code" to str("IATA / station code"),
                "to_code" to str("IATA / station code"),
                "departure" to str("yyyy-MM-ddTHH:mm local"),
                "arrival" to str("yyyy-MM-ddTHH:mm local"),
                "seat" to str(""), "gate" to str(""),
                "booking_reference" to str("PNR / confirmation number"),
                "passenger_name" to str(""),
                "notes" to str("Short extra info: terminal, baggage, class"),
            ))),
            "lodgings" to arrayOf(obj(mapOf(
                "source_file" to sourceFile,
                "hotel_name" to str(""),
                "lodging_type" to enumOf(LodgingType.entries.map { it.name }),
                "address" to str(""),
                "check_in" to str("yyyy-MM-ddTHH:mm local (use 15:00 if only a date is given)"),
                "check_out" to str("yyyy-MM-ddTHH:mm local (use 11:00 if only a date is given)"),
                "booking_reference" to str("Reservation / confirmation number"),
                "notes" to str("Room type, breakfast, guests…"),
            ))),
            "places" to arrayOf(obj(mapOf(
                "source_file" to sourceFile,
                "name" to str("Attraction / restaurant / landmark"),
                "category" to enumOf(PlaceCategory.entries.map { it.name }),
                "city" to str(""), "country" to str(""),
                "date" to str("Planned visit date yyyy-MM-dd or empty"),
                "time" to str("HH:mm or empty"),
                "notes" to str(""),
            ))),
            "activities" to arrayOf(obj(mapOf(
                "source_file" to sourceFile,
                "date" to str("yyyy-MM-dd"),
                "time" to str("HH:mm or empty"),
                "end_time" to str("HH:mm or empty"),
                "title" to str("Short title"),
                "emoji" to str("One emoji"),
                "notes" to str(""),
            ))),
        )
    )

    val schema: JsonOutputFormat.Schema = JsonOutputFormat.Schema.builder().apply {
        root.forEach { (k, v) -> putAdditionalProperty(k, JsonValue.from(v)) }
    }.build()

    val receiptSchema: JsonOutputFormat.Schema = JsonOutputFormat.Schema.builder().apply {
        obj(mapOf(
            "merchant" to str("Shop / restaurant name"),
            "total" to mapOf("type" to "number", "description" to "Total paid"),
            "currency" to str("ISO 4217 code, e.g. TWD"),
            "date" to str("yyyy-MM-dd or empty"),
            "category" to enumOf(com.wanderlust.app.data.ExpenseCategory.entries.map { it.name }),
            "notes" to str("Very short: what was bought"),
        )).forEach { (k, v) -> putAdditionalProperty(k, JsonValue.from(v)) }
    }.build()

    fun parseReceipt(raw: String): ReceiptDraft {
        val o = runCatching { JSONObject(raw.substring(raw.indexOf('{'), raw.lastIndexOf('}') + 1)) }
            .getOrElse { throw AiException("Couldn't read that receipt.") }
        return ReceiptDraft(o.s("merchant"), o.optDouble("total", 0.0).takeIf { !it.isNaN() } ?: 0.0, o.s("currency").uppercase(),
            date(o.s("date")), com.wanderlust.app.data.ExpenseCategory.of(o.s("category")).name, o.s("notes"))
    }

    private fun JSONObject.s(k: String) = optString(k, "").takeIf { it != "null" }?.trim() ?: ""
    private fun date(v: String) = runCatching { LocalDate.parse(v.take(10)) }.getOrNull()
    private fun time(v: String) = runCatching { LocalTime.parse(v.take(5)) }.getOrNull()
    private fun dateTime(v: String): LocalDateTime? = if (v.isBlank()) null else
        runCatching { LocalDateTime.parse(v.take(16)) }.getOrNull() ?: date(v)?.atTime(12, 0)
    private fun JSONObject.list(k: String): List<JSONObject> = optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } } ?: emptyList()

    fun parse(raw: String, fileCount: Int): PlanDraft {
        val o = runCatching { JSONObject(raw.substring(raw.indexOf('{'), raw.lastIndexOf('}') + 1)) }
            .getOrElse { throw AiException("Couldn't read the details from those files.") }
        fun src(j: JSONObject) = j.optInt("source_file", 0).coerceIn(0, (fileCount - 1).coerceAtLeast(0))
        val trip = o.optJSONObject("trip") ?: JSONObject()
        return PlanDraft(
            destination = trip.s("destination"),
            startDate = date(trip.s("start_date")),
            endDate = date(trip.s("end_date")),
            transports = o.list("transports").map { t ->
                BookingDraft(
                    kind = "transport", sourceFile = src(t),
                    transportType = TransportType.of(t.s("transport_type")).name,
                    carrier = t.s("carrier"), number = t.s("number"),
                    fromLocation = t.s("from_location"), toLocation = t.s("to_location"),
                    fromCode = t.s("from_code").uppercase(), toCode = t.s("to_code").uppercase(),
                    departure = dateTime(t.s("departure")), arrival = dateTime(t.s("arrival")),
                    seat = t.s("seat"), gate = t.s("gate"), bookingRef = t.s("booking_reference"),
                    passenger = t.s("passenger_name"), notes = t.s("notes"),
                )
            },
            lodgings = o.list("lodgings").map { l ->
                BookingDraft(
                    kind = "lodging", sourceFile = src(l),
                    lodgingType = LodgingType.of(l.s("lodging_type")).name,
                    hotelName = l.s("hotel_name"), address = l.s("address"),
                    checkIn = dateTime(l.s("check_in")), checkOut = dateTime(l.s("check_out")),
                    bookingRef = l.s("booking_reference"), notes = l.s("notes"),
                )
            },
            places = o.list("places").filter { it.s("name").isNotBlank() }.map { p ->
                val d = date(p.s("date"))
                PlaceDraft(src(p), p.s("name"), PlaceCategory.of(p.s("category")).name, p.s("city"), p.s("country"),
                    d?.let { day -> time(p.s("time"))?.let { day.atTime(it) } ?: day.atStartOfDay() }, p.s("notes"))
            },
            activities = o.list("activities").mapNotNull { a ->
                val d = date(a.s("date")) ?: return@mapNotNull null
                ActivityDraft(src(a), d, time(a.s("time")), time(a.s("end_time")), a.s("title").ifBlank { return@mapNotNull null },
                    a.s("emoji").ifBlank { "⭐" }.take(8), a.s("notes"))
            },
        )
    }

}

/** Scales photos down so they fit the API's image limits (and upload faster). */
object ImageShrinker {
    private const val MAX_SIDE = 2000

    fun toJpeg(path: String): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val bmp = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val scale = MAX_SIDE.toFloat() / maxOf(bmp.width, bmp.height)
        val sized = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
        return ByteArrayOutputStream().use { out ->
            sized.compress(Bitmap.CompressFormat.JPEG, 85, out)
            if (sized !== bmp) sized.recycle()
            bmp.recycle()
            out.toByteArray()
        }
    }
}
