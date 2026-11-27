package com.wanderlust.app.ui.world

import android.annotation.SuppressLint
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.OvalShape
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.wanderlust.app.LocalApp
import com.wanderlust.app.data.Geocoder
import com.wanderlust.app.data.Place
import com.wanderlust.app.data.PlaceCategory
import com.wanderlust.app.data.TravelRepository
import com.wanderlust.app.data.Trip
import com.wanderlust.app.data.TripStatus
import com.wanderlust.app.data.flagEmoji
import com.wanderlust.app.ui.components.GlassCard
import com.wanderlust.app.ui.theme.Coral
import com.wanderlust.app.ui.theme.Gold
import com.wanderlust.app.ui.theme.Teal
import kotlinx.coroutines.launch
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

private val quickCategories = listOf(PlaceCategory.ATTRACTION, PlaceCategory.DINING, PlaceCategory.CULTURE, PlaceCategory.MUSEUM,
    PlaceCategory.OUTDOOR, PlaceCategory.SHOPPING, PlaceCategory.NIGHTLIFE, PlaceCategory.GENERAL)

/**
 * Interactive OpenStreetMap with a search bar: search any place, preview it on the map, and save it
 * to a trip. Saved places show as pins (teal = visited, coral = planned).
 *
 * @param fixedTripId save into this trip (trip map); null = let the user pick a trip (world map)
 * @param centerOn place name to centre on when there are no pins yet (e.g. the trip destination)
 */
@Composable
fun MapExplorer(places: List<Place>, trips: List<Trip>, fixedTripId: Long?, centerOn: String?, modifier: Modifier = Modifier, mapHeight: Int = 380) {
    val app = LocalApp.current
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Geocoder.Result>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<Geocoder.Result?>(null) }
    var category by remember { mutableStateOf(PlaceCategory.ATTRACTION) }
    val activeTrips = trips.filter { !it.isArchived }
    var tripId by remember(fixedTripId, activeTrips.size) {
        mutableStateOf(fixedTripId ?: (activeTrips.firstOrNull { TravelRepository.tripStatus(it) == TripStatus.ONGOING }
            ?: activeTrips.firstOrNull { TravelRepository.tripStatus(it) == TripStatus.UPCOMING } ?: activeTrips.firstOrNull())?.id ?: 0L)
    }
    var tripMenu by remember { mutableStateOf(false) }

    // bias searches towards the trip's destination when there is one
    fun search() {
        if (query.isBlank()) return
        focus.clearFocus()
        searching = true; message = null; selected = null
        scope.launch {
            val q = query.trim()
            results = runCatching {
                val r = Geocoder.search(q)
                if (r.isEmpty() && !centerOn.isNullOrBlank()) Geocoder.search("$q, $centerOn") else r
            }.getOrElse { message = "Search failed — check your connection"; emptyList() }
            if (results.isEmpty() && message == null) message = "No places found for “$q”"
            searching = false
        }
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = query, onValueChange = { query = it },
            placeholder = { Text(if (!centerOn.isNullOrBlank()) "Search places in ${centerOn.substringBefore(',')}…" else "Search any place on the map…") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = {
                if (searching) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else if (query.isNotEmpty()) IconButton(onClick = { query = ""; results = emptyList(); selected = null; message = null }) { Icon(Icons.Default.Close, "Clear") }
            },
            singleLine = true, shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { search() }),
        )
        message?.let { Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (results.isNotEmpty() && selected == null) {
            GlassCard(Modifier.fillMaxWidth(), corner = 18.dp) {
                Column(Modifier.padding(6.dp)) {
                    results.forEach { r ->
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { selected = r }.padding(10.dp)) {
                            Text("${flagEmoji(r.countryCode)}  ${r.name}", fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(r.displayName, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }

        OsmMap(places, selected, centerOn, Modifier.fillMaxWidth().height(mapHeight.dp).clip(RoundedCornerShape(24.dp)))

        selected?.let { r ->
            GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${flagEmoji(r.countryCode)}  ${r.name}", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    Text(r.displayName, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        quickCategories.forEach { c -> FilterChip(selected = category == c, onClick = { category = c }, label = { Text("${c.emoji} ${c.label}", fontSize = 12.sp) }) }
                    }
                    if (fixedTripId == null) {
                        Box {
                            OutlinedButton(onClick = { tripMenu = true }, enabled = activeTrips.isNotEmpty()) {
                                Text(activeTrips.firstOrNull { it.id == tripId }?.let { "Trip: ${it.destination.ifBlank { it.name }}" } ?: "Create a trip first", maxLines = 1)
                            }
                            DropdownMenu(expanded = tripMenu, onDismissRequest = { tripMenu = false }) {
                                activeTrips.forEach { t -> DropdownMenuItem(text = { Text(t.destination.ifBlank { t.name }) }, onClick = { tripId = t.id; tripMenu = false }) }
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(enabled = tripId != 0L, onClick = {
                            val place = Place(
                                tripId = tripId, name = r.name, category = category.name, address = r.displayName,
                                latitude = r.lat, longitude = r.lon, city = r.city, countryName = r.country, countryCode = r.countryCode,
                            )
                            app.appScope.launch { app.repo.savePlace(place) }
                            Toast.makeText(context, "Saved ${r.name} 📍", Toast.LENGTH_SHORT).show()
                            selected = null; results = emptyList(); query = ""
                        }) { Text("＋ Add to trip") }
                        OutlinedButton(onClick = { selected = null }) { Text("Back to results") }
                    }
                }
            }
        }
    }
}

/** The osmdroid map itself, inside Compose. */
@SuppressLint("ClickableViewAccessibility")
@Composable
fun OsmMap(places: List<Place>, selected: Geocoder.Result?, centerOn: String?, modifier: Modifier) {
    val context = LocalContext.current
    val map = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            minZoomLevel = 2.0
            controller.setZoom(2.5)
            // let the map own drags instead of the scrolling screen around it
            setOnTouchListener { v, _ -> v.parent?.requestDisallowInterceptTouchEvent(true); false }
        }
    }
    DisposableEffect(map) {
        map.onResume()
        onDispose { map.onPause(); map.onDetach() }
    }
    val mapped = places.filter { it.latitude != null && it.longitude != null }

    // first view: fit all pins, otherwise centre on the destination
    var framed by remember { mutableStateOf(false) }
    LaunchedEffect(mapped.size, centerOn) {
        if (framed) return@LaunchedEffect
        when {
            mapped.size == 1 -> { map.controller.setZoom(13.0); map.controller.setCenter(GeoPoint(mapped[0].latitude!!, mapped[0].longitude!!)); framed = true }
            mapped.size > 1 -> map.post {
                runCatching { map.zoomToBoundingBox(BoundingBox.fromGeoPointsSafe(mapped.map { GeoPoint(it.latitude!!, it.longitude!!) }).increaseByScale(1.4f), false, 60) }
                framed = true
            }
            !centerOn.isNullOrBlank() -> runCatching { Geocoder.search(centerOn).firstOrNull() }.getOrNull()?.let {
                map.controller.setZoom(11.0); map.controller.setCenter(GeoPoint(it.lat, it.lon)); framed = true
            }
        }
    }
    LaunchedEffect(selected) {
        selected?.let { map.controller.animateTo(GeoPoint(it.lat, it.lon), 15.5, 900L) }
    }

    AndroidView({ map }, modifier) { mv ->
        mv.overlays.clear()
        fun dot(color: Int, size: Int) = ShapeDrawable(OvalShape()).apply { intrinsicWidth = size; intrinsicHeight = size; paint.color = color }
        mapped.forEach { p ->
            mv.overlays.add(Marker(mv).apply {
                position = GeoPoint(p.latitude!!, p.longitude!!)
                title = "${PlaceCategory.of(p.category).emoji} ${p.name}"
                snippet = listOf(p.city, p.countryName).filter { it.isNotBlank() }.joinToString(", ")
                icon = dot((if (p.visited) Teal else Coral).toArgb(), 44)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            })
        }
        selected?.let { r ->
            mv.overlays.add(Marker(mv).apply {
                position = GeoPoint(r.lat, r.lon)
                title = r.name
                icon = dot(Gold.toArgb(), 60)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            })
        }
        mv.invalidate()
    }
}
