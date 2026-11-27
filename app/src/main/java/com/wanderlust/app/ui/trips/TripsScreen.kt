package com.wanderlust.app.ui.trips

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.wanderlust.app.LocalApp
import com.wanderlust.app.data.TravelRepository
import com.wanderlust.app.data.TripStatus
import com.wanderlust.app.domain.Countdowns
import com.wanderlust.app.domain.dateRange
import com.wanderlust.app.ui.Routes
import com.wanderlust.app.ui.components.EmptyState
import com.wanderlust.app.ui.components.Pill
import com.wanderlust.app.ui.components.TripCover
import com.wanderlust.app.ui.theme.Coral
import com.wanderlust.app.ui.theme.Display
import com.wanderlust.app.ui.theme.Night

private enum class Filter(val label: String) { ALL("All"), UPCOMING("Upcoming"), PLANNING("Planning"), PAST("Past"), ARCHIVED("Archived") }

@Composable
fun TripsScreen(nav: NavHostController) {
    val app = LocalApp.current
    val trips by app.repo.trips.collectAsState(initial = emptyList())
    var filter by rememberSaveable { mutableStateOf(Filter.ALL) }
    val shown = trips.filter { t ->
        val s = TravelRepository.tripStatus(t)
        when (filter) {
            Filter.ALL -> !t.isArchived
            Filter.UPCOMING -> !t.isArchived && (s == TripStatus.UPCOMING || s == TripStatus.ONGOING)
            Filter.PLANNING -> !t.isArchived && s == TripStatus.PLANNING
            Filter.PAST -> !t.isArchived && s == TripStatus.PAST
            Filter.ARCHIVED -> t.isArchived
        }
    }

    Scaffold(
        containerColor = Night,
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { nav.navigate(Routes.tripEdit(0)) }, icon = { Icon(Icons.Default.Add, null) }, text = { Text("New trip") })
        },
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(bottom = 96.dp)) {
            item {
                Column(Modifier.statusBarsPadding().padding(start = 20.dp, end = 20.dp, top = 16.dp)) {
                    Text("Your trips", style = MaterialTheme.typography.displaySmall)
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Filter.entries.forEach { f -> FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f.label) }) }
                    }
                }
            }
            if (shown.isEmpty()) {
                item { EmptyState("🧳", "No trips here yet", "Create a trip to start tracking flights, hotels and plans.") }
            }
            items(shown, key = { it.id }) { trip ->
                val status = TravelRepository.tripStatus(trip)
                TripCover(
                    trip,
                    Modifier.padding(horizontal = 20.dp, vertical = 8.dp).fillMaxWidth().height(180.dp)
                        .clip(RoundedCornerShape(26.dp)).clickable { nav.navigate(Routes.trip(trip.id)) },
                ) {
                    Row(Modifier.align(Alignment.TopStart).padding(14.dp)) {
                        Pill(Countdowns.label(trip), color = if (status == TripStatus.ONGOING) Coral else Color.White.copy(alpha = 0.2f))
                    }
                    Column(Modifier.align(Alignment.BottomStart).padding(18.dp)) {
                        Text(trip.destination.ifBlank { trip.name }, fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 28.sp,
                            color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOf(trip.name.takeIf { it != trip.destination && trip.destination.isNotBlank() }, dateRange(trip.startDate, trip.endDate))
                                .filterNotNull().joinToString(" · "),
                            color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp,
                        )
                    }
                }
            }
        }
    }
}
