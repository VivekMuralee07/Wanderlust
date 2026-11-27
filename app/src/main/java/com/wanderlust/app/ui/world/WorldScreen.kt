package com.wanderlust.app.ui.world

import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.OvalShape
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavHostController
import com.wanderlust.app.LocalApp
import com.wanderlust.app.data.LodgingType
import com.wanderlust.app.data.Place
import com.wanderlust.app.data.PlaceCategory
import com.wanderlust.app.data.TransportType
import com.wanderlust.app.data.flagEmoji
import com.wanderlust.app.domain.tripLengthDays
import com.wanderlust.app.ui.components.EmptyState
import com.wanderlust.app.ui.components.GlassCard
import com.wanderlust.app.ui.components.SmallLabel
import com.wanderlust.app.ui.components.StatTile
import com.wanderlust.app.ui.theme.Coral
import com.wanderlust.app.ui.theme.Display
import com.wanderlust.app.ui.theme.Gold
import com.wanderlust.app.ui.theme.Night
import com.wanderlust.app.ui.theme.Sky
import com.wanderlust.app.ui.theme.Teal
import com.wanderlust.app.ui.theme.Violet
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import java.time.temporal.ChronoUnit

/** World view (AdventureLog's world travel + stats): map of every place, countries visited and totals. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WorldScreen(@Suppress("UNUSED_PARAMETER") nav: NavHostController) {
    val app = LocalApp.current
    val trips by app.repo.trips.collectAsState(initial = emptyList())
    val places by app.repo.allPlaces.collectAsState(initial = emptyList())
    val transports by app.repo.allTransports.collectAsState(initial = emptyList())
    val lodgings by app.repo.allLodgings.collectAsState(initial = emptyList())

    val countries = places.filter { it.countryCode.isNotBlank() }.groupBy { it.countryCode }
    val visitedCountries = countries.filter { (_, ps) -> ps.any { it.visited } }
    val days = trips.sumOf { tripLengthDays(it.startDate, it.endDate) }
    val nights = lodgings.sumOf { l ->
        val a = l.checkIn?.toLocalDate(); val b = l.checkOut?.toLocalDate()
        if (a != null && b != null) ChronoUnit.DAYS.between(a, b).coerceAtLeast(0) else 0L
    }
    val byType = transports.groupingBy { TransportType.of(it.type) }.eachCount()

    LazyColumn(Modifier.fillMaxSize().background(Night), contentPadding = PaddingValues(bottom = 32.dp)) {
        item {
            Column(Modifier.statusBarsPadding().padding(20.dp)) {
                Text("Your world", style = MaterialTheme.typography.displaySmall)
                Text("Search anywhere, save it to a trip, and see every place on one map.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            MapExplorer(places, trips, fixedTripId = null, centerOn = null, modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(), mapHeight = 340)
        }
        item {
            Spacer(Modifier.height(16.dp))
            Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile("${visitedCountries.size}", "Countries", Modifier.weight(1f), Teal)
                StatTile("${places.count { it.visited }}", "Places visited", Modifier.weight(1f), Coral)
                StatTile("$days", "Travel days", Modifier.weight(1f), Gold)
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile("${byType[TransportType.PLANE] ?: 0}", "Flights", Modifier.weight(1f), Sky)
                StatTile("${(byType - TransportType.PLANE).values.sum()}", "Other rides", Modifier.weight(1f), Violet)
                StatTile("$nights", "Hotel nights", Modifier.weight(1f), Gold)
            }
        }
        if (countries.isNotEmpty()) item {
            SmallLabel("Countries", Modifier.padding(start = 20.dp, top = 24.dp, bottom = 8.dp))
            FlowRow(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                countries.forEach { (code, ps) ->
                    val visited = ps.any { it.visited }
                    GlassCard {
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                            Text(flagEmoji(code), fontSize = 20.sp)
                            Column(Modifier.padding(start = 8.dp)) {
                                Text(ps.first().countryName.ifBlank { code }, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                Text(if (visited) "${ps.count { it.visited }} visited" else "planned", fontSize = 11.sp, color = if (visited) Teal else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
        if (places.isNotEmpty()) item {
            SmallLabel("Favourite kinds of places", Modifier.padding(start = 20.dp, top = 24.dp, bottom = 8.dp))
            places.groupingBy { PlaceCategory.of(it.category) }.eachCount().entries.sortedByDescending { it.value }.take(6).forEach { (c, n) ->
                Row(Modifier.padding(horizontal = 20.dp, vertical = 3.dp)) {
                    Text("${c.emoji}  ${c.label}", Modifier.weight(1f))
                    Text("$n", fontFamily = Display, fontWeight = FontWeight.Bold, color = Gold)
                }
            }
        }
        if (lodgings.isNotEmpty()) item {
            SmallLabel("Where you slept", Modifier.padding(start = 20.dp, top = 24.dp, bottom = 8.dp))
            lodgings.groupingBy { LodgingType.of(it.type) }.eachCount().forEach { (t, n) ->
                Row(Modifier.padding(horizontal = 20.dp, vertical = 3.dp)) {
                    Text("${t.emoji}  ${t.label}", Modifier.weight(1f)); Text("$n", fontFamily = Display, fontWeight = FontWeight.Bold, color = Teal)
                }
            }
        }
    }
}
