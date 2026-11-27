package com.wanderlust.app.ui.editors

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.wanderlust.app.LocalApp
import com.wanderlust.app.data.Trip
import com.wanderlust.app.ui.Routes
import com.wanderlust.app.ui.components.DateField
import com.wanderlust.app.ui.components.SmallLabel
import com.wanderlust.app.ui.components.TripCover
import com.wanderlust.app.ui.theme.Display
import com.wanderlust.app.ui.theme.TripPalettes
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun TripEditor(nav: NavHostController, tripId: Long) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    var trip by remember { mutableStateOf(Trip(name = "", themeIndex = (0 until TripPalettes.size).random())) }
    var loaded by remember { mutableStateOf(tripId == 0L) }
    LaunchedEffect(tripId) {
        if (tripId != 0L) app.repo.trip(tripId)?.let { trip = it }
        loaded = true
    }
    val pickCover = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            app.files.import(uri, "covers")?.let { stored ->
                trip.coverPath?.let { File(it).delete() }
                trip = trip.copy(coverPath = stored.path)
            }
        }
    }
    if (!loaded) return

    EditorScaffold(
        title = if (tripId == 0L) "New trip" else "Edit trip",
        onBack = { nav.popBackStack() },
        saveEnabled = trip.name.isNotBlank() || trip.destination.isNotBlank(),
        onSave = {
            app.appScope.launch {
                val t = trip.copy(name = trip.name.ifBlank { trip.destination }, endDate = trip.endDate?.takeIf { e -> trip.startDate == null || !e.isBefore(trip.startDate) } ?: trip.startDate)
                val id = app.repo.saveTrip(t)
                if (tripId == 0L) app.repo.seedPackingList(id)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    if (tripId == 0L) nav.navigate(Routes.trip(id)) { popUpTo(Routes.tripEdit(0)) { inclusive = true } }
                    else nav.popBackStack()
                }
            }
        },
        onDelete = if (tripId != 0L) ({
            app.appScope.launch {
                app.repo.deleteTrip(trip)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { nav.popBackStack(Routes.HOME, false) }
            }
        }) else null,
    ) {
        // live preview of the cover
        TripCover(trip, Modifier.fillMaxWidth().height(170.dp).clip(RoundedCornerShape(24.dp)).clickable {
            pickCover.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }) {
            Text(trip.destination.ifBlank { trip.name.ifBlank { "Your next adventure" } }, fontFamily = Display, fontWeight = FontWeight.Bold,
                fontSize = 28.sp, color = Color.White, modifier = Modifier.align(Alignment.BottomStart).padding(16.dp))
            Text(if (trip.coverPath == null) "Tap to add a cover photo" else "Tap to change photo", fontSize = 11.sp,
                color = Color.White.copy(alpha = 0.8f), modifier = Modifier.align(Alignment.TopEnd).padding(12.dp))
        }
        if (trip.coverPath != null) {
            OutlinedButton(onClick = { trip.coverPath?.let { File(it).delete() }; trip = trip.copy(coverPath = null) }) { Text("Use gradient instead") }
        }
        Field("Trip name", trip.name, { trip = trip.copy(name = it) })
        Field("Destination (city, country)", trip.destination, { trip = trip.copy(destination = it) })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DateField("Start", trip.startDate, { trip = trip.copy(startDate = it) }, Modifier.weight(1f))
            DateField("End", trip.endDate, { trip = trip.copy(endDate = it) }, Modifier.weight(1f))
        }
        Field("Description", trip.description, { trip = trip.copy(description = it) }, singleLine = false, minLines = 3)
        SmallLabel("Theme")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            itemsIndexed(TripPalettes) { i, p ->
                Box(
                    Modifier.size(48.dp).clip(CircleShape)
                        .then(Modifier.border(if (i == trip.themeIndex) 3.dp else 0.dp, if (i == trip.themeIndex) Color.White else Color.Transparent, CircleShape))
                        .clickable { trip = trip.copy(themeIndex = i) }
                ) { Box(Modifier.size(48.dp).clip(CircleShape).then(Modifier.padding(0.dp))) { androidx.compose.foundation.Canvas(Modifier.size(48.dp)) { drawCircle(p.brush) } } }
            }
        }
        Text(TripPalettes[trip.themeIndex].name, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
    }
}
