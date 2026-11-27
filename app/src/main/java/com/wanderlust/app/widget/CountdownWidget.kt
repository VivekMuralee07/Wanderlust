package com.wanderlust.app.widget

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.wanderlust.app.MainActivity
import com.wanderlust.app.WanderlustApp
import com.wanderlust.app.data.TravelRepository
import com.wanderlust.app.data.TripStatus
import com.wanderlust.app.domain.dateRange
import com.wanderlust.app.ui.theme.palette
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Home-screen widget: days until the next trip (or "Day X of Y" during a trip). */
class CountdownWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repo = (context.applicationContext as WanderlustApp).container.repo
        val trips = repo.tripsOnce().filter { !it.isArchived }
        val today = LocalDate.now()
        val trip = trips.filter { TravelRepository.tripStatus(it) == TripStatus.ONGOING }.minByOrNull { it.startDate!! }
            ?: trips.filter { TravelRepository.tripStatus(it) == TripStatus.UPCOMING }.minByOrNull { it.startDate!! }

        provideContent {
            val bg = trip?.let { palette(it.themeIndex).colors.first() } ?: Color(0xFF7B61FF)
            val white = ColorProvider(Color.White)
            val soft = ColorProvider(Color.White.copy(alpha = 0.8f))
            Column(
                GlanceModifier.fillMaxSize().background(bg).cornerRadius(24.dp).padding(14.dp).clickable(actionStartActivity<MainActivity>()),
                verticalAlignment = Alignment.Vertical.CenterVertically,
                horizontalAlignment = Alignment.Horizontal.Start,
            ) {
                if (trip == null) {
                    Text("Wanderlust", style = TextStyle(color = soft, fontSize = 12.sp))
                    Text("Where to next? ✈️", style = TextStyle(color = white, fontSize = 18.sp, fontWeight = FontWeight.Bold))
                } else {
                    val start = trip.startDate!!
                    val ongoing = !today.isBefore(start)
                    Text("✈ " + trip.destination.ifBlank { trip.name }, maxLines = 1, style = TextStyle(color = white, fontSize = 14.sp, fontWeight = FontWeight.Bold))
                    Spacer(GlanceModifier.height(4.dp))
                    if (ongoing) {
                        val day = ChronoUnit.DAYS.between(start, today) + 1
                        val len = ChronoUnit.DAYS.between(start, trip.endDate ?: start) + 1
                        Text("Day $day", style = TextStyle(color = white, fontSize = 34.sp, fontWeight = FontWeight.Bold))
                        Text("of $len · enjoy!", style = TextStyle(color = soft, fontSize = 12.sp))
                    } else {
                        val days = ChronoUnit.DAYS.between(today, start)
                        Text("$days", style = TextStyle(color = white, fontSize = 40.sp, fontWeight = FontWeight.Bold))
                        Text(if (days == 1L) "day to go" else "days to go", style = TextStyle(color = soft, fontSize = 12.sp))
                    }
                    Spacer(GlanceModifier.height(4.dp))
                    Text(dateRange(trip.startDate, trip.endDate), maxLines = 1, style = TextStyle(color = soft, fontSize = 11.sp))
                }
            }
        }
    }

    companion object {
        suspend fun refresh(context: Context) { runCatching { CountdownWidget().updateAll(context) } }
    }
}

class CountdownWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = CountdownWidget()
}
