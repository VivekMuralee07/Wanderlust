package com.wanderlust.app.ui.importing

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.wanderlust.app.LocalApp
import com.wanderlust.app.SharedPayload
import com.wanderlust.app.ai.AiException
import com.wanderlust.app.ai.PlanDraft
import com.wanderlust.app.ai.SourceFile
import com.wanderlust.app.data.LodgingType
import com.wanderlust.app.data.Memory
import com.wanderlust.app.data.PlaceCategory
import com.wanderlust.app.data.TransportType
import com.wanderlust.app.data.Trip
import com.wanderlust.app.domain.PlanSaver
import com.wanderlust.app.domain.dateRange
import com.wanderlust.app.domain.dateTimeFmt
import com.wanderlust.app.domain.dayFmt
import com.wanderlust.app.domain.timeFmt
import com.wanderlust.app.ui.Routes
import com.wanderlust.app.ui.components.EmptyState
import com.wanderlust.app.ui.components.GlassCard
import com.wanderlust.app.ui.components.SmallLabel
import com.wanderlust.app.ui.theme.Night
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

object ImportMode {
    const val BOOKINGS = "bookings"
    const val ITINERARY = "itinerary"
}

@Composable
private fun TripPicker(trips: List<Trip>, selected: Long, allowNew: Boolean, onSelect: (Long) -> Unit) {
    SmallLabel("Save to trip")
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (allowNew) FilterChip(selected = selected == 0L, onClick = { onSelect(0L) }, modifier = Modifier.fillMaxWidth(),
            label = { Text("✨ Create a new trip from these files") })
        trips.filter { !it.isArchived }.forEach { t ->
            FilterChip(selected = t.id == selected, onClick = { onSelect(t.id) }, modifier = Modifier.fillMaxWidth(),
                label = { Text("${t.destination.ifBlank { t.name }} · ${dateRange(t.startDate, t.endDate)}", maxLines = 1, overflow = TextOverflow.Ellipsis) })
        }
    }
}

/**
 * AI import: upload one or more tickets, hotel confirmations or a whole itinerary (images and/or PDFs).
 * Claude splits them into separate flights, stays, places and activities; the user ticks what to keep.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmartImportScreen(nav: NavHostController, initialTripId: Long, initialMode: String, sharedUris: List<Uri> = emptyList(), onDone: () -> Unit = {}) {
    val app = LocalApp.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val trips by app.repo.trips.collectAsState(initial = emptyList())
    val settings by app.settings.state.collectAsState()
    var mode by remember { mutableStateOf(initialMode) }
    var tripId by remember { mutableStateOf(initialTripId) }
    val files = remember { mutableStateListOf<SourceFile>() }
    var plan by remember { mutableStateOf<PlanDraft?>(null) }
    val keep = remember { mutableStateMapOf<String, Boolean>() }
    var busy by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    suspend fun addUris(uris: List<Uri>) {
        uris.forEach { u -> app.files.import(u, "attachments")?.let { files += SourceFile(it.path, it.mimeType, it.name) } }
        plan = null
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) scope.launch { addUris(uris) }
    }
    LaunchedEffect(sharedUris) { if (sharedUris.isNotEmpty()) addUris(sharedUris) }
    LaunchedEffect(trips) {
        if (tripId == 0L && initialTripId == 0L && mode == ImportMode.BOOKINGS) {
            trips.firstOrNull { !it.isArchived && (it.startDate == null || !it.startDate.isBefore(java.time.LocalDate.now())) }?.let { tripId = it.id }
        }
    }

    fun analyse() {
        if (!settings.aiEnabled) { error = "Add your Anthropic API key in Settings so AI can read your files."; return }
        busy = true; error = null; plan = null
        scope.launch {
            try {
                val trip = trips.firstOrNull { it.id == tripId }
                val p = app.ai.extractPlan(files.toList(), trip?.destination.orEmpty(), trip?.startDate, trip?.endDate)
                keep.clear()
                p.transports.indices.forEach { keep["t$it"] = true }
                p.lodgings.indices.forEach { keep["l$it"] = true }
                p.places.indices.forEach { keep["p$it"] = true }
                p.activities.indices.forEach { keep["a$it"] = true }
                plan = p
                if (p.isEmpty) error = "Nothing travel-related was found in those files."
            } catch (e: AiException) { error = e.message } finally { busy = false }
        }
    }

    fun save() {
        val p = plan ?: return
        saving = true
        val selected = p.copy(
            transports = p.transports.filterIndexed { i, _ -> keep["t$i"] == true },
            lodgings = p.lodgings.filterIndexed { i, _ -> keep["l$i"] == true },
            places = p.places.filterIndexed { i, _ -> keep["p$i"] == true },
            activities = p.activities.filterIndexed { i, _ -> keep["a$i"] == true },
        )
        app.appScope.launch {
            val tid = PlanSaver.save(app, tripId, selected, files.toList())
            withContext(Dispatchers.Main) {
                onDone()
                nav.popBackStack()
                if (initialTripId == 0L || initialTripId != tid) nav.navigate(Routes.trip(tid))
            }
        }
    }

    val isItinerary = mode == ImportMode.ITINERARY
    Scaffold(
        containerColor = Night,
        topBar = {
            TopAppBar(
                title = { Text(if (isItinerary) "Import itinerary" else "Import bookings") },
                navigationIcon = { IconButton(onClick = { files.forEach { File(it.path).delete() }; onDone(); nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Night),
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf(ImportMode.BOOKINGS to "✈️🏨  Tickets & hotels", ImportMode.ITINERARY to "🗺️  Itinerary").forEachIndexed { i, (m, label) ->
                    SegmentedButton(selected = mode == m, onClick = { mode = m }, shape = SegmentedButtonDefaults.itemShape(i, 2)) { Text(label, fontSize = 13.sp) }
                }
            }
            GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (isItinerary) "Upload your itinerary — a PDF from your travel agent, screenshots, or photos of a printed plan."
                        else "Upload e-tickets, boarding passes and hotel confirmations. Pick several at once — each booking is saved separately.",
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        if (isItinerary) "AI fills in flights, hotels, the places you’ll visit and each day’s activities."
                        else "AI splits them into individual flights and stays, with each original file attached to its booking.",
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    files.forEach { f ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                            .clickable { app.files.open(context, f.path, f.mimeType) }) {
                            Icon(if (f.mimeType.startsWith("image/")) Icons.Default.Image else Icons.Default.Description, null, tint = MaterialTheme.colorScheme.secondary)
                            Text("  ${f.name}", Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
                            IconButton(onClick = { files.remove(f); File(f.path).delete(); plan = null }) { Icon(Icons.Default.Close, "Remove") }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { picker.launch(arrayOf("application/pdf", "image/*")) }) {
                            Icon(Icons.Default.UploadFile, null); Spacer(Modifier.width(6.dp)); Text(if (files.isEmpty()) "Choose files" else "Add more")
                        }
                        Button(onClick = ::analyse, enabled = files.isNotEmpty() && !busy) {
                            Icon(Icons.Default.AutoAwesome, null); Spacer(Modifier.width(6.dp)); Text("Read with AI")
                        }
                    }
                }
            }
            if (busy) Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text("  Reading ${files.size} file${if (files.size == 1) "" else "s"}… this can take up to a minute")
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            plan?.takeIf { !it.isEmpty }?.let { p ->
                Text("Found ${p.count} item${if (p.count == 1) "" else "s"} — untick anything you don’t want", style = MaterialTheme.typography.titleMedium)
                fun name(i: Int) = files.getOrNull(i)?.name.orEmpty()
                if (p.transports.isNotEmpty()) Section("Flights & transport") {
                    p.transports.forEachIndexed { i, d ->
                        val type = TransportType.of(d.transportType)
                        Item(keep["t$i"] == true, { keep["t$i"] = it },
                            "${type.emoji}  ${listOf(d.carrier, d.number).filter { s -> s.isNotBlank() }.joinToString(" ").ifBlank { type.label }}  " +
                                listOf(d.fromCode.ifBlank { d.fromLocation }, d.toCode.ifBlank { d.toLocation }).filter { s -> s.isNotBlank() }.joinToString(" → "),
                            listOfNotNull(d.departure?.format(dateTimeFmt), d.seat.takeIf { s -> s.isNotBlank() }?.let { s -> "Seat $s" },
                                d.bookingRef.takeIf { s -> s.isNotBlank() }?.let { s -> "Ref $s" }).joinToString(" · "),
                            name(d.sourceFile))
                    }
                }
                if (p.lodgings.isNotEmpty()) Section("Stays") {
                    p.lodgings.forEachIndexed { i, d ->
                        Item(keep["l$i"] == true, { keep["l$i"] = it },
                            "${LodgingType.of(d.lodgingType).emoji}  ${d.hotelName.ifBlank { "Stay" }}",
                            listOfNotNull(
                                if (d.checkIn != null) "${d.checkIn.format(dayFmt)} → ${d.checkOut?.format(dayFmt) ?: "?"}" else null,
                                d.bookingRef.takeIf { s -> s.isNotBlank() }?.let { s -> "Res. $s" }).joinToString(" · "),
                            name(d.sourceFile))
                    }
                }
                if (p.places.isNotEmpty()) Section("Places to visit") {
                    p.places.forEachIndexed { i, d ->
                        Item(keep["p$i"] == true, { keep["p$i"] = it }, "${PlaceCategory.of(d.category).emoji}  ${d.name}",
                            listOfNotNull(d.city.takeIf { s -> s.isNotBlank() }, d.visitAt?.format(dayFmt)).joinToString(" · "), null)
                    }
                }
                if (p.activities.isNotEmpty()) Section("Activities") {
                    p.activities.forEachIndexed { i, d ->
                        Item(keep["a$i"] == true, { keep["a$i"] = it }, "${d.emoji}  ${d.title}",
                            listOfNotNull(d.date.format(dayFmt), d.time?.format(timeFmt)).joinToString(" · "), null)
                    }
                }
                TripPicker(trips, tripId, allowNew = true) { tripId = it }
                if (tripId == 0L && p.destination.isNotBlank()) Text("New trip: ${p.destination} · ${dateRange(p.startDate, p.endDate)}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val n = keep.values.count { it }
                Button(onClick = ::save, enabled = n > 0 && !saving, modifier = Modifier.fillMaxWidth()) {
                    Text(if (saving) "Saving…" else "Save $n item${if (n == 1) "" else "s"}")
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 10.dp)) {
            SmallLabel(title, Modifier.padding(start = 8.dp, bottom = 4.dp))
            content()
        }
    }
}

@Composable
private fun Item(checked: Boolean, onChecked: (Boolean) -> Unit, title: String, subtitle: String, file: String?) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onChecked(!checked) }) {
        Checkbox(checked = checked, onCheckedChange = onChecked)
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!file.isNullOrBlank()) Text("📎 $file", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Handles content shared into the app: photos → memories or AI import; PDFs → AI import. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareImportScreen(nav: NavHostController, shared: SharedPayload?, onConsumed: () -> Unit) {
    val app = LocalApp.current
    val trips by app.repo.trips.collectAsState(initial = emptyList())
    var tripId by remember { mutableStateOf(0L) }
    var saving by remember { mutableStateOf(false) }
    var asDocuments by remember { mutableStateOf(false) }

    if (shared == null) { LaunchedEffect(Unit) { nav.popBackStack() }; return }
    if (!shared.isImages || asDocuments) { SmartImportScreen(nav, 0, ImportMode.BOOKINGS, shared.uris, onConsumed); return }

    LaunchedEffect(trips) {
        if (tripId == 0L) (trips.firstOrNull { com.wanderlust.app.data.TravelRepository.tripStatus(it) == com.wanderlust.app.data.TripStatus.ONGOING }
            ?: trips.maxByOrNull { it.startDate ?: java.time.LocalDate.MIN })?.let { tripId = it.id }
    }

    Scaffold(
        containerColor = Night,
        topBar = {
            TopAppBar(title = { Text("${shared.uris.size} photo${if (shared.uris.size == 1) "" else "s"} shared") },
                navigationIcon = { IconButton(onClick = { onConsumed(); nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Night))
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { asDocuments = true }, modifier = Modifier.fillMaxWidth()) {
                Text("✨ These are tickets / an itinerary — read them with AI")
            }
            Spacer(Modifier.height(4.dp))
            SmallLabel("Or add them to a trip’s memories")
            if (trips.isEmpty()) {
                EmptyState("🧳", "Create a trip first", "Memories are saved to a trip.") {
                    Button(onClick = { nav.navigate(Routes.tripEdit(0)) }) { Text("Create trip") }
                }
            } else {
                TripPicker(trips, tripId, allowNew = false) { tripId = it }
                Button(enabled = tripId != 0L && !saving, onClick = {
                    saving = true
                    app.appScope.launch {
                        shared.uris.forEach { u -> app.files.import(u, "memories")?.let { app.repo.addMemory(Memory(tripId = tripId, filePath = it.path)) } }
                        withContext(Dispatchers.Main) {
                            onConsumed()
                            nav.popBackStack()
                            nav.navigate(Routes.trip(tripId))
                        }
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text(if (saving) "Saving…" else "Save to memories") }
            }
        }
    }
}
