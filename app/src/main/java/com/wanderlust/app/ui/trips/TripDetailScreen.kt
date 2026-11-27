package com.wanderlust.app.ui.trips

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import coil3.compose.AsyncImage
import com.wanderlust.app.LocalApp
import com.wanderlust.app.data.ChecklistItem
import com.wanderlust.app.data.Memory
import com.wanderlust.app.data.OwnerType
import com.wanderlust.app.data.PlaceCategory
import com.wanderlust.app.data.TravelRepository
import com.wanderlust.app.data.TripFull
import com.wanderlust.app.data.TripStatus
import com.wanderlust.app.data.flagEmoji
import com.wanderlust.app.domain.Countdowns
import com.wanderlust.app.domain.EventKind
import com.wanderlust.app.domain.ItineraryBuilder
import com.wanderlust.app.domain.dateRange
import com.wanderlust.app.domain.dayFmt
import com.wanderlust.app.domain.timeFmt
import com.wanderlust.app.domain.tripLengthDays
import com.wanderlust.app.ui.Routes
import com.wanderlust.app.ui.components.EmptyState
import com.wanderlust.app.ui.components.GlassCard
import com.wanderlust.app.ui.components.LiveCountdown
import com.wanderlust.app.ui.components.Pill
import com.wanderlust.app.ui.components.SmallLabel
import com.wanderlust.app.ui.components.StatTile
import com.wanderlust.app.ui.components.TripCover
import com.wanderlust.app.ui.itinerary.ItineraryPdf
import com.wanderlust.app.ui.theme.Coral
import com.wanderlust.app.ui.theme.Display
import com.wanderlust.app.ui.theme.Gold
import com.wanderlust.app.ui.theme.Night
import com.wanderlust.app.ui.theme.Teal
import com.wanderlust.app.ui.theme.Violet
import com.wanderlust.app.ui.theme.palette
import com.wanderlust.app.ui.world.MapExplorer
import kotlinx.coroutines.launch
import java.io.File

private enum class TripTab(val label: String) {
    OVERVIEW("Overview"), BOOKINGS("Bookings"), PLAN("Itinerary"), PLACES("Places"),
    CHECKLIST("Checklist"), EXPENSES("Expenses"), JOURNAL("Journal"), MEMORIES("Memories"), MAP("Map")
}

@Composable
fun TripDetailScreen(nav: NavHostController, tripId: Long) {
    val app = LocalApp.current
    val full by remember(tripId) { app.repo.tripFull(tripId) }.collectAsState(initial = null)
    var tab by rememberSaveable { mutableStateOf(TripTab.OVERVIEW) }
    var addMenu by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val pickMemories = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { uris ->
        app.appScope.launch {
            uris.forEach { uri -> app.files.import(uri, "memories")?.let { app.repo.addMemory(Memory(tripId = tripId, filePath = it.path)) } }
        }
    }

    val f = full ?: return
    val p = palette(f.trip.themeIndex)
    val weather by com.wanderlust.app.weather.rememberTripWeather(f)

    Scaffold(
        containerColor = Night,
        floatingActionButton = {
            if (tab != TripTab.OVERVIEW && tab != TripTab.MAP) Box {
                FloatingActionButton(onClick = {
                    when (tab) {
                        TripTab.BOOKINGS -> addMenu = true
                        TripTab.PLAN -> nav.navigate(Routes.activity(tripId))
                        TripTab.PLACES -> nav.navigate(Routes.place(tripId))
                        TripTab.CHECKLIST -> Unit
                        TripTab.EXPENSES -> nav.navigate(Routes.expense(tripId))
                        TripTab.JOURNAL -> nav.navigate(Routes.journal(tripId))
                        TripTab.MEMORIES -> pickMemories.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        else -> Unit
                    }
                }, containerColor = p.accent) { Icon(Icons.Default.Add, "Add") }
                DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                    DropdownMenuItem(text = { Text("✈️  Flight / transport") }, onClick = { addMenu = false; nav.navigate(Routes.transport(tripId)) })
                    DropdownMenuItem(text = { Text("🏨  Hotel / stay") }, onClick = { addMenu = false; nav.navigate(Routes.lodging(tripId)) })
                    DropdownMenuItem(text = { Text("✨  Import tickets & hotels (AI)") }, onClick = { addMenu = false; nav.navigate(Routes.scan(tripId, "bookings")) })
                }
            }
        },
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(bottom = pad.calculateBottomPadding()), contentPadding = PaddingValues(bottom = 100.dp)) {
            item { Header(f, onBack = { nav.popBackStack() }, onEdit = { nav.navigate(Routes.tripEdit(tripId)) }) }
            item {
                SecondaryScrollableTabRow(selectedTabIndex = tab.ordinal, containerColor = Night, edgePadding = 12.dp) {
                    TripTab.entries.forEach { t -> Tab(selected = tab == t, onClick = { tab = t }, text = { Text(t.label) }) }
                }
                Spacer(Modifier.height(12.dp))
            }
            when (tab) {
                TripTab.OVERVIEW -> overview(f, nav, weather, onExport = {
                    scope.launch {
                        runCatching { ItineraryPdf.export(context, f) }
                            .onSuccess { file -> app.files.share(context, file, "application/pdf", "${f.trip.name} itinerary") }
                            .onFailure { Toast.makeText(context, "Export failed: ${it.message}", Toast.LENGTH_LONG).show() }
                    }
                }, onArchive = { app.appScope.launch { app.repo.saveTrip(f.trip.copy(isArchived = !f.trip.isArchived)) } })
                TripTab.BOOKINGS -> bookings(f, nav)
                TripTab.PLAN -> plan(f, nav, weather)
                TripTab.PLACES -> places(f, nav, onToggle = { pl -> app.appScope.launch { app.repo.savePlace(pl.copy(visited = !pl.visited)) } })
                TripTab.CHECKLIST -> item { Checklist(f) }
                TripTab.EXPENSES -> item { com.wanderlust.app.expenses.ExpensesTab(f, nav) }
                TripTab.JOURNAL -> journal(f, nav)
                TripTab.MEMORIES -> item {
                    Memories(f, onAdd = { pickMemories.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        onOpenGooglePhotos = {
                            val intent = context.packageManager.getLaunchIntentForPackage("com.google.android.apps.photos")
                            if (intent != null) context.startActivity(intent)
                            else Toast.makeText(context, "Google Photos isn't installed", Toast.LENGTH_SHORT).show()
                        })
                }
                TripTab.MAP -> item {
                    MapExplorer(f.places, listOf(f.trip), fixedTripId = f.trip.id, centerOn = f.trip.destination.ifBlank { null },
                        modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(), mapHeight = 420)
                }
            }
        }
    }
}

@Composable
private fun Header(f: TripFull, onBack: () -> Unit, onEdit: () -> Unit) {
    val t = f.trip
    TripCover(t, Modifier.fillMaxWidth().height(300.dp)) {
        Row(Modifier.statusBarsPadding().padding(8.dp).fillMaxWidth()) {
            val c = IconButtonDefaults.iconButtonColors(containerColor = Color.Black.copy(alpha = 0.3f), contentColor = Color.White)
            IconButton(onClick = onBack, colors = c) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onEdit, colors = c) { Icon(Icons.Default.Edit, "Edit") }
        }
        Column(Modifier.align(Alignment.BottomStart).padding(20.dp)) {
            Pill(Countdowns.label(t))
            Spacer(Modifier.height(8.dp))
            Text(t.destination.ifBlank { t.name }, fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 36.sp, lineHeight = 40.sp, color = Color.White)
            Text(listOfNotNull(t.name.takeIf { it != t.destination && t.destination.isNotBlank() }, dateRange(t.startDate, t.endDate)).joinToString(" · "),
                color = Color.White.copy(alpha = 0.85f))
        }
    }
}

private fun LazyListScope.overview(f: TripFull, nav: NavHostController, weather: Map<java.time.LocalDate, com.wanderlust.app.weather.DayWeather>, onExport: () -> Unit, onArchive: () -> Unit) {
    val t = f.trip
    val status = TravelRepository.tripStatus(t)
    val target = Countdowns.target(t, f.transports)
    if (status == TripStatus.UPCOMING && target != null) item {
        GlassCard(Modifier.padding(horizontal = 20.dp).fillMaxWidth()) {
            Column(Modifier.padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                SmallLabel("Countdown to take-off")
                Spacer(Modifier.height(10.dp))
                LiveCountdown(target)
            }
        }
        Spacer(Modifier.height(14.dp))
    }
    if (weather.isNotEmpty()) item { WeatherStrip(weather) }
    item {
        Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FeatureCard("🗺️", "Visual itinerary", "Your days, beautifully laid out", listOf(Violet, Coral), Modifier.weight(1f)) { nav.navigate(Routes.itinerary(t.id)) }
            FeatureCard("📖", "Journal summary", "Your trip story & stats", listOf(Teal, Color(0xFF0F4C81)), Modifier.weight(1f)) { nav.navigate(Routes.summary(t.id)) }
        }
        Spacer(Modifier.height(14.dp))
    }
    item {
        val days = tripLengthDays(t.startDate, t.endDate)
        val packed = f.checklist.count { it.isChecked }
        Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile("${if (days > 0) days else "–"}", "Days", Modifier.weight(1f), Gold)
            StatTile("${f.transports.size + f.lodgings.size}", "Bookings", Modifier.weight(1f), Coral)
            StatTile("${f.places.size}", "Places", Modifier.weight(1f), Teal)
            StatTile("$packed/${f.checklist.size}", "Checked", Modifier.weight(1f), Violet)
        }
    }
    val docs = f.attachments.filter { it.ownerType == OwnerType.TRIP }
    if (docs.isNotEmpty()) item { TripDocuments(docs) }
    if (t.description.isNotBlank()) item {
        Text(t.description, modifier = Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    item {
        val upcomingEvents = ItineraryBuilder.build(f).days.firstOrNull { d -> !d.date.isBefore(java.time.LocalDate.now()) && d.events.isNotEmpty() }
        if (upcomingEvents != null) {
            Text("Next up · ${upcomingEvents.date.format(dayFmt)}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 8.dp))
            upcomingEvents.events.take(4).forEach { e ->
                Row(Modifier.padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(e.time?.format(timeFmt) ?: "··:··", color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(52.dp))
                    Text("${e.emoji}  ${e.title}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
    item {
        var calendar by remember { mutableStateOf(false) }
        OutlinedButton(onClick = { calendar = true }, modifier = Modifier.padding(start = 20.dp, top = 12.dp)) { Text("📅  Add to calendar") }
        if (calendar) com.wanderlust.app.calendar.CalendarDialog(f) { calendar = false }
        Row(Modifier.padding(20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onExport) { Icon(Icons.Default.PictureAsPdf, null); Spacer(Modifier.width(6.dp)); Text("Export PDF") }
            OutlinedButton(onClick = onArchive) { Icon(Icons.Default.Archive, null); Spacer(Modifier.width(6.dp)); Text(if (t.isArchived) "Unarchive" else "Archive") }
        }
    }
}

@Composable
private fun FeatureCard(emoji: String, title: String, body: String, colors: List<Color>, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(24.dp)).background(Brush.linearGradient(colors)).clickable(onClick = onClick).padding(16.dp)) {
        Text(emoji, fontSize = 28.sp)
        Spacer(Modifier.height(18.dp))
        Text(title, fontWeight = FontWeight.Bold, color = Color.White)
        Text(body, fontSize = 12.sp, color = Color.White.copy(alpha = 0.85f))
    }
}

private fun LazyListScope.bookings(f: TripFull, nav: NavHostController) {
    item {
        OutlinedButton(onClick = { nav.navigate(Routes.scan(f.trip.id, "bookings")) }, modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth()) {
            Text("✨  Import tickets & hotel confirmations (AI)")
        }
    }
    if (f.transports.isEmpty() && f.lodgings.isEmpty()) item {
        EmptyState("🎫", "No bookings yet", "Upload your flight and hotel tickets — pick several at once and AI fills in each booking.")
    }
    val p = palette(f.trip.themeIndex)
    if (f.transports.isNotEmpty()) item { SmallLabel("Transport", Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) }
    items(f.transports, key = { "t${it.id}" }) { t ->
        Box(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
            BoardingPass(t, p, f.attachments.filter { it.ownerType == OwnerType.TRANSPORT && it.ownerId == t.id }) { nav.navigate(Routes.transport(f.trip.id, t.id)) }
        }
    }
    if (f.lodgings.isNotEmpty()) item { SmallLabel("Stays", Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) }
    items(f.lodgings, key = { "l${it.id}" }) { l ->
        Box(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
            StayCard(l, f.attachments.filter { it.ownerType == OwnerType.LODGING && it.ownerId == l.id }) { nav.navigate(Routes.lodging(f.trip.id, l.id)) }
        }
    }
}

private fun LazyListScope.plan(f: TripFull, nav: NavHostController, weather: Map<java.time.LocalDate, com.wanderlust.app.weather.DayWeather>) {
    val it = ItineraryBuilder.build(f)
    item {
        Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { nav.navigate(Routes.itinerary(f.trip.id)) }, modifier = Modifier.weight(1f)) { Text("🗺️  Visual view") }
            OutlinedButton(onClick = { nav.navigate(Routes.scan(f.trip.id, "itinerary")) }, modifier = Modifier.weight(1f)) { Text("✨  Import (AI)") }
        }
    }
    if (it.days.isEmpty()) item { EmptyState("📅", "Set your trip dates", "Add dates to the trip and each day will appear here.") }
    items(it.days, key = { d -> d.date.toString() }) { d ->
        GlassCard(Modifier.padding(horizontal = 20.dp, vertical = 6.dp).fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (d.index >= 1) "Day ${d.index}" else "Travel day", fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = MaterialTheme.colorScheme.secondary)
                    Spacer(Modifier.width(10.dp))
                    Text(d.date.format(dayFmt), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(weather[d.date]?.let { "   ${it.emoji} ${it.temps()}" } ?: "", fontSize = 13.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = { nav.navigate(Routes.activity(f.trip.id, 0, d.date.toString())) }) { Text("+ Add") }
                }
                if (d.events.isEmpty()) Text("Free day — add an activity or ask the AI for ideas", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                d.events.forEach { e ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable {
                        when (e.kind) {
                            EventKind.ACTIVITY -> nav.navigate(Routes.activity(f.trip.id, e.refId))
                            EventKind.TRANSPORT -> nav.navigate(Routes.transport(f.trip.id, e.refId))
                            EventKind.CHECK_IN, EventKind.CHECK_OUT, EventKind.STAY -> nav.navigate(Routes.lodging(f.trip.id, e.refId))
                            EventKind.PLACE -> nav.navigate(Routes.place(f.trip.id, e.refId))
                        }
                    }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(e.time?.format(timeFmt) ?: "", color = MaterialTheme.colorScheme.secondary, modifier = Modifier.width(50.dp), fontSize = 13.sp)
                        Text("${e.emoji}  ${e.title}", maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
                    }
                }
                d.sleepingAt?.let { Text("🌙  $it", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp)) }
            }
        }
    }
    if (it.unscheduled.isNotEmpty()) item {
        Text("Unscheduled", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(20.dp, 12.dp))
        it.unscheduled.forEach { e -> Text("${e.emoji}  ${e.title}", modifier = Modifier.padding(horizontal = 28.dp, vertical = 3.dp)) }
    }
}

private fun LazyListScope.places(f: TripFull, nav: NavHostController, onToggle: (com.wanderlust.app.data.Place) -> Unit) {
    if (f.places.isEmpty()) item { EmptyState("📍", "No places yet", "Save attractions, restaurants and hidden gems you want to see.") }
    items(f.places, key = { it.id }) { pl ->
        val cat = PlaceCategory.of(pl.category)
        GlassCard(Modifier.padding(horizontal = 20.dp, vertical = 5.dp).fillMaxWidth(), onClick = { nav.navigate(Routes.place(f.trip.id, pl.id)) }) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(46.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.08f)), contentAlignment = Alignment.Center) { Text(cat.emoji, fontSize = 22.sp) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(pl.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(listOfNotNull(
                        pl.countryCode.takeIf { it.isNotBlank() }?.let { flagEmoji(it) },
                        pl.city.ifBlank { pl.address }.takeIf { it.isNotBlank() },
                        pl.visitAt?.format(dayFmt),
                        pl.rating?.let { "★".repeat(it.toInt()) },
                    ).joinToString(" · "), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Checkbox(checked = pl.visited, onCheckedChange = { onToggle(pl) })
            }
        }
    }
}

@Composable
private fun Checklist(f: TripFull) {
    val app = LocalApp.current
    var newItem by remember { mutableStateOf("") }
    var listName by remember { mutableStateOf("Packing") }
    val lists = (f.checklist.map { it.listName } + listOf("Packing", "To-do")).distinct()
    Column(Modifier.padding(horizontal = 20.dp)) {
        val done = f.checklist.count { it.isChecked }
        if (f.checklist.isNotEmpty()) {
            Text("$done of ${f.checklist.size} done", color = MaterialTheme.colorScheme.onSurfaceVariant)
            LinearProgressIndicator(progress = { done.toFloat() / f.checklist.size }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).height(8.dp).clip(RoundedCornerShape(4.dp)), color = Teal)
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            lists.forEach { l -> androidx.compose.material3.FilterChip(selected = l == listName, onClick = { listName = l }, label = { Text(l) }) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(newItem, { newItem = it }, label = { Text("Add to $listName") }, singleLine = true, modifier = Modifier.weight(1f))
            IconButton(onClick = {
                if (newItem.isNotBlank()) {
                    val name = newItem.trim(); newItem = ""
                    app.appScope.launch { app.repo.saveChecklist(ChecklistItem(tripId = f.trip.id, name = name, listName = listName)) }
                }
            }) { Icon(Icons.Default.Add, "Add") }
        }
        Spacer(Modifier.height(8.dp))
        f.checklist.groupBy { it.listName }.forEach { (name, items) ->
            SmallLabel(name, Modifier.padding(top = 10.dp, bottom = 2.dp))
            items.forEach { c ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = c.isChecked, onCheckedChange = { app.appScope.launch { app.repo.saveChecklist(c.copy(isChecked = it)) } })
                    Text(c.name, modifier = Modifier.weight(1f), color = if (c.isChecked) MaterialTheme.colorScheme.onSurfaceVariant else Color.White,
                        textDecoration = if (c.isChecked) androidx.compose.ui.text.style.TextDecoration.LineThrough else null)
                    IconButton(onClick = { app.appScope.launch { app.repo.deleteChecklist(c) } }) { Icon(Icons.Default.Delete, "Delete", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
    }
}

private fun LazyListScope.journal(f: TripFull, nav: NavHostController) {
    item {
        OutlinedButton(onClick = { nav.navigate(Routes.summary(f.trip.id)) }, modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth()) {
            Text("📖  View journal summary")
        }
        Spacer(Modifier.height(8.dp))
    }
    if (f.journal.isEmpty()) item { EmptyState("✍️", "Start your travel journal", "Capture how each day felt — later we’ll turn it into a story.") }
    items(f.journal.sortedByDescending { it.date }, key = { it.id }) { e ->
        GlassCard(Modifier.padding(horizontal = 20.dp, vertical = 6.dp).fillMaxWidth(), onClick = { nav.navigate(Routes.journal(f.trip.id, e.id)) }) {
            Column {
                e.photoPath?.let { AsyncImage(File(it), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().height(160.dp)) }
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(e.mood, fontSize = 24.sp)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(e.title, fontWeight = FontWeight.Bold)
                            Text(e.date.format(dayFmt), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (e.content.isNotBlank()) Text(e.content, maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Memories(f: TripFull, onAdd: () -> Unit, onOpenGooglePhotos: () -> Unit) {
    val app = LocalApp.current
    val context = LocalContext.current
    var toDelete by remember { mutableStateOf<Memory?>(null) }
    Column(Modifier.padding(horizontal = 20.dp)) {
        GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text("📸  Bring in your memories", fontWeight = FontWeight.SemiBold)
                Text(
                    "Tap + to pick photos — if Google Photos is your cloud media app, your cloud library appears right in the picker. " +
                        "You can also open Google Photos, select photos from the trip and Share → Wanderlust.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    OutlinedButton(onClick = onAdd) { Text("Pick photos") }
                    OutlinedButton(onClick = onOpenGooglePhotos) { Text("Open Google Photos") }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        if (f.memories.isEmpty()) EmptyState("🖼️", "No memories yet", "Photos you add appear here and on your home screen.")
        f.memories.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                row.forEach { m ->
                    AsyncImage(
                        File(m.filePath), m.caption, contentScale = ContentScale.Crop,
                        modifier = Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(12.dp))
                            .combinedClickable(onClick = { app.files.open(context, m.filePath, "image/*") }, onLongClick = { toDelete = m }),
                    )
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
    toDelete?.let { m ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Remove photo?") },
            text = { Text("It will be removed from Wanderlust (not from Google Photos).") },
            confirmButton = { TextButton(onClick = { app.appScope.launch { app.repo.deleteMemory(m) }; toDelete = null }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Cancel") } },
        )
    }
}


@Composable
private fun TripDocuments(docs: List<com.wanderlust.app.data.Attachment>) {
    val app = LocalApp.current
    val context = LocalContext.current
    Column(Modifier.padding(start = 20.dp, end = 8.dp, top = 18.dp)) {
        SmallLabel("Trip documents")
        docs.forEach { d ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                .clickable { app.files.open(context, d.filePath, d.mimeType) }) {
                Text(if (d.mimeType.startsWith("image/")) "🖼️" else "📄", fontSize = 18.sp)
                Text("  ${d.name}", Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                IconButton(onClick = { app.appScope.launch { app.repo.deleteAttachment(d) } }) {
                    Icon(Icons.Default.Delete, "Remove", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun WeatherStrip(weather: Map<java.time.LocalDate, com.wanderlust.app.weather.DayWeather>) {
    val days = weather.values.sortedBy { it.date }
    val typical = days.all { it.typical }
    Column(Modifier.padding(bottom = 14.dp)) {
        SmallLabel(if (typical) "Typical weather for your dates (last year)" else "Weather forecast", Modifier.padding(start = 20.dp, bottom = 6.dp))
        androidx.compose.foundation.lazy.LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(days.size) { i ->
                val w = days[i]
                GlassCard(corner = 18.dp) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(w.date.format(java.time.format.DateTimeFormatter.ofPattern("EEE d")), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(w.emoji, fontSize = 26.sp)
                        Text(w.temps(), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        w.rainChance?.takeIf { it >= 30 }?.let { Text("💧$it%", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        if (w.typical && !typical) Text("typical", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        Text("Weather data by Open-Meteo.com", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 20.dp, top = 4.dp))
    }
}
