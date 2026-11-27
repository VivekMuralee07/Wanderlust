package com.wanderlust.app.ui.trips

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wanderlust.app.LocalApp
import com.wanderlust.app.data.Attachment
import com.wanderlust.app.data.Lodging
import com.wanderlust.app.data.LodgingType
import com.wanderlust.app.data.Transport
import com.wanderlust.app.data.TransportType
import com.wanderlust.app.domain.dayFmt
import com.wanderlust.app.domain.timeFmt
import com.wanderlust.app.ui.components.DashedDivider
import com.wanderlust.app.ui.components.SmallLabel
import com.wanderlust.app.ui.components.TicketShape
import com.wanderlust.app.ui.theme.Display
import com.wanderlust.app.ui.theme.TripPalette
import java.time.Duration

/** Boarding-pass styled card for any transport booking. */
@Composable
fun BoardingPass(t: Transport, palette: TripPalette, attachments: List<Attachment>, onClick: () -> Unit) {
    val type = TransportType.of(t.type)
    val duration = if (t.departure != null && t.arrival != null) Duration.between(t.departure, t.arrival).takeIf { !it.isNegative } else null
    Column(
        Modifier.fillMaxWidth().clip(TicketShape()).background(Brush.linearGradient(palette.colors.map { it.copy(alpha = 0.92f) }))
            .clickable(onClick = onClick)
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${type.emoji}  ${t.name.ifBlank { type.label }}".uppercase(), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 1.5.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (t.flightNumber.isNotBlank()) Text(t.flightNumber, color = Color.White, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Endpoint(t.fromCode, t.fromLocation, t.departure?.format(timeFmt), Modifier.weight(1f), TextAlign.Start)
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(90.dp)) {
                    Text(type.emoji, fontSize = 22.sp)
                    DashedDivider(Modifier.padding(vertical = 4.dp), Color.White.copy(alpha = 0.6f))
                    duration?.let { Text("${it.toHours()}h ${it.toMinutesPart()}m", color = Color.White.copy(alpha = 0.85f), fontSize = 11.sp) }
                }
                Endpoint(t.toCode, t.toLocation, t.arrival?.format(timeFmt), Modifier.weight(1f), TextAlign.End)
            }
            Spacer(Modifier.height(10.dp))
            Text(t.departure?.format(dayFmt) ?: "Date not set", color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
        }
        DashedDivider(Modifier.padding(horizontal = 20.dp), Color.White.copy(alpha = 0.55f))
        Row(Modifier.padding(horizontal = 18.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Info("Booking", t.bookingRef.ifBlank { "—" })
            Info("Seat", t.seat.ifBlank { "—" })
            Info("Gate", t.gate.ifBlank { "—" })
        }
        AttachmentRow(attachments)
    }
}

@Composable
private fun Endpoint(code: String, place: String, time: String?, modifier: Modifier, align: TextAlign) {
    Column(modifier, horizontalAlignment = if (align == TextAlign.Start) Alignment.Start else Alignment.End) {
        Text(code.ifBlank { place.take(3).uppercase().ifBlank { "···" } }, fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 34.sp, color = Color.White)
        if (place.isNotBlank()) Text(place, color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = align)
        if (time != null) Text(time, color = Color.White, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun Info(label: String, value: String) {
    Column {
        Text(label.uppercase(), fontSize = 9.sp, letterSpacing = 1.5.sp, color = Color.White.copy(alpha = 0.7f))
        Text(value, color = Color.White, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun AttachmentRow(attachments: List<Attachment>) {
    if (attachments.isEmpty()) return
    val context = LocalContext.current
    val app = LocalApp.current
    Row(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        attachments.take(3).forEach { a ->
            AssistChip(
                onClick = { app.files.open(context, a.filePath, a.mimeType) },
                label = { Text(a.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 11.sp) },
                leadingIcon = { Icon(if (a.mimeType.startsWith("image/")) Icons.Default.Image else Icons.Default.Description, null) },
                colors = AssistChipDefaults.assistChipColors(containerColor = Color.Black.copy(alpha = 0.25f), labelColor = Color.White, leadingIconContentColor = Color.White),
                border = null,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
    }
}

/** Hotel "key card" styled stay. */
@Composable
fun StayCard(l: Lodging, attachments: List<Attachment>, onClick: () -> Unit) {
    val type = LodgingType.of(l.type)
    val nights = if (l.checkIn != null && l.checkOut != null) Duration.between(l.checkIn.toLocalDate().atStartOfDay(), l.checkOut.toLocalDate().atStartOfDay()).toDays() else null
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF232C55), Color(0xFF2E2350))))
            .clickable(onClick = onClick)
    ) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(type.emoji, fontSize = 34.sp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(l.name, fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (l.address.isNotBlank()) Text(l.address, fontSize = 12.sp, color = Color.White.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                l.rating?.let { Text("★".repeat(it.toInt()), color = Color(0xFFFFC857), fontSize = 12.sp) }
            }
            nights?.let {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("$it", fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 26.sp, color = Color(0xFFFFC857))
                    Text(if (it == 1L) "night" else "nights", fontSize = 11.sp, color = Color.White.copy(alpha = 0.7f))
                }
            }
        }
        Row(Modifier.padding(horizontal = 18.dp).padding(bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column { SmallLabel("Check-in"); Text(l.checkIn?.let { "${it.format(dayFmt)} · ${it.format(timeFmt)}" } ?: "—", fontWeight = FontWeight.SemiBold) }
            Column { SmallLabel("Check-out"); Text(l.checkOut?.let { "${it.format(dayFmt)} · ${it.format(timeFmt)}" } ?: "—", fontWeight = FontWeight.SemiBold) }
        }
        if (l.reservationNumber.isNotBlank()) {
            Text("Reservation ${l.reservationNumber}", fontSize = 12.sp, color = Color.White.copy(alpha = 0.75f), modifier = Modifier.padding(start = 18.dp, bottom = 12.dp))
        }
        AttachmentRow(attachments)
    }
}
