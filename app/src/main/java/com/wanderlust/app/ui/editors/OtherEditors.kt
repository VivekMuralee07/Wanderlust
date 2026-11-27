package com.wanderlust.app.ui.editors

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import coil3.compose.AsyncImage
import com.wanderlust.app.LocalApp
import com.wanderlust.app.data.Geocoder
import com.wanderlust.app.data.ItineraryItem
import com.wanderlust.app.data.JournalEntry
import com.wanderlust.app.data.Place
import com.wanderlust.app.data.PlaceCategory
import com.wanderlust.app.data.flagEmoji
import com.wanderlust.app.ui.components.DateField
import com.wanderlust.app.ui.components.DateTimeField
import com.wanderlust.app.ui.components.GlassCard
import com.wanderlust.app.ui.components.SmallLabel
import com.wanderlust.app.ui.components.TimeField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

@Composable
fun PlaceEditor(nav: NavHostController, tripId: Long, id: Long) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    var p by remember { mutableStateOf(Place(tripId = tripId)) }
    var loaded by remember { mutableStateOf(id == 0L) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Geocoder.Result>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }
    var defaultDate by remember { mutableStateOf<LocalDate?>(null) }
    LaunchedEffect(id) {
        val trip = app.repo.trip(tripId)
        defaultDate = trip?.startDate
        if (id != 0L) app.repo.place(id)?.let { p = it; query = it.name }
        loaded = true
    }
    if (!loaded) return

    EditorScaffold(
        title = if (id == 0L) "Add place" else "Edit place",
        onBack = { nav.popBackStack() },
        saveEnabled = p.name.isNotBlank(),
        onSave = { app.appScope.launch { app.repo.savePlace(p); withContext(Dispatchers.Main) { nav.popBackStack() } } },
        onDelete = if (id != 0L) ({ app.appScope.launch { app.repo.deletePlace(p); withContext(Dispatchers.Main) { nav.popBackStack() } } }) else null,
    ) {
        GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallLabel("Find on the map (OpenStreetMap)")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Field("Search a place", query, { query = it }, Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    FilledTonalButton(onClick = {
                        searching = true; searchError = null
                        scope.launch {
                            results = runCatching { Geocoder.search(query) }.getOrElse { searchError = "Search failed: ${it.message}"; emptyList() }
                            if (results.isEmpty() && searchError == null) searchError = "No results"
                            searching = false
                        }
                    }, enabled = query.isNotBlank() && !searching) {
                        if (searching) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Icon(Icons.Default.Search, "Search")
                    }
                }
                searchError?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
                results.forEach { r ->
                    Text(
                        "${flagEmoji(r.countryCode)}  ${r.displayName}", maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 13.sp,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable {
                            p = p.copy(
                                name = p.name.ifBlank { r.name }, address = r.displayName, latitude = r.lat, longitude = r.lon,
                                city = r.city, countryName = r.country, countryCode = r.countryCode,
                            )
                            results = emptyList()
                        }.padding(8.dp),
                    )
                }
                if (p.latitude != null) Text("📍 ${"%.4f".format(p.latitude)}, ${"%.4f".format(p.longitude)}  ${flagEmoji(p.countryCode)} ${p.countryName}",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.tertiary)
            }
        }
        Field("Name", p.name, { p = p.copy(name = it) })
        SmallLabel("Category")
        ChipPicker(PlaceCategory.entries, PlaceCategory.of(p.category), { "${it.emoji} ${it.label}" }) { p = p.copy(category = it.name) }
        Field("Address", p.address, { p = p.copy(address = it) })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field("City", p.city, { p = p.copy(city = it) }, Modifier.weight(1f))
            Field("Country", p.countryName, { p = p.copy(countryName = it) }, Modifier.weight(1f))
        }
        DateTimeField("Visit", p.visitAt, { p = p.copy(visitAt = it) }, defaultDate = defaultDate)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Visited ✅", Modifier.weight(1f))
            Switch(checked = p.visited, onCheckedChange = { p = p.copy(visited = it) })
        }
        RatingRow(p.rating) { p = p.copy(rating = it) }
        Field("Link", p.link, { p = p.copy(link = it) })
        Field("Notes", p.notes, { p = p.copy(notes = it) }, singleLine = false, minLines = 3)
    }
}

private val activityEmojis = listOf("⭐", "🍽️", "☕", "🏛️", "🎨", "🥾", "🏖️", "🛍️", "🎭", "🎢", "🚤", "📸", "🧘", "🍷", "🌅", "🎉")

@Composable
fun ActivityEditor(nav: NavHostController, tripId: Long, id: Long, date: String) {
    val app = LocalApp.current
    var item by remember {
        mutableStateOf(ItineraryItem(tripId = tripId, date = runCatching { LocalDate.parse(date) }.getOrElse { LocalDate.now() }, title = ""))
    }
    var loaded by remember { mutableStateOf(id == 0L && date.isNotBlank()) }
    LaunchedEffect(id) {
        if (id != 0L) app.repo.itineraryItem(id)?.let { item = it }
        else if (date.isBlank()) app.repo.trip(tripId)?.startDate?.let { item = item.copy(date = it) }
        loaded = true
    }
    if (!loaded) return

    EditorScaffold(
        title = if (id == 0L) "Add activity" else "Edit activity",
        onBack = { nav.popBackStack() },
        saveEnabled = item.title.isNotBlank(),
        onSave = { app.appScope.launch { app.repo.saveItinerary(item); withContext(Dispatchers.Main) { nav.popBackStack() } } },
        onDelete = if (id != 0L) ({ app.appScope.launch { app.repo.deleteItinerary(item); withContext(Dispatchers.Main) { nav.popBackStack() } } }) else null,
    ) {
        Field("What are you doing?", item.title, { item = item.copy(title = it) })
        SmallLabel("Icon")
        ChipPicker(activityEmojis, item.emoji, { it }) { item = item.copy(emoji = it) }
        DateField("Day", item.date, { it?.let { d -> item = item.copy(date = d) } })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TimeField("Start", item.time, { item = item.copy(time = it) }, Modifier.weight(1f))
            TimeField("End", item.endTime, { item = item.copy(endTime = it) }, Modifier.weight(1f))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Optional / if time allows", Modifier.weight(1f))
            Switch(checked = item.isOptional, onCheckedChange = { item = item.copy(isOptional = it) })
        }
        Field("Notes", item.notes, { item = item.copy(notes = it) }, singleLine = false, minLines = 3)
    }
}

private val moods = listOf("🤩", "😊", "😌", "🥰", "😋", "🥱", "😮", "🤔", "😢", "😤")

@Composable
fun JournalEditor(nav: NavHostController, tripId: Long, id: Long) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    var e by remember { mutableStateOf(JournalEntry(tripId = tripId, date = LocalDate.now(), title = "")) }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(id) {
        if (id != 0L) app.repo.journalEntry(id)?.let { e = it }
        else app.repo.trip(tripId)?.let { t ->
            val today = LocalDate.now()
            val d = if (t.startDate != null && (today.isBefore(t.startDate) || (t.endDate != null && today.isAfter(t.endDate)))) t.startDate else today
            e = e.copy(date = d)
        }
        loaded = true
    }
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch { app.files.import(uri, "memories")?.let { e = e.copy(photoPath = it.path) } }
    }
    if (!loaded) return

    EditorScaffold(
        title = if (id == 0L) "New journal entry" else "Edit entry",
        onBack = { nav.popBackStack() },
        saveEnabled = e.title.isNotBlank() || e.content.isNotBlank(),
        onSave = {
            app.appScope.launch {
                app.repo.saveJournal(e.copy(title = e.title.ifBlank { e.content.take(40) }))
                withContext(Dispatchers.Main) { nav.popBackStack() }
            }
        },
        onDelete = if (id != 0L) ({ app.appScope.launch { app.repo.deleteJournal(e); withContext(Dispatchers.Main) { nav.popBackStack() } } }) else null,
    ) {
        SmallLabel("How did today feel?")
        ChipPicker(moods, e.mood, { it }) { e = e.copy(mood = it) }
        DateField("Date", e.date, { it?.let { d -> e = e.copy(date = d) } })
        Field("Title", e.title, { e = e.copy(title = it) })
        Field("Write about your day…", e.content, { e = e.copy(content = it) }, singleLine = false, minLines = 8)
        if (e.photoPath != null) {
            AsyncImage(File(e.photoPath!!), null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(20.dp)))
        }
        OutlinedButton(onClick = { pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
            Icon(Icons.Default.AddPhotoAlternate, null); Spacer(Modifier.width(6.dp))
            Text(if (e.photoPath == null) "Add a photo" else "Change photo")
        }
    }
}
