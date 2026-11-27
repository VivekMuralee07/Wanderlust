package com.wanderlust.app.ui.itinerary

import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.wanderlust.app.LocalApp
import com.wanderlust.app.data.TransportType
import com.wanderlust.app.domain.EventKind
import com.wanderlust.app.domain.ItineraryBuilder
import com.wanderlust.app.domain.ItineraryDay
import com.wanderlust.app.domain.TimelineEvent
import com.wanderlust.app.domain.dateRange
import com.wanderlust.app.domain.longDayFmt
import com.wanderlust.app.domain.timeFmt
import com.wanderlust.app.ui.components.EmptyState
import com.wanderlust.app.ui.theme.Display
import com.wanderlust.app.ui.theme.Night
import com.wanderlust.app.ui.theme.TripPalette
import com.wanderlust.app.ui.theme.TripPalettes
import kotlinx.coroutines.launch

/**
 * The "visually magnificent" itinerary: every flight, hotel, place and activity laid out on an
 * illustrated day-by-day timeline in the trip's colour theme. Exportable as a designed PDF.
 */
@Composable
fun VisualItineraryScreen(nav: NavHostController, tripId: Long) {
    val app = LocalApp.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val full by remember(tripId) { app.repo.tripFull(tripId) }.collectAsState(initial = null)
    val f = full ?: return
    var themeIndex by remember(f.trip.id) { mutableIntStateOf(f.trip.themeIndex) }
    val p = TripPalettes[themeIndex % TripPalettes.size]
    val itinerary = remember(f) { ItineraryBuilder.build(f) }
    val weather by com.wanderlust.app.weather.rememberTripWeather(f)

    Box(Modifier.fillMaxSize().background(Night)) {
        // themed backdrop
        Box(Modifier.fillMaxWidth().height(520.dp).background(Brush.verticalGradient(listOf(p.colors.first().copy(alpha = 0.85f), p.colors.last().copy(alpha = 0.25f), Night))))
        LazyColumn(contentPadding = PaddingValues(bottom = 48.dp)) {
            item {
                Row(Modifier.statusBarsPadding().padding(8.dp).fillMaxWidth()) {
                    val c = IconButtonDefaults.iconButtonColors(containerColor = Color.Black.copy(alpha = 0.25f), contentColor = Color.White)
                    IconButton(onClick = { nav.popBackStack() }, colors = c) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = {
                        val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, ItineraryBuilder.asText(f)) }
                        context.startActivity(Intent.createChooser(send, "Share itinerary"))
                    }, colors = c) { Icon(Icons.Default.Share, "Share as text") }
                    IconButton(onClick = {
                        scope.launch {
                            runCatching { ItineraryPdf.export(context, f.copy(trip = f.trip.copy(themeIndex = themeIndex))) }
                                .onSuccess { app.files.share(context, it, "application/pdf", "${f.trip.name} itinerary") }
                                .onFailure { Toast.makeText(context, "Export failed: ${it.message}", Toast.LENGTH_LONG).show() }
                        }
                    }, colors = c) { Icon(Icons.Default.PictureAsPdf, "Export PDF") }
                }
            }
            item {
                Column(Modifier.padding(horizontal = 24.dp)) {
                    Text("ITINERARY", letterSpacing = 4.sp, fontSize = 12.sp, color = Color.White.copy(alpha = 0.8f))
                    Text(f.trip.destination.ifBlank { f.trip.name }, fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 44.sp, lineHeight = 48.sp, color = Color.White)
                    Text(dateRange(f.trip.startDate, f.trip.endDate), color = Color.White.copy(alpha = 0.85f))
                    Spacer(Modifier.height(14.dp))
                    val flights = f.transports.count { TransportType.of(it.type) == TransportType.PLANE }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(plural(itinerary.days.size, "day"), plural(flights, "flight"), plural(f.lodgings.size, "stay"), plural(f.places.size, "place")).forEach {
                            Text(it, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.18f)).padding(horizontal = 10.dp, vertical = 5.dp))
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Text("STYLE", letterSpacing = 2.sp, fontSize = 10.sp, color = Color.White.copy(alpha = 0.7f))
                    Spacer(Modifier.height(6.dp))
                }
                LazyRow(contentPadding = PaddingValues(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(TripPalettes) { i, pal ->
                        Text(pal.name, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clip(RoundedCornerShape(50)).background(pal.brush)
                                .border(if (i == themeIndex) 2.dp else 0.dp, if (i == themeIndex) Color.White else Color.Transparent, RoundedCornerShape(50))
                                .clickable { themeIndex = i; app.appScope.launch { app.repo.saveTrip(f.trip.copy(themeIndex = i)) } }
                                .padding(horizontal = 14.dp, vertical = 8.dp))
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
            if (itinerary.days.isEmpty() && itinerary.unscheduled.isEmpty()) item {
                EmptyState("🧭", "Your itinerary is empty", "Add trip dates, flights, hotels and places — they’ll appear here on a beautiful timeline.")
            }
            itemsIndexed(itinerary.days, key = { _, d -> d.date.toString() }) { i, d -> DayBlock(d, p, i, weather[d.date]) }
            if (itinerary.unscheduled.isNotEmpty()) item {
                DayHeader("✦", "Anytime", "Ideas without a date yet", p)
                itinerary.unscheduled.forEach { EventRow(it, p, isLast = it == itinerary.unscheduled.last()) }
            }
        }
    }
}

@Composable
private fun DayBlock(d: ItineraryDay, p: TripPalette, index: Int, w: com.wanderlust.app.weather.DayWeather? = null) {
    // gentle staggered entrance
    val anim = remember { Animatable(0f) }
    LaunchedEffect(Unit) { anim.animateTo(1f, tween(450, delayMillis = (index * 60).coerceAtMost(400))) }
    Column(Modifier.graphicsLayer { alpha = anim.value; translationY = (1 - anim.value) * 60f }) {
        val sub = listOfNotNull(
            w?.let { "${it.emoji} ${it.temps()}" + if (it.typical) " typical" else "" },
            d.sleepingAt?.let { "🌙 $it" } ?: if (d.index < 1) "Travel day" else null,
        ).joinToString("  ·  ")
        DayHeader(if (d.index >= 1) "${d.index}" else "✈", d.date.format(longDayFmt), sub, p)
        if (d.events.isEmpty()) {
            EventRow(TimelineEvent(EventKind.ACTIVITY, null, null, "🌿", "Free day", "Wander, rest, get lost on purpose"), p, isLast = true, muted = true)
        } else d.events.forEachIndexed { i, e -> EventRow(e, p, isLast = i == d.events.lastIndex) }
        Spacer(Modifier.height(18.dp))
    }
}

@Composable
private fun DayHeader(number: String, title: String, subtitle: String, p: TripPalette) {
    Row(Modifier.padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(56.dp).clip(CircleShape).background(p.brush), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (number.all { it.isDigit() }) Text("DAY", fontSize = 9.sp, letterSpacing = 1.sp, color = Color.White)
                Text(number, fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 22.sp, color = Color.White, lineHeight = 22.sp)
            }
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, fontFamily = Display, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, color = Color.White)
            if (subtitle.isNotBlank()) Text(subtitle, fontSize = 12.sp, color = Color.White.copy(alpha = 0.7f))
        }
    }
}

@Composable
private fun EventRow(e: TimelineEvent, p: TripPalette, isLast: Boolean, muted: Boolean = false) {
    Row(Modifier.padding(start = 20.dp, end = 20.dp).height(IntrinsicSize.Min)) {
        // time column
        Column(Modifier.width(56.dp).padding(top = 14.dp), horizontalAlignment = Alignment.End) {
            Text(e.time?.format(timeFmt) ?: "", color = p.accent, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            e.endTime?.let { Text(it.format(timeFmt), color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp) }
        }
        // rail
        Box(Modifier.width(32.dp).fillMaxHeight()) {
            Canvas(Modifier.fillMaxSize()) {
                val x = size.width / 2
                drawLine(
                    Brush.verticalGradient(p.colors.map { it.copy(alpha = 0.6f) }), Offset(x, 0f), Offset(x, if (isLast) 30.dp.toPx() else size.height),
                    strokeWidth = 3f, pathEffect = if (muted) PathEffect.dashPathEffect(floatArrayOf(8f, 8f)) else null,
                )
                drawCircle(Night, 9.dp.toPx(), Offset(x, 22.dp.toPx()))
                drawCircle(p.accent, 6.dp.toPx(), Offset(x, 22.dp.toPx()))
            }
        }
        // card
        val isTransport = e.kind == EventKind.TRANSPORT
        Column(
            Modifier.weight(1f).padding(vertical = 6.dp).clip(RoundedCornerShape(18.dp))
                .background(if (isTransport) Brush.linearGradient(p.colors.map { it.copy(alpha = 0.85f) }) else Brush.linearGradient(listOf(Color.White.copy(alpha = 0.09f), Color.White.copy(alpha = 0.04f))))
                .border(1.dp, Color.White.copy(alpha = if (muted) 0.06f else 0.12f), RoundedCornerShape(18.dp))
                .padding(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(e.emoji, fontSize = 22.sp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(e.title, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = if (muted) 0.7f else 1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (e.subtitle.isNotBlank()) Text(e.subtitle, fontSize = 12.sp, color = Color.White.copy(alpha = 0.75f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (e.optional) Text("optional", fontSize = 10.sp, color = Color.White.copy(alpha = 0.7f))
            }
            if (e.detail.isNotBlank()) Text(e.detail, fontSize = 12.sp, color = Color.White.copy(alpha = 0.65f), modifier = Modifier.padding(top = 6.dp), maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}


private fun plural(n: Int, word: String) = "$n $word" + if (n == 1) "" else "s"
