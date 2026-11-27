package com.wanderlust.app.ui.home

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import androidx.navigation.NavHostController
import coil3.compose.AsyncImage
import com.wanderlust.app.LocalApp
import com.wanderlust.app.data.Memory
import com.wanderlust.app.data.Transport
import com.wanderlust.app.data.TransportType
import com.wanderlust.app.data.TravelRepository
import com.wanderlust.app.data.Trip
import com.wanderlust.app.data.TripStatus
import com.wanderlust.app.domain.Countdowns
import com.wanderlust.app.domain.dateRange
import com.wanderlust.app.ui.Routes
import com.wanderlust.app.ui.components.GlassCard
import com.wanderlust.app.ui.components.LiveCountdown
import com.wanderlust.app.ui.components.Pill
import com.wanderlust.app.ui.components.SectionTitle
import com.wanderlust.app.ui.components.StatTile
import com.wanderlust.app.ui.components.TripCover
import com.wanderlust.app.ui.switchTab
import com.wanderlust.app.ui.theme.Coral
import com.wanderlust.app.ui.theme.Display
import com.wanderlust.app.ui.theme.Gold
import com.wanderlust.app.ui.theme.Night
import com.wanderlust.app.ui.theme.Sky
import com.wanderlust.app.ui.theme.Teal
import com.wanderlust.app.ui.theme.Violet
import java.io.File
import java.time.LocalTime
import kotlin.math.absoluteValue
import kotlin.math.atan2
import kotlin.math.sin
import kotlin.random.Random

@Composable
fun HomeScreen(nav: NavHostController) {
    val app = LocalApp.current
    val trips by app.repo.trips.collectAsState(initial = emptyList())
    val transports by app.repo.allTransports.collectAsState(initial = emptyList())
    val places by app.repo.allPlaces.collectAsState(initial = emptyList())
    val memories by remember { app.repo.recentMemories(16) }.collectAsState(initial = emptyList())
    val settings by app.settings.state.collectAsState()

    val active = trips.filter { !it.isArchived }
    val ongoing = active.filter { TravelRepository.tripStatus(it) == TripStatus.ONGOING }
    val upcoming = active.filter { TravelRepository.tripStatus(it) == TripStatus.UPCOMING }.sortedBy { it.startDate }
    val past = trips.filter { TravelRepository.tripStatus(it) == TripStatus.PAST }.sortedByDescending { it.startDate }
    val hero = ongoing.firstOrNull() ?: upcoming.firstOrNull()
    val manualCountries by app.repo.visitedCountries.collectAsState(initial = emptyList())
    val countries = (places.filter { it.visited && it.countryCode.isNotBlank() }.map { it.countryCode } + manualCountries.map { it.countryCode }).distinct().size
    val flights = transports.count { TransportType.of(it.type) == TransportType.PLANE }

    Box(Modifier.fillMaxSize().background(Night)) {
        AuroraBackground()
        LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
            item {
                Header(settings.travelerName, onSettings = { nav.navigate(Routes.SETTINGS) })
            }
            item {
                com.wanderlust.app.update.UpdateBanner(Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            }
            item {
                FlightPath(Modifier.fillMaxWidth().height(70.dp).padding(horizontal = 20.dp))
            }
            item {
                if (hero != null) HeroTrip(hero, transports) { nav.navigate(Routes.trip(hero.id)) }
                else EmptyHero { nav.navigate(Routes.tripEdit(0)) }
            }
            item {
                QuickActions(
                    onNew = { nav.navigate(Routes.tripEdit(0)) },
                    onScan = { nav.navigate(Routes.scan()) },
                    onAsk = { nav.switchTab(Routes.ASSISTANT) },
                    onWorld = { nav.switchTab(Routes.WORLD) },
                )
            }
            val more = (ongoing + upcoming).filter { it.id != hero?.id }
            if (more.isNotEmpty()) {
                item { SectionTitle("Coming up", action = "All trips") { nav.switchTab(Routes.TRIPS) } }
                item { TripCarousel(more) { nav.navigate(Routes.trip(it.id)) } }
            }
            item {
                SectionTitle("Your travel story")
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile("${trips.size}", "Trips", Modifier.weight(1f), Gold)
                    StatTile("$countries", "Countries", Modifier.weight(1f), Teal)
                    StatTile("${places.count { it.visited }}", "Places", Modifier.weight(1f), Coral)
                    StatTile("$flights", "Flights", Modifier.weight(1f), Sky)
                }
            }
            if (memories.isNotEmpty()) {
                item { SectionTitle("Memories") }
                item { MemoriesStrip(memories) { nav.navigate(Routes.trip(it.tripId)) } }
            }
            if (past.isNotEmpty()) {
                item { SectionTitle("Relive") }
                items(past.take(5), key = { it.id }) { t -> PastTripRow(t) { nav.navigate(Routes.trip(t.id)) } }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Background: slow-moving aurora blobs + twinkling stars
// ---------------------------------------------------------------------------------------------

@Composable
private fun AuroraBackground() {
    val t = rememberInfiniteTransition(label = "aurora")
    val phase by t.animateFloat(0f, (2 * Math.PI).toFloat(), infiniteRepeatable(tween(18_000, easing = LinearEasing)), label = "phase")
    val twinkle by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2_400), RepeatMode.Reverse), label = "twinkle")
    val stars = remember { List(70) { Triple(Random.nextFloat(), Random.nextFloat(), Random.nextFloat()) } }
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width; val h = size.height
        fun blob(color: Color, cx: Float, cy: Float, r: Float) =
            drawCircle(Brush.radialGradient(listOf(color, Color.Transparent), center = Offset(cx, cy), radius = r), r, Offset(cx, cy))
        blob(Violet.copy(alpha = 0.45f), w * (0.2f + 0.12f * sin(phase)), h * 0.12f, w * 0.9f)
        blob(Coral.copy(alpha = 0.30f), w * (0.9f + 0.1f * sin(phase + 2f)), h * (0.28f + 0.04f * sin(phase)), w * 0.75f)
        blob(Teal.copy(alpha = 0.22f), w * (0.4f + 0.15f * sin(phase + 4f)), h * 0.55f, w * 0.8f)
        stars.forEach { (x, y, s) ->
            val a = 0.25f + 0.6f * ((sin((twinkle + s) * Math.PI.toFloat() * 2) + 1) / 2)
            drawCircle(Color.White.copy(alpha = a * 0.8f), radius = 0.8f + s * 1.8f, center = Offset(x * w, y * h * 0.6f))
        }
    }
}

@Composable
private fun Header(name: String, onSettings: () -> Unit) {
    val hour = LocalTime.now().hour
    val greeting = when (hour) { in 5..11 -> "Good morning"; in 12..16 -> "Good afternoon"; in 17..21 -> "Good evening"; else -> "Hello, night owl" }
    Column(Modifier.statusBarsPadding().padding(start = 24.dp, end = 12.dp, top = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                (greeting + if (name.isNotBlank()) ", $name" else "").uppercase(),
                fontSize = 12.sp, letterSpacing = 2.sp, color = Color.White.copy(alpha = 0.7f), modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, contentDescription = "Settings", tint = Color.White.copy(alpha = 0.8f)) }
        }
        Text(
            "Where to\nnext?",
            style = TextStyle(
                fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 52.sp, lineHeight = 54.sp,
                brush = Brush.linearGradient(listOf(Color.White, Gold, Coral)),
            ),
        )
    }
}

/** A dashed great-circle style route with a plane flying along it, looping forever. */
@Composable
private fun FlightPath(modifier: Modifier) {
    val t = rememberInfiniteTransition(label = "plane")
    val progress by t.animateFloat(0f, 1f, infiniteRepeatable(tween(7_000, easing = LinearEasing)), label = "progress")
    val measurer = rememberTextMeasurer()
    Canvas(modifier) {
        val path = Path().apply {
            moveTo(0f, size.height * 0.85f)
            cubicTo(size.width * 0.3f, -size.height * 0.2f, size.width * 0.7f, -size.height * 0.2f, size.width, size.height * 0.7f)
        }
        drawPath(path, Color.White.copy(alpha = 0.35f), style = Stroke(width = 3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 14f))))
        drawCircle(Gold, 6f, Offset(0f, size.height * 0.85f))
        drawCircle(Coral, 6f, Offset(size.width, size.height * 0.7f))
        val pm = PathMeasure().apply { setPath(path, false) }
        val d = pm.length * progress
        val pos = pm.getPosition(d)
        val tan = pm.getTangent(d)
        val angle = Math.toDegrees(atan2(tan.y, tan.x).toDouble()).toFloat()
        val layout = measurer.measure("✈", TextStyle(fontSize = 22.sp, color = Color.White))
        rotate(angle, pivot = pos) {
            drawText(layout, topLeft = Offset(pos.x - layout.size.width / 2f, pos.y - layout.size.height / 2f))
        }
    }
}

@Composable
private fun HeroTrip(trip: Trip, transports: List<Transport>, onClick: () -> Unit) {
    val status = TravelRepository.tripStatus(trip)
    val target = Countdowns.target(trip, transports)
    TripCover(
        trip,
        Modifier.padding(horizontal = 20.dp).fillMaxWidth().height(360.dp).clip(RoundedCornerShape(32.dp)).clickable(onClick = onClick),
    ) {
        Column(Modifier.fillMaxSize().padding(22.dp)) {
            Row {
                Pill(if (status == TripStatus.ONGOING) "● HAPPENING NOW" else "NEXT ADVENTURE",
                    color = if (status == TripStatus.ONGOING) Coral else Color.White.copy(alpha = 0.2f))
                Spacer(Modifier.weight(1f))
                Pill(Countdowns.label(trip))
            }
            Spacer(Modifier.weight(1f))
            Text(trip.destination.ifBlank { trip.name }, fontFamily = Display, fontWeight = FontWeight.Bold,
                fontSize = 40.sp, lineHeight = 44.sp, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (trip.destination.isNotBlank() && trip.destination != trip.name) {
                Text(trip.name, color = Color.White.copy(alpha = 0.85f), fontSize = 16.sp)
            }
            Text(dateRange(trip.startDate, trip.endDate), color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
            Spacer(Modifier.height(16.dp))
            if (status == TripStatus.UPCOMING && target != null) {
                LiveCountdown(target)
            } else if (status == TripStatus.ONGOING) {
                Text("Enjoy every moment ✨  Tap to see today’s plan", color = Color.White, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun EmptyHero(onCreate: () -> Unit) {
    Box(
        Modifier
            .padding(horizontal = 20.dp).fillMaxWidth().height(300.dp)
            .clip(RoundedCornerShape(32.dp))
            .background(Brush.linearGradient(listOf(Violet, Coral, Gold)))
            .clickable(onClick = onCreate)
    ) {
        Text("🌍", fontSize = 160.sp, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).graphicsLayer { alpha = 0.35f })
        Column(Modifier.align(Alignment.BottomStart).padding(24.dp)) {
            Text("Plan your first\nadventure", fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 38.sp, color = Color.White)
            Spacer(Modifier.height(8.dp))
            Text("Add flights, hotels and places — we’ll build a gorgeous itinerary and count down the days.", color = Color.White.copy(alpha = 0.9f))
            Spacer(Modifier.height(14.dp))
            Pill("＋  Create a trip", color = Color.White, textColor = Night)
        }
    }
}

@Composable
private fun QuickActions(onNew: () -> Unit, onScan: () -> Unit, onAsk: () -> Unit, onWorld: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 20.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        QuickAction(Icons.Default.Add, "New trip", listOf(Coral, Gold), onNew)
        QuickAction(Icons.Default.DocumentScanner, "AI import", listOf(Teal, Sky), onScan)
        QuickAction(Icons.Default.AutoAwesome, "Ask AI", listOf(Violet, Coral), onAsk)
        QuickAction(Icons.Default.Public, "My world", listOf(Color(0xFF134E5E), Color(0xFF71B280)), onWorld)
    }
}

@Composable
private fun QuickAction(icon: ImageVector, label: String, colors: List<Color>, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(76.dp).clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(4.dp)) {
        Box(Modifier.size(60.dp).clip(CircleShape).background(Brush.linearGradient(colors)), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = Color.White)
        }
        Spacer(Modifier.height(6.dp))
        Text(label, fontSize = 12.sp, color = Color.White.copy(alpha = 0.85f), maxLines = 1)
    }
}

@Composable
private fun TripCarousel(trips: List<Trip>, onClick: (Trip) -> Unit) {
    val pager = rememberPagerState { trips.size }
    HorizontalPager(pager, contentPadding = PaddingValues(horizontal = 40.dp), pageSpacing = 12.dp) { page ->
        val trip = trips[page]
        val offset = ((pager.currentPage - page) + pager.currentPageOffsetFraction).absoluteValue.coerceIn(0f, 1f)
        TripCover(
            trip,
            Modifier
                .graphicsLayer {
                    val scale = lerp(1f, 0.88f, offset)
                    scaleX = scale; scaleY = scale; alpha = lerp(1f, 0.6f, offset)
                }
                .fillMaxWidth().height(200.dp).clip(RoundedCornerShape(26.dp)).clickable { onClick(trip) },
        ) {
            Column(Modifier.align(Alignment.BottomStart).padding(18.dp)) {
                Pill(Countdowns.label(trip))
                Spacer(Modifier.height(8.dp))
                Text(trip.destination.ifBlank { trip.name }, fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 26.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(dateRange(trip.startDate, trip.endDate), color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun MemoriesStrip(memories: List<Memory>, onClick: (Memory) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        items(memories, key = { it.id }) { m ->
            val tilt = remember(m.id) { (m.id % 7 - 3).toFloat() }
            Box(
                Modifier.rotate(tilt).clip(RoundedCornerShape(6.dp)).background(Color.White).padding(start = 6.dp, end = 6.dp, top = 6.dp, bottom = 22.dp)
                    .clickable { onClick(m) }
            ) {
                AsyncImage(File(m.filePath), contentDescription = m.caption, contentScale = ContentScale.Crop, modifier = Modifier.size(width = 110.dp, height = 120.dp))
            }
        }
    }
}

@Composable
private fun PastTripRow(trip: Trip, onClick: () -> Unit) {
    GlassCard(Modifier.padding(horizontal = 20.dp, vertical = 6.dp).fillMaxWidth(), onClick = onClick) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            TripCover(trip, Modifier.size(64.dp).clip(RoundedCornerShape(16.dp)), scrim = false)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(trip.destination.ifBlank { trip.name }, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(dateRange(trip.startDate, trip.endDate), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            }
            Text(Countdowns.label(trip), color = MaterialTheme.colorScheme.secondary, fontSize = 12.sp, modifier = Modifier.border(1.dp, Color.Transparent, CircleShape))
        }
    }
}
