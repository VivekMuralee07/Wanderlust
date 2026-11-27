package com.wanderlust.app.update

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wanderlust.app.LocalApp
import com.wanderlust.app.ui.components.GlassCard
import com.wanderlust.app.ui.components.MarkdownText
import com.wanderlust.app.ui.theme.Teal
import com.wanderlust.app.ui.theme.Violet
import kotlinx.coroutines.launch

/** The action button for the current update state (download / allow installs / install / Play update). */
@Composable
private fun UpdateActions(state: UpdateState) {
    val app = LocalApp.current
    val updater = app.updater
    val activity = LocalActivity.current
    when (state) {
        is UpdateState.Available -> Button(onClick = { app.appScope.launch { updater.download(state.update) } }) {
            Text("Download" + if (state.update.apkSize > 0) " (${state.update.apkSize / (1024 * 1024)} MB)" else "")
        }
        is UpdateState.Downloading -> Column(Modifier.fillMaxWidth()) {
            LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)), color = Teal)
            Text("Downloading… ${(state.progress * 100).toInt()}%", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        }
        is UpdateState.ReadyToInstall -> {
            // Android requires the user to allow "Install unknown apps" for Wanderlust once.
            var allowed by remember { mutableStateOf(updater.canInstall()) }
            if (!allowed) {
                Column {
                    Text("Android needs your permission for Wanderlust to install updates (one time only).", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                        OutlinedButton(onClick = { activity?.let { updater.openInstallPermissionSettings(it) } }) { Text("Allow installs") }
                        Button(onClick = { allowed = updater.canInstall(); if (allowed) activity?.let { updater.install(it, state.apk) } }) { Text("Install") }
                    }
                }
            } else {
                Button(onClick = { activity?.let { updater.install(it, state.apk) } }) { Text("Install update") }
            }
        }
        is UpdateState.PlayUpdateAvailable -> Button(onClick = { activity?.let { updater.startPlayUpdate(it) } }) { Text("Update via Google Play") }
        else -> Unit
    }
}

/** Compact banner shown on the home screen when an update is available. */
@Composable
fun UpdateBanner(modifier: Modifier = Modifier) {
    val updater = LocalApp.current.updater
    val state by updater.state.collectAsState()
    if (!updater.shouldShowBanner(state)) return
    val version = when (state) {
        is UpdateState.Available -> (state as UpdateState.Available).update.versionName
        is UpdateState.Downloading -> (state as UpdateState.Downloading).update.versionName
        is UpdateState.ReadyToInstall -> (state as UpdateState.ReadyToInstall).update.versionName
        else -> null
    }
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp))
            .background(Brush.linearGradient(listOf(Violet.copy(alpha = 0.55f), Teal.copy(alpha = 0.45f))))
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.SystemUpdate, null, tint = Color.White)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Update available" + (version?.let { " · v$it" } ?: ""), fontWeight = FontWeight.Bold, color = Color.White)
                Text("New features and fixes for Wanderlust", fontSize = 12.sp, color = Color.White.copy(alpha = 0.85f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (state is UpdateState.Available) IconButton(onClick = { updater.dismissBanner() }) { Icon(Icons.Default.Close, "Not now", tint = Color.White) }
        }
        Row(Modifier.padding(top = 6.dp, end = 12.dp)) { UpdateActions(state) }
    }
}

/** Full "App updates" section for the Settings screen. */
@Composable
fun UpdateSettingsCard() {
    val app = LocalApp.current
    val updater = app.updater
    val state by updater.state.collectAsState()
    var auto by remember { mutableStateOf(updater.autoCheck) }

    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Wanderlust ${updater.currentVersion}", fontWeight = FontWeight.SemiBold)
                    Text(
                        when {
                            updater.isDebugBuild -> "Debug build — in-app updates are for release builds"
                            updater.installedFromPlay -> "Updates via Google Play"
                            else -> "Updates from GitHub Releases"
                        },
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(
                    enabled = state !is UpdateState.Checking && state !is UpdateState.Downloading,
                    onClick = { app.appScope.launch { updater.check() } },
                ) {
                    if (state is UpdateState.Checking) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Text("Check now")
                }
            }
            when (val s = state) {
                is UpdateState.UpToDate -> Text("✅ You’re on the latest version.", fontSize = 13.sp)
                is UpdateState.Error -> Text(s.message, fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                is UpdateState.Available -> {
                    Text("🎉 Version ${s.update.versionName} is available", fontWeight = FontWeight.SemiBold)
                    if (s.update.notes.isNotBlank()) MarkdownText(s.update.notes.take(1500))
                }
                is UpdateState.ReadyToInstall -> Text("Version ${s.update.versionName} downloaded and verified.", fontSize = 13.sp)
                is UpdateState.PlayUpdateAvailable -> Text("🎉 A new version is available on Google Play.", fontWeight = FontWeight.SemiBold)
                else -> Unit
            }
            UpdateActions(state)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Check for updates automatically", Modifier.weight(1f), fontSize = 14.sp)
                Switch(checked = auto, onCheckedChange = { auto = it; updater.autoCheck = it })
            }
        }
    }
}
