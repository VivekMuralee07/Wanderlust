package com.wanderlust.app.expenses

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import coil3.compose.AsyncImage
import com.wanderlust.app.LocalApp
import com.wanderlust.app.ai.AiException
import com.wanderlust.app.ai.SourceFile
import com.wanderlust.app.data.Expense
import com.wanderlust.app.data.ExpenseCategory
import com.wanderlust.app.data.TripFull
import com.wanderlust.app.domain.dayFmt
import com.wanderlust.app.domain.tripLengthDays
import com.wanderlust.app.ui.Routes
import com.wanderlust.app.ui.components.DateField
import com.wanderlust.app.ui.components.EmptyState
import com.wanderlust.app.ui.components.GlassCard
import com.wanderlust.app.ui.components.SmallLabel
import com.wanderlust.app.ui.editors.ChipPicker
import com.wanderlust.app.ui.editors.EditorScaffold
import com.wanderlust.app.ui.editors.Field
import com.wanderlust.app.ui.theme.Coral
import com.wanderlust.app.ui.theme.Display
import com.wanderlust.app.ui.theme.Gold
import com.wanderlust.app.ui.theme.Teal
import com.wanderlust.app.ui.theme.palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

/** Trip "Expenses" tab: total vs budget, by category, and every expense by day. */
@Composable
fun ExpensesTab(f: TripFull, nav: NavHostController) {
    val app = LocalApp.current
    val settings by app.settings.state.collectAsState()
    val home = settings.homeCurrency
    val p = palette(f.trip.themeIndex)
    var editBudget by remember { mutableStateOf(false) }
    // expenses saved before a home-currency change are shown in their own home currency's sum
    val inHome = f.expenses.filter { it.homeCurrency == home }
    val total = inHome.sumOf { it.amountHome }
    val budget = f.trip.budget
    val days = tripLengthDays(f.trip.startDate, f.trip.endDate).coerceAtLeast(1)

    Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp)) {
                SmallLabel("Spent so far")
                Text(ExchangeRates.format(total, home), fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 34.sp, color = p.accent)
                Text("≈ ${ExchangeRates.format(total / days, home)} per day · ${f.expenses.size} expense${if (f.expenses.size == 1) "" else "s"}",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                if (budget != null && budget > 0) {
                    val frac = (total / budget).toFloat()
                    LinearProgressIndicator(progress = { frac.coerceIn(0f, 1f) }, color = if (frac > 1f) Coral else if (frac > 0.85f) Gold else Teal,
                        modifier = Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)))
                    Row(Modifier.padding(top = 6.dp)) {
                        Text(if (frac > 1f) "Over budget by ${ExchangeRates.format(total - budget, home)}" else "${ExchangeRates.format(budget - total, home)} left",
                            fontSize = 13.sp, color = if (frac > 1f) Coral else Color.White, modifier = Modifier.weight(1f))
                        Text("Budget ${ExchangeRates.format(budget, home)}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                TextButton(onClick = { editBudget = true }) { Text(if (budget == null) "Set a budget" else "Change budget") }
            }
        }
        if (inHome.isNotEmpty()) {
            GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SmallLabel("By category")
                    val byCat = inHome.groupBy { ExpenseCategory.of(it.category) }.mapValues { (_, v) -> v.sumOf { it.amountHome } }.entries.sortedByDescending { it.value }
                    val max = byCat.maxOf { it.value }
                    byCat.forEach { (c, v) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${c.emoji} ${c.label}", Modifier.width(130.dp), fontSize = 13.sp, maxLines = 1)
                            LinearProgressIndicator(progress = { (v / max).toFloat() }, color = p.accent, modifier = Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)))
                            Text("  " + ExchangeRates.format(v, home), fontSize = 12.sp)
                        }
                    }
                }
            }
        }
        if (f.expenses.isEmpty()) EmptyState("💸", "No expenses yet", "Track what you spend — snap a receipt and AI fills in the amount, currency and category.")
        f.expenses.groupBy { it.date }.toSortedMap(compareByDescending { it }).forEach { (date, list) ->
            Row { SmallLabel(date.format(dayFmt), Modifier.weight(1f)); Text(ExchangeRates.format(list.filter { it.homeCurrency == home }.sumOf { it.amountHome }, home), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            list.forEach { e -> ExpenseRow(e) { nav.navigate(Routes.expense(f.trip.id, e.id)) } }
        }
        Text("Exchange rates by ExchangeRate-API (open.er-api.com)", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    if (editBudget) {
        var text by remember { mutableStateOf(budget?.let { "%.0f".format(it) } ?: "") }
        AlertDialog(
            onDismissRequest = { editBudget = false },
            title = { Text("Trip budget") },
            text = {
                OutlinedTextField(text, { text = it.filter { c -> c.isDigit() || c == '.' } }, label = { Text("Budget in $home") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            },
            confirmButton = {
                TextButton(onClick = {
                    app.appScope.launch { app.repo.saveTrip(f.trip.copy(budget = text.toDoubleOrNull()?.takeIf { it > 0 })) }
                    editBudget = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editBudget = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ExpenseRow(e: Expense, onClick: () -> Unit) {
    val c = ExpenseCategory.of(e.category)
    GlassCard(Modifier.fillMaxWidth(), corner = 18.dp, onClick = onClick) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.08f)), contentAlignment = Alignment.Center) { Text(c.emoji, fontSize = 20.sp) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(e.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(c.label + if (e.receiptPath != null) " · 🧾" else "", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(ExchangeRates.format(e.amount, e.currency), fontWeight = FontWeight.Bold)
                if (e.currency != e.homeCurrency) Text("≈ " + ExchangeRates.format(e.amountHome, e.homeCurrency), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Add / edit an expense, with live conversion to the home currency and AI receipt scanning. */
@Composable
fun ExpenseEditor(nav: NavHostController, tripId: Long, id: Long) {
    val app = LocalApp.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by app.settings.state.collectAsState()
    val home = settings.homeCurrency
    val prefs = remember { context.getSharedPreferences("wanderlust_fx", android.content.Context.MODE_PRIVATE) }
    var e by remember { mutableStateOf<Expense?>(null) }
    var amountText by remember { mutableStateOf("") }
    var homeText by remember { mutableStateOf("") }
    var homeEdited by remember { mutableStateOf(false) }
    var rateNote by remember { mutableStateOf("") }
    var currencyMenu by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }

    LaunchedEffect(id) {
        val existing = if (id != 0L) app.repo.expense(id) else null
        val trip = app.repo.trip(tripId)
        val today = LocalDate.now()
        val date = if (trip?.startDate != null && (today.isBefore(trip.startDate) || (trip.endDate != null && today.isAfter(trip.endDate)))) trip.startDate else today
        e = existing ?: Expense(tripId = tripId, title = "", amount = 0.0, currency = prefs.getString("last_ccy_$tripId", home) ?: home,
            amountHome = 0.0, homeCurrency = home, category = ExpenseCategory.FOOD.name, date = date)
        existing?.let { amountText = "%.2f".format(it.amount).trimEnd('0').trimEnd('.'); homeText = "%.2f".format(it.amountHome); homeEdited = true }
    }
    val ex = e ?: return

    // live conversion
    LaunchedEffect(amountText, ex.currency, home) {
        val amt = amountText.toDoubleOrNull() ?: return@LaunchedEffect
        if (ex.currency == home) { homeText = "%.2f".format(amt); rateNote = ""; return@LaunchedEffect }
        if (homeEdited && id != 0L && ex.currency == e?.currency && amt == ex.amount) return@LaunchedEffect
        val converted = app.fx.convert(amt, ex.currency, home)
        if (converted != null) {
            homeText = "%.2f".format(converted); homeEdited = false
            rateNote = "1 ${ex.currency} ≈ ${"%.4f".format(converted / amt)} $home"
        } else rateNote = "No exchange rate available offline — enter the $home amount yourself"
    }

    val receiptPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val stored = app.files.import(uri, "attachments") ?: return@launch
            ex.receiptPath?.let { File(it).delete() }
            e = ex.copy(receiptPath = stored.path)
            if (settings.aiEnabled) {
                scanning = true
                try {
                    val r = app.ai.extractReceipt(SourceFile(stored.path, stored.mimeType, stored.name))
                    e = e!!.copy(
                        title = r.merchant.ifBlank { ex.title }, category = r.category,
                        currency = r.currency.takeIf { it.length == 3 } ?: ex.currency, date = r.date ?: ex.date,
                        notes = listOf(ex.notes, r.notes).filter { it.isNotBlank() }.joinToString("\n"),
                    )
                    if (r.total > 0) amountText = "%.2f".format(r.total).trimEnd('0').trimEnd('.')
                    Toast.makeText(context, "Read your receipt ✨ — please double-check", Toast.LENGTH_SHORT).show()
                } catch (err: AiException) {
                    Toast.makeText(context, err.message, Toast.LENGTH_LONG).show()
                } finally { scanning = false }
            }
        }
    }

    val amount = amountText.toDoubleOrNull()
    val homeAmount = homeText.toDoubleOrNull()
    EditorScaffold(
        title = if (id == 0L) "Add expense" else "Edit expense",
        onBack = { nav.popBackStack() },
        saveEnabled = amount != null && amount > 0 && homeAmount != null,
        onSave = {
            prefs.edit().putString("last_ccy_$tripId", ex.currency).apply()
            val saved = ex.copy(title = ex.title.ifBlank { ExpenseCategory.of(ex.category).label }, amount = amount!!, amountHome = homeAmount!!, homeCurrency = home)
            app.appScope.launch { app.repo.saveExpense(saved); withContext(Dispatchers.Main) { nav.popBackStack() } }
        },
        onDelete = if (id != 0L) ({ app.appScope.launch { app.repo.deleteExpense(ex); withContext(Dispatchers.Main) { nav.popBackStack() } } }) else null,
    ) {
        GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalButton(onClick = { receiptPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, enabled = !scanning) {
                        if (scanning) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Icon(if (settings.aiEnabled) Icons.Default.AutoAwesome else Icons.Default.AddPhotoAlternate, null)
                        Spacer(Modifier.width(6.dp)); Text(if (settings.aiEnabled) "Scan receipt (AI)" else "Attach receipt")
                    }
                    if (scanning) Text("Reading…", fontSize = 13.sp)
                }
                ex.receiptPath?.let { path ->
                    AsyncImage(File(path), "Receipt", contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxWidth().height(140.dp).clip(RoundedCornerShape(14.dp)))
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(amountText, { amountText = it.filter { c -> c.isDigit() || c == '.' || c == ',' }.replace(',', '.') },
                label = { Text("Amount") }, singleLine = true, modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            Box {
                OutlinedButton(onClick = { currencyMenu = true }, modifier = Modifier.height(56.dp)) { Text(ex.currency) }
                DropdownMenu(expanded = currencyMenu, onDismissRequest = { currencyMenu = false }) {
                    (listOf(home) + ExchangeRates.COMMON).distinct().forEach { code ->
                        DropdownMenuItem(text = { Text("$code · ${ExchangeRates.name(code)}") }, onClick = { e = ex.copy(currency = code); currencyMenu = false })
                    }
                }
            }
        }
        if (ex.currency != home) {
            OutlinedTextField(homeText, { homeText = it.filter { c -> c.isDigit() || c == '.' }; homeEdited = true },
                label = { Text("In $home") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            if (rateNote.isNotBlank()) Text(rateNote, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Field("What was it?", ex.title, { e = ex.copy(title = it) })
        SmallLabel("Category")
        ChipPicker(ExpenseCategory.entries, ExpenseCategory.of(ex.category), { "${it.emoji} ${it.label}" }) { e = ex.copy(category = it.name) }
        DateField("Date", ex.date, { it?.let { d -> e = ex.copy(date = d) } })
        Field("Notes", ex.notes, { e = ex.copy(notes = it) }, singleLine = false, minLines = 2)
    }
}
