package com.wanderlust.app.ui.importing

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.wanderlust.app.LocalApp
import com.wanderlust.app.SharedPayload
import com.wanderlust.app.ai.AiException
import com.wanderlust.app.ai.BookingDraft
import com.wanderlust.app.data.Attachment
import com.wanderlust.app.data.FileStore
import com.wanderlust.app.data.Lodging
import com.wanderlust.app.data.Memory
import com.wanderlust.app.data.OwnerType
import com.wanderlust.app.data.Transport
import com.wanderlust.app.data.Trip
import com.wanderlust.app.domain.dateRange
import com.wanderlust.app.domain.dateTimeFmt
import com.wanderlust.app.ui.Routes
import com.wanderlust.app.ui.components.EmptyState
import com.wanderlust.app.ui.components.GlassCard
import com.wanderlust.app.ui.components.SmallLabel
import com.wanderlust.app.ui.editors.applyDraft
import com.wanderlust.app.ui.theme.Night
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
private fun TripPicker(trips: List<Trip>, selected: Long, onSelect: (Long) -> Unit) {
    SmallLabel("Add to trip")
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        trips.filter { !it.isArchived }.forEach { t ->
            FilterChip(selected = t.id == selected, onClick = { onSelect(t.id) }, modifier = Modifier.fillMaxWidth(),
                label = { Text("${t.destination.ifBlank { t.name }} · ${dateRange(t.startDate, t.endDate)}") })
        }
    }
}

/**
 * "Scan ticket": upload a flight/hotel ticket (PDF or image), let Claude read it, and save it as a
 * booking on a trip with the ticket attached. A trip is created automatically if needed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanTicketScreen(nav: NavHostController, initialTripId: Long, sharedUri: Uri? = null, onDone: () -> Unit = {}) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val trips by app.repo.trips.collectAsState(initial = emptyList())
    val settings by app.settings.state.collectAsState()
    var tripId by remember { mutableStateOf(initialTripId) }
    var file by remember { mutableStateOf<FileStore.Stored?>(null) }
    var draft by remember { mutableStateOf<BookingDraft?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun analyse(stored: FileStore.Stored) {
        if (!settings.aiEnabled) { error = "Add your Anthropic API key in Settings so AI can read the ticket — or save it and fill in the details yourself."; return }
        busy = true; error = null
        scope.launch {
            try { draft = app.ai.extractBooking(stored.path, stored.mimeType) } catch (e: AiException) { error = e.message } finally { busy = false }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { app.files.import(uri, "attachments")?.let { file = it; draft = null; analyse(it) } }
    }
    LaunchedEffect(sharedUri) {
        if (sharedUri != null) app.files.import(sharedUri, "attachments")?.let { file = it; analyse(it) }
    }
    LaunchedEffect(trips) { if (tripId == 0L) trips.firstOrNull { !it.isArchived && (it.startDate == null || !it.startDate.isBefore(java.time.LocalDate.now())) }?.let { tripId = it.id } }

    fun save(kind: String) {
        val f = file ?: return
        val d = draft ?: BookingDraft(kind = kind)
        app.appScope.launch {
            val tid = if (tripId != 0L) tripId else app.repo.saveTrip(
                Trip(name = d.toLocation.ifBlank { d.address.ifBlank { "New trip" } }, destination = d.toLocation.ifBlank { d.address },
                    startDate = (d.departure ?: d.checkIn)?.toLocalDate(), endDate = (d.arrival ?: d.checkOut)?.toLocalDate())
            ).also { app.repo.seedPackingList(it) }
            val (ownerType, id) = if (kind == "lodging") {
                OwnerType.LODGING to app.repo.saveLodging(Lodging(tripId = tid, name = d.hotelName.ifBlank { f.name }).applyDraft(d))
            } else {
                OwnerType.TRANSPORT to app.repo.saveTransport(Transport(tripId = tid).applyDraft(d))
            }
            app.repo.addAttachment(Attachment(tripId = tid, ownerType = ownerType, ownerId = id, name = f.name, filePath = f.path, mimeType = f.mimeType))
            withContext(Dispatchers.Main) {
                onDone()
                nav.popBackStack()
                nav.navigate(if (kind == "lodging") Routes.lodging(tid, id) else Routes.transport(tid, id))
            }
        }
    }

    Scaffold(
        containerColor = Night,
        topBar = {
            TopAppBar(title = { Text("Scan a ticket") }, navigationIcon = { IconButton(onClick = { onDone(); nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Night))
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp)) {
                    Text("✈️🏨", fontSize = 34.sp)
                    Text("Upload a flight e-ticket, boarding pass or hotel confirmation", fontWeight = FontWeight.SemiBold)
                    Text("Claude reads it and fills in flight numbers, times, seats, PNRs and check-in dates. The original file is kept with the booking.",
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = { picker.launch(arrayOf("application/pdf", "image/*")) }) {
                        Icon(Icons.Default.UploadFile, null); Spacer(Modifier.width(6.dp)); Text(if (file == null) "Choose file" else "Choose another file")
                    }
                    file?.let { Text("📎 ${it.name}", modifier = Modifier.padding(top = 8.dp), fontSize = 13.sp) }
                }
            }
            if (busy) Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Text("  Reading your ticket…")
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            draft?.let { d ->
                GlassCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.AutoAwesome, null, tint = MaterialTheme.colorScheme.secondary); Text("  Found", fontWeight = FontWeight.Bold) }
                        if (d.kind == "lodging") {
                            Line("Hotel", d.hotelName); Line("Address", d.address)
                            Line("Check-in", d.checkIn?.format(dateTimeFmt)); Line("Check-out", d.checkOut?.format(dateTimeFmt))
                            Line("Reservation", d.bookingRef)
                        } else {
                            Line("Carrier", listOf(d.carrier, d.number).filter { it.isNotBlank() }.joinToString(" "))
                            Line("Route", listOf(d.fromCode.ifBlank { d.fromLocation }, d.toCode.ifBlank { d.toLocation }).filter { it.isNotBlank() }.joinToString(" → "))
                            Line("Departs", d.departure?.format(dateTimeFmt)); Line("Arrives", d.arrival?.format(dateTimeFmt))
                            Line("Seat", d.seat); Line("Booking ref", d.bookingRef)
                        }
                        Line("Passenger", d.passenger); Line("Notes", d.notes)
                    }
                }
            }
            if (file != null && !busy) {
                if (trips.isEmpty()) Text("No trips yet — a trip will be created from the ticket.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                else TripPicker(trips, tripId) { tripId = it }
                val kind = draft?.kind ?: "unknown"
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (kind != "lodging") Button(onClick = { save("transport") }) { Text("Save as flight / transport") }
                    if (kind != "transport") OutlinedButton(onClick = { save("lodging") }) { Text("Save as stay") }
                }
            }
        }
    }
}

@Composable
private fun Line(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    Row { Text("$label  ", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp); Text(value, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
}

/** Handles content shared into the app: photos → trip memories; a PDF → ticket scanner. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareImportScreen(nav: NavHostController, shared: SharedPayload?, onConsumed: () -> Unit) {
    val app = LocalApp.current
    val trips by app.repo.trips.collectAsState(initial = emptyList())
    var tripId by remember { mutableStateOf(0L) }
    var saving by remember { mutableStateOf(false) }

    if (shared == null) { LaunchedEffect(Unit) { nav.popBackStack() }; return }
    if (!shared.isImages) { ScanTicketScreen(nav, 0, shared.uris.first(), onConsumed); return }

    LaunchedEffect(trips) {
        if (tripId == 0L) (trips.firstOrNull { com.wanderlust.app.data.TravelRepository.tripStatus(it) == com.wanderlust.app.data.TripStatus.ONGOING }
            ?: trips.maxByOrNull { it.startDate ?: java.time.LocalDate.MIN })?.let { tripId = it.id }
    }

    Scaffold(
        containerColor = Night,
        topBar = {
            TopAppBar(title = { Text("Add ${shared.uris.size} photo${if (shared.uris.size == 1) "" else "s"} to memories") },
                navigationIcon = { IconButton(onClick = { onConsumed(); nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Night))
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (trips.isEmpty()) {
                EmptyState("🧳", "Create a trip first", "Memories are saved to a trip.") {
                    Button(onClick = { nav.navigate(Routes.tripEdit(0)) }) { Text("Create trip") }
                }
            } else {
                TripPicker(trips, tripId) { tripId = it }
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
