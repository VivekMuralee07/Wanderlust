package com.wanderlust.app.ui.itinerary

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.compose.ui.graphics.toArgb
import com.wanderlust.app.data.TransportType
import com.wanderlust.app.data.TripFull
import com.wanderlust.app.domain.ItineraryBuilder
import com.wanderlust.app.domain.TimelineEvent
import com.wanderlust.app.domain.dateRange
import com.wanderlust.app.domain.longDayFmt
import com.wanderlust.app.domain.timeFmt
import com.wanderlust.app.ui.theme.palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Renders the itinerary as a designed, multi-page A4 PDF (cover band, day headers, timeline). */
object ItineraryPdf {
    private const val W = 595
    private const val H = 842
    private const val M = 40f

    suspend fun export(context: Context, full: TripFull, weather: com.wanderlust.app.weather.TripWeather = com.wanderlust.app.weather.TripWeather()): File = withContext(Dispatchers.IO) {
        val pal = palette(full.trip.themeIndex)
        val c1 = pal.colors.first().toArgb(); val c2 = pal.colors.last().toArgb(); val accent = pal.accent.toArgb()
        val ink = 0xFF1B1F3B.toInt(); val gray = 0xFF6B7194.toInt(); val light = 0xFFE8EAF6.toInt()
        val serifBold = Typeface.create(Typeface.SERIF, Typeface.BOLD)

        val doc = PdfDocument()
        var pageNo = 0
        lateinit var page: PdfDocument.Page
        lateinit var canvas: Canvas
        var y = 0f

        fun text(size: Float, color: Int, bold: Boolean = false, face: Typeface? = null) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size; this.color = color
            typeface = face ?: if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        fun layout(s: String, p: TextPaint, width: Float): StaticLayout =
            StaticLayout.Builder.obtain(s, 0, s.length, p, width.toInt()).setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0f, 1.1f).build()
        fun StaticLayout.drawAt(x: Float, top: Float) { canvas.save(); canvas.translate(x, top); draw(canvas); canvas.restore() }

        fun footer() {
            val p = text(8f, gray)
            canvas.drawText("Wanderlust · ${full.trip.name}", M, H - 20f, p)
            canvas.drawText("$pageNo", W - M, H - 20f, p.apply { textAlign = Paint.Align.RIGHT })
        }
        fun newPage(first: Boolean) {
            if (pageNo > 0) { footer(); doc.finishPage(page) }
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, pageNo).create())
            canvas = page.canvas
            if (first) {
                val band = Paint().apply { shader = LinearGradient(0f, 0f, W.toFloat(), 230f, c1, c2, Shader.TileMode.CLAMP) }
                canvas.drawRect(0f, 0f, W.toFloat(), 230f, band)
                val deco = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x30FFFFFF }
                canvas.drawCircle(W - 90f, 70f, 110f, deco); canvas.drawCircle(W - 30f, 200f, 60f, deco)
                canvas.drawText("TRAVEL ITINERARY", M, 60f, text(10f, 0xDDFFFFFF.toInt(), bold = true).apply { letterSpacing = 0.3f })
                val title = layout(full.trip.destination.ifBlank { full.trip.name }, text(34f, 0xFFFFFFFF.toInt(), face = serifBold), W - 2 * M)
                title.drawAt(M, 72f)
                var ty = 72f + title.height + 6f
                if (full.trip.destination.isNotBlank() && full.trip.name != full.trip.destination) {
                    canvas.drawText(full.trip.name, M, ty + 12f, text(13f, 0xEEFFFFFF.toInt())); ty += 18f
                }
                canvas.drawText(dateRange(full.trip.startDate, full.trip.endDate), M, ty + 14f, text(12f, 0xDDFFFFFF.toInt()))
                val flights = full.transports.count { TransportType.of(it.type) == TransportType.PLANE }
                canvas.drawText("$flights flights  ·  ${full.lodgings.size} stays  ·  ${full.places.size} places", M, 210f, text(11f, 0xFFFFFFFF.toInt(), bold = true))
                y = 262f
            } else {
                canvas.drawRect(0f, 0f, W.toFloat(), 6f, Paint().apply { shader = LinearGradient(0f, 0f, W.toFloat(), 0f, c1, c2, Shader.TileMode.CLAMP) })
                y = 44f
            }
        }
        fun ensure(space: Float) { if (y + space > H - 50f) newPage(false) }

        fun dayHeader(label: String, title: String, sub: String?) {
            ensure(70f)
            val pill = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = LinearGradient(M, y, M + 64f, y + 26f, c1, c2, Shader.TileMode.CLAMP) }
            canvas.drawRoundRect(RectF(M, y, M + 64f, y + 26f), 13f, 13f, pill)
            canvas.drawText(label, M + 32f, y + 17.5f, text(10f, 0xFFFFFFFF.toInt(), bold = true).apply { textAlign = Paint.Align.CENTER })
            canvas.drawText(title, M + 76f, y + 18f, text(15f, ink, face = serifBold))
            y += 32f
            if (!sub.isNullOrBlank()) { canvas.drawText(sub, M + 76f, y + 2f, text(9.5f, gray)); y += 12f }
            y += 6f
        }

        fun event(e: TimelineEvent, last: Boolean) {
            val textX = M + 96f
            val width = W - M - textX
            val titleL = layout("${e.emoji}  ${e.title}", text(11.5f, ink, bold = true), width)
            val subL = (listOf(e.subtitle, e.detail).filter { it.isNotBlank() }.joinToString("\n")).takeIf { it.isNotBlank() }?.let { layout(it, text(9.5f, gray), width) }
            val h = titleL.height + (subL?.height ?: 0) + 16f
            ensure(h)
            val railX = M + 78f
            canvas.drawText(e.time?.format(timeFmt) ?: "", M + 62f, y + 12f, text(10.5f, accent, bold = true).apply { textAlign = Paint.Align.RIGHT })
            if (!last) canvas.drawLine(railX, y + 6f, railX, y + h + 2f, Paint().apply { color = light; strokeWidth = 2f })
            canvas.drawCircle(railX, y + 8f, 5f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent })
            titleL.drawAt(textX, y)
            subL?.drawAt(textX, y + titleL.height + 2f)
            y += h
        }

        newPage(true)
        val it = ItineraryBuilder.build(full)
        it.days.forEach { d ->
            val w = weather.days[d.date]
            dayHeader(if (d.index >= 1) "DAY ${d.index}" else "TRAVEL", d.date.format(longDayFmt),
                listOfNotNull(w?.let { "${it.emoji} ${it.label} ${it.temps()}" + (it.place.takeIf { p -> p.isNotBlank() }?.let { p -> " in $p" } ?: "") + if (it.typical) " (typical)" else "" },
                    d.sleepingAt?.let { "Staying at $it" }).joinToString("   ·   ").ifBlank { null })
            if (d.events.isEmpty()) { ensure(20f); canvas.drawText("Free day — wander, rest, explore.", M + 96f, y + 10f, text(10f, gray)); y += 22f }
            d.events.forEachIndexed { i, e -> event(e, i == d.events.lastIndex) }
            y += 12f
        }
        if (it.unscheduled.isNotEmpty()) {
            dayHeader("IDEAS", "Anytime", null)
            it.unscheduled.forEachIndexed { i, e -> event(e, i == it.unscheduled.lastIndex) }
        }
        val todo = full.checklist.filter { !it.isChecked }
        if (todo.isNotEmpty()) {
            dayHeader("LIST", "Still to pack / do", null)
            todo.forEach { c -> ensure(16f); canvas.drawText("☐  ${c.name}", M + 96f, y + 10f, text(10.5f, ink)); y += 16f }
        }
        footer()
        doc.finishPage(page)

        val safeName = full.trip.name.replace(Regex("[^A-Za-z0-9]+"), "_").trim('_').ifBlank { "trip" }
        val out = File(File(context.cacheDir, "exports").apply { mkdirs() }, "${safeName}_itinerary.pdf")
        out.outputStream().use { doc.writeTo(it) }
        doc.close()
        out
    }
}
