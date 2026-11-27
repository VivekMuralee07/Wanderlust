package com.wanderlust.app.ui.editors

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.wanderlust.app.LocalApp
import com.wanderlust.app.ai.AiException
import com.wanderlust.app.ai.BookingDraft
import com.wanderlust.app.ai.PlanDraft
import com.wanderlust.app.ai.SourceFile
import com.wanderlust.app.domain.PlanSaver
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.height
import com.wanderlust.app.data.Attachment
import com.wanderlust.app.data.Lodging
import com.wanderlust.app.data.LodgingType
import com.wanderlust.app.data.OwnerType
import com.wanderlust.app.data.Transport
import com.wanderlust.app.data.TransportType
import com.wanderlust.app.ui.components.DateTimeField
import com.wanderlust.app.ui.components.GlassCard
import com.wanderlust.app.ui.components.SmallLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Pending (not yet saved) or saved ticket file. */
data class TicketFile(val path: String, val name: String, val mime: String, val saved: Attachment? = null)

/**
 * Ticket uploader shared by the flight and hotel editors: attach several PDFs/images, open them, and let
 * Claude read them all. The first matching booking fills this form; any further flights/stays found are
 * offered as separate bookings (each with its own file attached).
 */
@Composable
fun TicketSection(
    tripId: Long,
    kind: String,                       // "transport" or "lodging"
    files: MutableList<TicketFile>,
    removed: MutableList<Attachment>,
    onAutoFill: (BookingDraft) -> Unit,
) {
    val app = LocalApp.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by app.settings.state.collectAsState()
    var busy by remember { mutableStateOf(false) }
    var extras by remember { mutableStateOf<Pair<PlanDraft, List<SourceFile>>?>(null) }
    var primarySource by remember { mutableStateOf(-1) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            uris.forEach { uri ->
                app.files.import(uri, "attachments")?.let { files += TicketFile(it.path, it.name, it.mimeType) }
                    ?: Toast.makeText(context, "Couldn't read a file", Toast.LENGTH_SHORT).show()
            }
        }
    }

    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallLabel("Tickets & confirmations")
            if (files.isEmpty()) Text(
                if (kind == "lodging") "Upload hotel confirmations (PDFs or screenshots). Pick several — AI creates one stay per booking."
                else "Upload e-tickets or boarding passes (PDF or image). Pick several — AI creates one booking per flight.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            files.forEach { f ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AssistChip(
                        onClick = { app.files.open(context, f.path, f.mime) },
                        label = { Text(f.name, maxLines = 1) },
                        leadingIcon = { Icon(if (f.mime.startsWith("image/")) Icons.Default.Image else Icons.Default.Description, null) },
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = {
                        files.remove(f)
                        if (f.saved != null) removed += f.saved else File(f.path).delete()
                    }) { Icon(Icons.Default.Close, "Remove") }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { picker.launch(arrayOf("application/pdf", "image/*")) }) {
                    Icon(Icons.Default.AttachFile, null); Spacer(Modifier.width(6.dp)); Text("Upload")
                }
                FilledTonalButton(
                    enabled = files.isNotEmpty() && !busy,
                    onClick = {
                        if (!settings.aiEnabled) {
                            Toast.makeText(context, "Add an Anthropic API key in Settings to auto-fill from tickets", Toast.LENGTH_LONG).show()
                            return@FilledTonalButton
                        }
                        val sources = files.map { SourceFile(it.path, it.mime, it.name) }
                        busy = true
                        scope.launch {
                            try {
                                val trip = app.repo.trip(tripId)
                                val plan = app.ai.extractPlan(sources, trip?.destination.orEmpty(), trip?.startDate, trip?.endDate)
                                val mine = if (kind == "lodging") plan.lodgings else plan.transports
                                val others = if (kind == "lodging") plan.transports else plan.lodgings
                                mine.firstOrNull()?.let {
                                    onAutoFill(it)
                                    Toast.makeText(context, "Filled in from your file ✨ — please double-check", Toast.LENGTH_LONG).show()
                                }
                                val rest = mine.drop(1) + others
                                if (rest.isNotEmpty()) {
                                    extras = plan.copy(
                                        transports = rest.filter { it.kind == "transport" },
                                        lodgings = rest.filter { it.kind == "lodging" },
                                        places = emptyList(), activities = emptyList(),
                                    ) to sources
                                } else if (mine.isEmpty()) {
                                    Toast.makeText(context, if (kind == "lodging") "No hotel booking found in these files" else "No ticket found in these files", Toast.LENGTH_LONG).show()
                                }
                                // remember which file filled this form, so other files can move to their own bookings
                                primarySource = mine.firstOrNull()?.sourceFile ?: -1
                            } catch (e: AiException) {
                                Toast.makeText(context, e.message, Toast.LENGTH_LONG).show()
                            } finally { busy = false }
                        }
                    },
                ) {
                    if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Icon(Icons.Default.AutoAwesome, null)
                    Spacer(Modifier.width(6.dp)); Text("Auto-fill with AI")
                }
            }
        }
    }

    extras?.let { (plan, sources) ->
        AlertDialog(
            onDismissRequest = { extras = null },
            title = { Text("Found ${plan.count} more booking${if (plan.count == 1) "" else "s"}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    plan.transports.forEach { d -> Text("${TransportType.of(d.transportType).emoji}  ${listOf(d.carrier, d.number).filter { it.isNotBlank() }.joinToString(" ")} ${d.fromCode.ifBlank { d.fromLocation }} → ${d.toCode.ifBlank { d.toLocation }}") }
                    plan.lodgings.forEach { d -> Text("${LodgingType.of(d.lodgingType).emoji}  ${d.hotelName.ifBlank { "Stay" }}${d.checkIn?.let { " · " + it.format(com.wanderlust.app.domain.dayFmt) } ?: ""}") }
                    Spacer(Modifier.height(6.dp))
                    Text("Add them to this trip as separate bookings, each with its own file?", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    extras = null
                    // files that only belong to the extra bookings move out of this form
                    val usedByExtras = (plan.transports + plan.lodgings).map { it.sourceFile }.toSet()
                    val moving = sources.indices.filter { it in usedByExtras && it != primarySource }.mapNotNull { i -> files.getOrNull(i)?.takeIf { it.saved == null } }
                    app.appScope.launch {
                        PlanSaver.save(app, tripId, plan, sources, copyAll = true)
                        withContext(Dispatchers.Main) {
                            moving.forEach { files.remove(it); File(it.path).delete() }
                            Toast.makeText(context, "Added ${plan.count} more booking${if (plan.count == 1) "" else "s"} to the trip", Toast.LENGTH_LONG).show()
                        }
                    }
                }) { Text("Add them") }
            },
            dismissButton = { TextButton(onClick = { extras = null }) { Text("No thanks") } },
        )
    }
}


/** Persists newly added ticket files as attachments and deletes removed ones. */
suspend fun saveTickets(app: com.wanderlust.app.AppContainer, tripId: Long, ownerType: String, ownerId: Long, files: List<TicketFile>, removed: List<Attachment>) {
    removed.forEach { app.repo.deleteAttachment(it) }
    files.filter { it.saved == null }.forEach {
        app.repo.addAttachment(Attachment(tripId = tripId, ownerType = ownerType, ownerId = ownerId, name = it.name, filePath = it.path, mimeType = it.mime))
    }
}

fun Transport.applyDraft(d: BookingDraft) = copy(
    type = TransportType.of(d.transportType).name,
    name = d.carrier.ifBlank { name }, flightNumber = d.number.ifBlank { flightNumber },
    fromLocation = d.fromLocation.ifBlank { fromLocation }, toLocation = d.toLocation.ifBlank { toLocation },
    fromCode = d.fromCode.ifBlank { fromCode }, toCode = d.toCode.ifBlank { toCode },
    departure = d.departure ?: departure, arrival = d.arrival ?: arrival,
    seat = d.seat.ifBlank { seat }, gate = d.gate.ifBlank { gate }, bookingRef = d.bookingRef.ifBlank { bookingRef },
    notes = listOf(notes, d.notes, d.passenger.takeIf { it.isNotBlank() }?.let { "Passenger: $it" } ?: "").filter { it.isNotBlank() }.distinct().joinToString("\n"),
)

fun Lodging.applyDraft(d: BookingDraft) = copy(
    type = if (d.kind == "lodging") d.lodgingType else type,
    name = d.hotelName.ifBlank { name }, address = d.address.ifBlank { address },
    checkIn = d.checkIn ?: checkIn, checkOut = d.checkOut ?: checkOut,
    reservationNumber = d.bookingRef.ifBlank { reservationNumber },
    notes = listOf(notes, d.notes).filter { it.isNotBlank() }.distinct().joinToString("\n"),
)

@Composable
private fun rememberTickets(ownerType: String, ownerId: Long, loaded: (List<TicketFile>) -> Unit) {
    val app = LocalApp.current
    LaunchedEffect(ownerId) {
        if (ownerId != 0L) loaded(app.repo.attachmentsFor(ownerType, ownerId).map { TicketFile(it.filePath, it.name, it.mimeType, it) })
    }
}

@Composable
fun TransportEditor(nav: NavHostController, tripId: Long, id: Long) {
    val app = LocalApp.current
    var t by remember { mutableStateOf(Transport(tripId = tripId)) }
    var loaded by remember { mutableStateOf(id == 0L) }
    val files = remember { mutableStateListOf<TicketFile>() }
    val removed = remember { mutableStateListOf<Attachment>() }
    var defaultDate by remember { mutableStateOf<java.time.LocalDate?>(null) }
    LaunchedEffect(id) {
        defaultDate = app.repo.trip(tripId)?.startDate
        if (id != 0L) app.repo.transport(id)?.let { t = it }
        loaded = true
    }
    rememberTickets(OwnerType.TRANSPORT, id) { files.clear(); files.addAll(it) }
    if (!loaded) return
    val type = TransportType.of(t.type)

    EditorScaffold(
        title = if (id == 0L) "Add ${type.label.lowercase()}" else "Edit ${type.label.lowercase()}",
        onBack = { nav.popBackStack() },
        onSave = {
            app.appScope.launch {
                val newId = app.repo.saveTransport(t)
                saveTickets(app, tripId, OwnerType.TRANSPORT, if (id == 0L) newId else id, files, removed)
                withContext(Dispatchers.Main) { nav.popBackStack() }
            }
        },
        onDelete = if (id != 0L) ({ app.appScope.launch { app.repo.deleteTransport(t); withContext(Dispatchers.Main) { nav.popBackStack() } } }) else null,
    ) {
        ChipPicker(TransportType.entries, type, { "${it.emoji} ${it.label}" }) { t = t.copy(type = it.name) }
        TicketSection(tripId, "transport", files, removed) { d -> t = t.applyDraft(d) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field(if (type == TransportType.PLANE) "Airline" else "Operator", t.name, { t = t.copy(name = it) }, Modifier.weight(1.4f))
            Field(if (type == TransportType.PLANE) "Flight no." else "Number", t.flightNumber, { t = t.copy(flightNumber = it) }, Modifier.weight(1f), caps = KeyboardCapitalization.Characters)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field("From", t.fromLocation, { t = t.copy(fromLocation = it) }, Modifier.weight(1.4f))
            Field("Code", t.fromCode, { t = t.copy(fromCode = it.uppercase()) }, Modifier.weight(1f), caps = KeyboardCapitalization.Characters)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field("To", t.toLocation, { t = t.copy(toLocation = it) }, Modifier.weight(1.4f))
            Field("Code", t.toCode, { t = t.copy(toCode = it.uppercase()) }, Modifier.weight(1f), caps = KeyboardCapitalization.Characters)
        }
        DateTimeField("Departure", t.departure, { t = t.copy(departure = it) }, defaultDate = defaultDate)
        DateTimeField("Arrival", t.arrival, { t = t.copy(arrival = it) }, defaultDate = t.departure?.toLocalDate() ?: defaultDate)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field("Booking ref", t.bookingRef, { t = t.copy(bookingRef = it) }, Modifier.weight(1.3f), caps = KeyboardCapitalization.Characters)
            Field("Seat", t.seat, { t = t.copy(seat = it) }, Modifier.weight(1f), caps = KeyboardCapitalization.Characters)
            Field("Gate", t.gate, { t = t.copy(gate = it) }, Modifier.weight(1f), caps = KeyboardCapitalization.Characters)
        }
        Field("Link", t.link, { t = t.copy(link = it) }, caps = KeyboardCapitalization.None)
        Field("Notes", t.notes, { t = t.copy(notes = it) }, singleLine = false, minLines = 3)
    }
}

@Composable
fun LodgingEditor(nav: NavHostController, tripId: Long, id: Long) {
    val app = LocalApp.current
    var l by remember { mutableStateOf(Lodging(tripId = tripId)) }
    var loaded by remember { mutableStateOf(id == 0L) }
    val files = remember { mutableStateListOf<TicketFile>() }
    val removed = remember { mutableStateListOf<Attachment>() }
    var defaultDate by remember { mutableStateOf<java.time.LocalDate?>(null) }
    LaunchedEffect(id) {
        val trip = app.repo.trip(tripId)
        defaultDate = trip?.startDate
        if (id != 0L) app.repo.lodging(id)?.let { l = it }
        else if (trip?.startDate != null) l = l.copy(checkIn = trip.startDate.atTime(15, 0), checkOut = trip.endDate?.atTime(11, 0))
        loaded = true
    }
    rememberTickets(OwnerType.LODGING, id) { files.clear(); files.addAll(it) }
    if (!loaded) return
    val type = LodgingType.of(l.type)

    EditorScaffold(
        title = if (id == 0L) "Add stay" else "Edit stay",
        onBack = { nav.popBackStack() },
        saveEnabled = l.name.isNotBlank(),
        onSave = {
            app.appScope.launch {
                val newId = app.repo.saveLodging(l)
                saveTickets(app, tripId, OwnerType.LODGING, if (id == 0L) newId else id, files, removed)
                withContext(Dispatchers.Main) { nav.popBackStack() }
            }
        },
        onDelete = if (id != 0L) ({ app.appScope.launch { app.repo.deleteLodging(l); withContext(Dispatchers.Main) { nav.popBackStack() } } }) else null,
    ) {
        ChipPicker(LodgingType.entries, type, { "${it.emoji} ${it.label}" }) { l = l.copy(type = it.name) }
        TicketSection(tripId, "lodging", files, removed) { d -> l = l.applyDraft(d) }
        Field("Name", l.name, { l = l.copy(name = it) })
        Field("Address", l.address, { l = l.copy(address = it) })
        DateTimeField("Check-in", l.checkIn, { l = l.copy(checkIn = it) }, defaultDate = defaultDate)
        DateTimeField("Check-out", l.checkOut, { l = l.copy(checkOut = it) }, defaultDate = l.checkIn?.toLocalDate() ?: defaultDate)
        Field("Reservation number", l.reservationNumber, { l = l.copy(reservationNumber = it) }, caps = KeyboardCapitalization.Characters)
        Field("Link", l.link, { l = l.copy(link = it) }, caps = KeyboardCapitalization.None)
        RatingRow(l.rating) { l = l.copy(rating = it) }
        Field("Notes", l.notes, { l = l.copy(notes = it) }, singleLine = false, minLines = 3)
    }
}

@Composable
fun RatingRow(rating: Float?, onChange: (Float?) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        SmallLabel("Rating", Modifier.width(70.dp))
        (1..5).forEach { i ->
            Text(if ((rating ?: 0f) >= i) "★" else "☆", fontSize = 28.sp, color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.padding(horizontal = 2.dp).clickableNoRipple { onChange(if (rating == i.toFloat()) null else i.toFloat()) })
        }
    }
}

@Composable
private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier =
    clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
