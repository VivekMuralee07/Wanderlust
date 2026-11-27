package com.wanderlust.app.ui.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.fragment.app.FragmentActivity
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import com.wanderlust.app.LocalApp
import com.wanderlust.app.MainActivity
import com.wanderlust.app.expenses.ExchangeRates
import com.wanderlust.app.ui.components.GlassCard
import com.wanderlust.app.ui.components.SmallLabel
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** Home currency, reminders, app lock. Changes apply immediately. */
@Composable
fun PreferencesCard() {
    val app = LocalApp.current
    val context = LocalContext.current
    val activity = LocalActivity.current as? FragmentActivity
    val s by app.settings.state.collectAsState()
    var currencyMenu by remember { mutableStateOf(false) }
    val notificationsOn = NotificationManagerCompat.from(context).areNotificationsEnabled()

    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Home currency")
                    Text("Expenses and budgets are totalled in this currency", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Box {
                    OutlinedButton(onClick = { currencyMenu = true }) { Text(s.homeCurrency) }
                    DropdownMenu(expanded = currencyMenu, onDismissRequest = { currencyMenu = false }) {
                        ExchangeRates.COMMON.forEach { c ->
                            DropdownMenuItem(text = { Text("$c · ${ExchangeRates.name(c)}") }, onClick = { app.settings.update { it.copy(homeCurrency = c) }; currencyMenu = false })
                        }
                    }
                }
            }
            ToggleRow("Trip reminders", "Countdown, flight check-in, leave-for-airport and hotel check-in/out notifications", s.remindersEnabled) { on ->
                app.settings.update { it.copy(remindersEnabled = on) }
            }
            if (s.remindersEnabled && !notificationsOn) {
                TextButton(onClick = {
                    context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                }) { Text("Notifications are off for Wanderlust — turn them on") }
            }
            ToggleRow("App lock", "Ask for fingerprint, face or screen PIN when opening Wanderlust", s.appLockEnabled) { on ->
                if (!on) { app.settings.update { it.copy(appLockEnabled = false) }; return@ToggleRow }
                val act = activity ?: return@ToggleRow
                if (!MainActivity.canLock(act)) {
                    Toast.makeText(context, "Set up a screen lock or fingerprint on your phone first", Toast.LENGTH_LONG).show(); return@ToggleRow
                }
                // confirm the user can unlock before turning it on
                BiometricPrompt(act, ContextCompat.getMainExecutor(act), object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        app.settings.update { it.copy(appLockEnabled = true) }
                        Toast.makeText(context, "App lock is on 🔒", Toast.LENGTH_SHORT).show()
                    }
                }).authenticate(MainActivity.promptInfo())
            }
        }
    }
}

/** Manual backup/restore and automatic backups. */
@Composable
fun BackupCard() {
    val app = LocalApp.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val backups = app.backups
    var busy by remember { mutableStateOf<String?>(null) }
    var confirmRestore by remember { mutableStateOf<Uri?>(null) }
    var days by remember { mutableIntStateOf(backups.autoBackupDays) }
    var refresh by remember { mutableIntStateOf(0) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) {
            busy = "Backing up…"
            scope.launch {
                runCatching { backups.exportTo(uri) }
                    .onSuccess { Toast.makeText(context, "Backup saved ✅", Toast.LENGTH_SHORT).show() }
                    .onFailure { Toast.makeText(context, "Backup failed: ${it.message}", Toast.LENGTH_LONG).show() }
                busy = null; refresh++
            }
        }
    }
    val autoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) {
            val d = if (days == 0) 7 else days
            backups.configureAuto(uri, d); days = d
            busy = "Backing up…"
            scope.launch { backups.runAutoBackup(); busy = null; refresh++ }
        }
    }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) confirmRestore = uri }

    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Everything lives on this phone. A backup is one file with all trips, bookings, tickets, journals, expenses and photos " +
                "(not your API key). Save it to Google Drive or anywhere safe.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { exportLauncher.launch(backups.suggestedName()) }, enabled = busy == null) { Text("Back up now") }
                OutlinedButton(onClick = { restoreLauncher.launch(arrayOf("application/zip", "application/octet-stream")) }, enabled = busy == null) { Text("Restore") }
            }
            busy?.let { Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.padding(end = 8.dp), strokeWidth = 2.dp); Text(it) } }
            refresh.let { _ ->
                if (backups.lastBackupAt > 0) Text("Last backup: ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(backups.lastBackupAt))}", fontSize = 12.sp)
                backups.lastError?.let { Text("Last automatic backup failed: $it", fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
            }
            SmallLabel("Automatic backup")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0 to "Off", 1 to "Daily", 7 to "Weekly").forEach { (d, label) ->
                    FilterChip(selected = days == d, label = { Text(label) }, onClick = {
                        if (d != 0 && backups.autoBackupUri == null) { days = d; autoLauncher.launch("Wanderlust-auto-backup.zip") }
                        else { days = d; backups.configureAuto(null, d) }
                    })
                }
            }
            if (days != 0) {
                Text("Overwrites the same file each time" + (backups.autoBackupUri?.let { " · ${it.lastPathSegment?.substringAfterLast('/') ?: "chosen file"}" } ?: ""),
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { autoLauncher.launch("Wanderlust-auto-backup.zip") }) { Text("Change backup file") }
            }
            Text("Android’s own Google backup also saves your trips and tickets (photos excluded) if it’s on in your phone’s settings.",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    confirmRestore?.let { uri ->
        AlertDialog(
            onDismissRequest = { confirmRestore = null },
            title = { Text("Restore this backup?") },
            text = { Text("This replaces ALL data currently in Wanderlust with the backup, then restarts the app. Your API key stays as it is.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRestore = null; busy = "Restoring…"
                    scope.launch {
                        runCatching { backups.restoreFrom(uri) }
                            .onSuccess { backups.restartApp() }
                            .onFailure { busy = null; Toast.makeText(context, it.message ?: "Restore failed", Toast.LENGTH_LONG).show() }
                    }
                }) { Text("Replace & restore") }
            },
            dismissButton = { TextButton(onClick = { confirmRestore = null }) { Text("Cancel") } },
        )
    }
}

