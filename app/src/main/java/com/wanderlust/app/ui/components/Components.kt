package com.wanderlust.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.wanderlust.app.data.Trip
import com.wanderlust.app.domain.Countdowns
import com.wanderlust.app.ui.theme.Display
import com.wanderlust.app.ui.theme.palette
import kotlinx.coroutines.delay
import java.io.File
import java.time.LocalDateTime
import kotlin.math.abs

/** Trip cover: the user's photo, or a generated gradient "postcard" in the trip's palette. */
@Composable
fun TripCover(trip: Trip, modifier: Modifier = Modifier, scrim: Boolean = true, content: @Composable BoxScope.() -> Unit = {}) {
    val p = palette(trip.themeIndex)
    Box(modifier.background(p.brush)) {
        if (trip.coverPath != null) {
            AsyncImage(model = File(trip.coverPath), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            // decorative sun + hills, seeded by the trip name so every trip gets its own postcard
            val seed = abs(trip.name.hashCode())
            Canvas(Modifier.fillMaxSize()) {
                val w = size.width; val h = size.height
                drawCircle(Color.White.copy(alpha = 0.22f), radius = h * 0.32f, center = Offset(w * (0.55f + (seed % 30) / 100f), h * 0.42f))
                drawCircle(Color.White.copy(alpha = 0.10f), radius = h * 0.5f, center = Offset(w * 0.1f, h * 0.05f))
                val hills = Path().apply {
                    moveTo(0f, h * 0.78f)
                    cubicTo(w * 0.25f, h * 0.58f, w * 0.45f, h * 0.9f, w * 0.7f, h * 0.68f)
                    cubicTo(w * 0.85f, h * 0.56f, w * 0.95f, h * 0.7f, w, h * 0.66f)
                    lineTo(w, h); lineTo(0f, h); close()
                }
                drawPath(hills, Color.Black.copy(alpha = 0.18f))
                val hills2 = Path().apply {
                    moveTo(0f, h * 0.9f)
                    cubicTo(w * 0.3f, h * 0.75f, w * 0.6f, h * 0.98f, w, h * 0.82f)
                    lineTo(w, h); lineTo(0f, h); close()
                }
                drawPath(hills2, Color.Black.copy(alpha = 0.22f))
            }
        }
        if (scrim) Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.65f)))))
        content()
    }
}

@Composable
fun GlassCard(modifier: Modifier = Modifier, corner: Dp = 24.dp, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(corner))
            .background(Color.White.copy(alpha = 0.07f))
            .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(corner))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
    ) { content() }
}

/** Ticking countdown with big serif digits in glass tiles. */
@Composable
fun LiveCountdown(target: LocalDateTime, modifier: Modifier = Modifier, compact: Boolean = false) {
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(target) {
        while (true) { now = LocalDateTime.now(); delay(1000) }
    }
    val c = Countdowns.until(target, now)
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 10.dp)) {
        listOf(c.days to "DAYS", c.hours to "HRS", c.minutes to "MIN", c.seconds to "SEC").forEach { (v, l) ->
            Column(
                Modifier
                    .clip(RoundedCornerShape(if (compact) 12.dp else 18.dp))
                    .background(Color.Black.copy(alpha = 0.28f))
                    .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(if (compact) 12.dp else 18.dp))
                    .padding(horizontal = if (compact) 10.dp else 14.dp, vertical = if (compact) 6.dp else 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    if (l == "DAYS") v.toString() else v.toString().padStart(2, '0'),
                    fontFamily = Display, fontWeight = FontWeight.Bold,
                    fontSize = if (compact) 20.sp else 32.sp, color = Color.White,
                )
                Text(l, fontSize = if (compact) 9.sp else 10.sp, letterSpacing = 2.sp, color = Color.White.copy(alpha = 0.75f))
            }
        }
    }
}

@Composable
fun SectionTitle(title: String, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
        if (action != null && onAction != null) {
            Text(action, color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onAction).padding(6.dp))
        }
    }
}

@Composable
fun EmptyState(emoji: String, title: String, body: String, modifier: Modifier = Modifier, action: @Composable (() -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(88.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.06f)), contentAlignment = Alignment.Center) {
            Text(emoji, fontSize = 40.sp)
        }
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (action != null) { Spacer(Modifier.height(16.dp)); action() }
    }
}

@Composable
fun Pill(text: String, modifier: Modifier = Modifier, color: Color = Color.White.copy(alpha = 0.16f), textColor: Color = Color.White) {
    Text(
        text, color = textColor, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
        modifier = modifier.clip(RoundedCornerShape(50)).background(color).padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
fun StatTile(value: String, label: String, modifier: Modifier = Modifier, accent: Color = MaterialTheme.colorScheme.secondary) {
    GlassCard(modifier, corner = 20.dp) {
        Column(Modifier.padding(14.dp)) {
            Text(value, fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 26.sp, color = accent)
            Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A ticket with semicircle notches cut out at [notchFraction] of its height (boarding-pass look). */
class TicketShape(private val notchRadius: Dp = 12.dp, private val corner: Dp = 20.dp, private val notchFraction: Float = 0.62f) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val r = with(density) { notchRadius.toPx() }
        val c = with(density) { corner.toPx() }
        val base = Path().apply {
            addRoundRect(androidx.compose.ui.geometry.RoundRect(Rect(Offset.Zero, size), androidx.compose.ui.geometry.CornerRadius(c, c)))
        }
        val y = size.height * notchFraction
        val notches = Path().apply {
            addOval(Rect(Offset(0f, y), r))
            addOval(Rect(Offset(size.width, y), r))
        }
        return Outline.Generic(Path.combine(PathOperation.Difference, base, notches))
    }
}

@Composable
fun DashedDivider(modifier: Modifier = Modifier, color: Color = Color.White.copy(alpha = 0.3f)) {
    Canvas(modifier.fillMaxWidth().height(1.dp)) {
        var x = 0f
        while (x < size.width) {
            drawLine(color, Offset(x, 0f), Offset((x + 10f).coerceAtMost(size.width), 0f), strokeWidth = 2f)
            x += 20f
        }
    }
}

@Composable
fun Bullet(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(10.dp).clip(CircleShape).background(color))
}

@Composable
fun SmallLabel(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), modifier = modifier, fontSize = 10.sp, letterSpacing = 1.5.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
}

@Suppress("unused")
@Composable
fun HSpace(w: Dp) = Spacer(Modifier.width(w))
