package com.wanderlust.app.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.wanderlust.app.LocalApp
import com.wanderlust.app.data.AppSettings
import com.wanderlust.app.ui.components.GlassCard
import com.wanderlust.app.ui.components.SmallLabel
import com.wanderlust.app.ui.theme.Night

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(nav: NavHostController) {
    val app = LocalApp.current
    val context = LocalContext.current
    val s by app.settings.state.collectAsState()
    var name by remember { mutableStateOf(s.travelerName) }
    var home by remember { mutableStateOf(s.homeCity) }
    var key by remember { mutableStateOf(s.anthropicApiKey) }
    var model by remember { mutableStateOf(s.model) }
    var showKey by remember { mutableStateOf(false) }

    fun save() {
        app.settings.update { it.copy(travelerName = name.trim(), homeCity = home.trim(), anthropicApiKey = key.trim(), model = model) }
        nav.popBackStack()
    }

    Scaffold(
        containerColor = Night,
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = { TextButton(onClick = ::save) { Text("Save") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Night),
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SmallLabel("You")
            OutlinedTextField(name, { name = it }, label = { Text("Your name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(home, { home = it }, label = { Text("Home city") }, modifier = Modifier.fillMaxWidth(), singleLine = true)

            SmallLabel("AI travel assistant (Claude)")
            GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text("Wanderlust uses Claude by Anthropic for the travel assistant, reading tickets, and writing journal stories. " +
                        "Bring your own API key — it’s stored only on this device and sent only to api.anthropic.com.",
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://console.anthropic.com/settings/keys"))) }) {
                        Text("Get an API key")
                    }
                }
            }
            OutlinedTextField(
                key, { key = it }, label = { Text("Anthropic API key") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = { IconButton(onClick = { showKey = !showKey }) { Icon(if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility, "Show") } },
            )
            SmallLabel("Model")
            AppSettings.MODELS.forEach { (id, label) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = model == id, onClick = { model = id })
                    Text(label)
                }
            }

            SmallLabel("App updates")
            com.wanderlust.app.update.UpdateSettingsCard()

            SmallLabel("Google Photos")
            Text(
                "Google’s Photos Library API no longer lets apps read your whole library, so Wanderlust uses the Android photo picker " +
                    "(which shows your Google Photos cloud library on supported devices) and the Share sheet: in Google Photos, select photos → Share → Wanderlust.",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SmallLabel("About")
            Text("Wanderlust 1.0 · Features inspired by the open-source AdventureLog project. Maps © OpenStreetMap contributors.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
