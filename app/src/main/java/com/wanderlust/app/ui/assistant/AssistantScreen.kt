package com.wanderlust.app.ui.assistant

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.anthropic.models.messages.MessageParam
import com.wanderlust.app.AppContainer
import com.wanderlust.app.LocalApp
import com.wanderlust.app.ai.AiException
import com.wanderlust.app.data.ChatMessageEntity
import com.wanderlust.app.ui.components.EmptyState
import com.wanderlust.app.ui.components.MarkdownText
import com.wanderlust.app.ui.theme.Coral
import com.wanderlust.app.ui.theme.Display
import com.wanderlust.app.ui.theme.Night
import com.wanderlust.app.ui.theme.Violet
import kotlinx.coroutines.launch

data class ChatMessage(val fromUser: Boolean, val text: String, val isError: Boolean = false)

/** Keeps the conversation (including tool calls) alive across configuration changes and tab switches. */
class AssistantViewModel : ViewModel() {
    val messages = mutableStateListOf<ChatMessage>()
    private val history = mutableListOf<MessageParam>()
    var busy by mutableStateOf(false); private set
    var status by mutableStateOf(""); private set
    /** Text of the answer while it is being streamed. */
    var streaming by mutableStateOf(""); private set
    private var loaded = false

    /** Restores the saved conversation (plain text; tool calls from earlier sessions aren't replayed). */
    fun load(app: AppContainer) {
        if (loaded) return
        loaded = true
        viewModelScope.launch {
            val saved = app.repo.chatMessages()
            messages.clear()
            messages += saved.map { ChatMessage(it.fromUser, it.text, it.isError) }
            history.clear()
            // last 30 turns, alternating user/assistant, errors skipped
            saved.filter { !it.isError }.takeLast(30).forEach { m ->
                history += if (m.fromUser) MessageParam.ofUser(m.text) else MessageParam.ofAssistant(m.text)
            }
            while (history.isNotEmpty() && history.first().isAssistant()) history.removeAt(0)
        }
    }

    fun send(app: AppContainer, text: String) {
        if (text.isBlank() || busy) return
        messages += ChatMessage(true, text)
        busy = true; status = "Thinking…"; streaming = ""
        val snapshot = history.toMutableList()
        viewModelScope.launch {
            app.repo.addChat(ChatMessageEntity(fromUser = true, text = text))
            try {
                val reply = app.ai.chat(snapshot, text, onStatus = { s -> status = s; streaming = "" }, onText = { t -> streaming = t })
                history.clear(); history.addAll(snapshot)
                messages += ChatMessage(false, reply)
                app.repo.addChat(ChatMessageEntity(fromUser = false, text = reply))
            } catch (e: AiException) {
                val msg = e.message ?: "Something went wrong"
                messages += ChatMessage(false, msg, isError = true)
                app.repo.addChat(ChatMessageEntity(fromUser = false, text = msg, isError = true))
            } finally { busy = false; streaming = "" }
        }
    }

    fun clear(app: AppContainer) {
        messages.clear(); history.clear()
        viewModelScope.launch { app.repo.clearChat() }
    }
}

private val suggestions = listOf(
    "What should I pack for my next trip?",
    "Do I need a visa for my upcoming trip?",
    "Plan a perfect first day at my destination and add it to my itinerary",
    "Hidden-gem restaurants near my hotel",
    "How early should I get to the airport for my flight?",
    "What's the weather usually like when I'm travelling?",
)

@Composable
fun AssistantScreen(vm: AssistantViewModel = viewModel()) {
    val app = LocalApp.current
    val settings by app.settings.state.collectAsState()
    var input by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    val list = rememberLazyListState()
    LaunchedEffect(Unit) { vm.load(app) }
    LaunchedEffect(vm.messages.size, vm.busy, vm.streaming.length / 200) { if (vm.messages.isNotEmpty()) list.animateScrollToItem(list.layoutInfo.totalItemsCount.coerceAtLeast(1) - 1) }

    Column(Modifier.fillMaxSize().background(Night).imePadding()) {
        Row(Modifier.statusBarsPadding().padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Violet, Coral))), contentAlignment = Alignment.Center) { Text("✨", fontSize = 20.sp) }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text("Travel assistant", fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                Text("Knows your trips · searches the web · can plan for you", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (vm.messages.isNotEmpty()) IconButton(onClick = { vm.clear(app) }) { Icon(Icons.Default.DeleteSweep, "New chat") }
        }
        LazyColumn(Modifier.weight(1f), state = list, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (vm.messages.isEmpty()) {
                item {
                    if (!settings.aiEnabled) EmptyState("🔑", "Connect Claude", "Add your Anthropic API key in Settings (Home → ⚙️) to chat with your AI travel agent.")
                    else EmptyState("🧭", "Ask me anything about travel", "I can see your trips, look things up on the web, and add plans, places and packing items for you.")
                }
                items(suggestions) { s ->
                    Text(s, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.06f))
                        .clickable(enabled = settings.aiEnabled) { vm.send(app, s) }.padding(14.dp), fontSize = 14.sp)
                }
            }
            items(vm.messages) { m -> Bubble(m) }
            if (vm.busy && vm.streaming.isNotBlank()) item { Bubble(ChatMessage(false, vm.streaming)) }
            if (vm.busy) item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text("  ${vm.status}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                }
            }
        }
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                input, { input = it }, modifier = Modifier.weight(1f), placeholder = { Text("Ask about visas, food, routes…") },
                maxLines = 4, shape = RoundedCornerShape(24.dp), enabled = settings.aiEnabled,
            )
            Spacer(Modifier.size(8.dp))
            FilledIconButton(onClick = { vm.send(app, input.trim()); input = "" }, enabled = input.isNotBlank() && !vm.busy && settings.aiEnabled, modifier = Modifier.size(52.dp)) {
                Icon(Icons.AutoMirrored.Filled.Send, "Send")
            }
        }
    }
}

@Composable
private fun Bubble(m: ChatMessage) {
    Box(Modifier.fillMaxWidth(), contentAlignment = if (m.fromUser) Alignment.CenterEnd else Alignment.CenterStart) {
        val shape = RoundedCornerShape(20.dp, 20.dp, if (m.fromUser) 4.dp else 20.dp, if (m.fromUser) 20.dp else 4.dp)
        Box(
            Modifier.widthIn(max = 320.dp).clip(shape)
                .background(
                    when {
                        m.isError -> Brush.linearGradient(listOf(Color(0xFF5A1F2B), Color(0xFF5A1F2B)))
                        m.fromUser -> Brush.linearGradient(listOf(Coral, Violet))
                        else -> Brush.linearGradient(listOf(Color.White.copy(alpha = 0.09f), Color.White.copy(alpha = 0.06f)))
                    }
                ).padding(14.dp)
        ) {
            if (m.fromUser) Text(m.text, color = Color.White) else MarkdownText(m.text)
        }
    }
    Spacer(Modifier.height(2.dp))
}
