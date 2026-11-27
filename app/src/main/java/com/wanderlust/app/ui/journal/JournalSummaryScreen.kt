package com.wanderlust.app.ui.journal

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import coil3.compose.AsyncImage
import com.wanderlust.app.LocalApp
import com.wanderlust.app.ai.AiException
import com.wanderlust.app.domain.JournalSummarizer
import com.wanderlust.app.ui.components.GlassCard
import com.wanderlust.app.ui.components.MarkdownText
import com.wanderlust.app.ui.components.SmallLabel
import com.wanderlust.app.ui.theme.Display
import com.wanderlust.app.ui.theme.Night
import com.wanderlust.app.ui.theme.palette
import kotlinx.coroutines.launch
import java.io.File

/** Travel-journal summary: stats, mood timeline, highlights, word cloud and an optional AI-written story. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun JournalSummaryScreen(nav: NavHostController, tripId: Long) {
    val app = LocalApp.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val full by remember(tripId) { app.repo.tripFull(tripId) }.collectAsState(initial = null)
    val settings by app.settings.state.collectAsState()
    var story by rememberSaveable { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val f = full ?: return
    val s = remember(f) { JournalSummarizer.summarize(f) }
    val p = palette(f.trip.themeIndex)

    Box(Modifier.fillMaxSize().background(Night)) {
        Box(Modifier.fillMaxWidth().height(380.dp).background(Brush.verticalGradient(listOf(p.colors.first().copy(alpha = 0.7f), Night))))
        LazyColumn(contentPadding = PaddingValues(bottom = 48.dp)) {
            item {
                Row(Modifier.statusBarsPadding().padding(8.dp).fillMaxWidth()) {
                    val c = IconButtonDefaults.iconButtonColors(containerColor = Color.Black.copy(alpha = 0.25f), contentColor = Color.White)
                    IconButton(onClick = { nav.popBackStack() }, colors = c) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = {
                        val text = buildString {
                            appendLine(s.headline); appendLine()
                            appendLine(story ?: s.narrative); appendLine()
                            s.stats.forEach { (k, v) -> appendLine("$k: $v") }
                        }
                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }, "Share journal summary"))
                    }, colors = c) { Icon(Icons.Default.Share, "Share") }
                }
            }
            item {
                Column(Modifier.padding(horizontal = 24.dp)) {
                    Text("JOURNAL SUMMARY", letterSpacing = 4.sp, fontSize = 12.sp, color = Color.White.copy(alpha = 0.8f))
                    Text(s.headline, fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 40.sp, color = Color.White)
                    Spacer(Modifier.height(12.dp))
                    Text(s.narrative, color = Color.White.copy(alpha = 0.9f))
                    Spacer(Modifier.height(20.dp))
                }
            }
            item {
                FlowRow(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp), maxItemsInEachRow = 3) {
                    s.stats.forEach { (label, value) ->
                        GlassCard(Modifier.weight(1f)) {
                            Column(Modifier.padding(12.dp)) {
                                Text(value, fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 24.sp, color = p.accent)
                                Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            if (s.moodTimeline.isNotEmpty()) item {
                SmallLabel("Mood journey", Modifier.padding(start = 24.dp, top = 24.dp, bottom = 8.dp))
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(s.moodTimeline) { (day, mood) ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.06f)).padding(10.dp)) {
                            Text(mood, fontSize = 28.sp)
                            Text(day, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            if (f.memories.isNotEmpty()) item {
                SmallLabel("Snapshots", Modifier.padding(start = 24.dp, top = 24.dp, bottom = 8.dp))
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(f.memories.take(12)) { m ->
                        AsyncImage(File(m.filePath), null, contentScale = ContentScale.Crop, modifier = Modifier.size(110.dp).clip(RoundedCornerShape(14.dp)))
                    }
                }
            }
            if (s.highlights.isNotEmpty()) item {
                SmallLabel("Highlights", Modifier.padding(start = 24.dp, top = 24.dp, bottom = 8.dp))
                s.highlights.forEach { h ->
                    GlassCard(Modifier.padding(horizontal = 20.dp, vertical = 4.dp).fillMaxWidth()) { Text(h, Modifier.padding(14.dp), fontSize = 14.sp) }
                }
            }
            if (s.categoryBreakdown.isNotEmpty()) item {
                SmallLabel("What you explored", Modifier.padding(start = 24.dp, top = 24.dp, bottom = 8.dp))
                val max = s.categoryBreakdown.maxOf { it.second }.toFloat()
                s.categoryBreakdown.forEach { (label, n) ->
                    Row(Modifier.padding(horizontal = 24.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(label, Modifier.width(150.dp), fontSize = 13.sp)
                        LinearProgressIndicator(progress = { n / max }, modifier = Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)), color = p.accent)
                        Text("  $n", fontSize = 12.sp)
                    }
                }
            }
            if (s.topWords.isNotEmpty()) item {
                SmallLabel("Words of the trip", Modifier.padding(start = 24.dp, top = 24.dp, bottom = 8.dp))
                FlowRow(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    s.topWords.forEachIndexed { i, w ->
                        Text(w, fontFamily = Display, fontSize = (22 - i * 1.5).sp, color = p.colors[i % p.colors.size],
                            modifier = Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.06f)).padding(horizontal = 12.dp, vertical = 6.dp))
                    }
                }
            }
            item {
                Spacer(Modifier.height(28.dp))
                GlassCard(Modifier.padding(horizontal = 20.dp).fillMaxWidth()) {
                    Column(Modifier.padding(18.dp)) {
                        Text("✨ Your trip as a story", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(6.dp))
                        when {
                            story != null -> MarkdownText(story!!)
                            !settings.aiEnabled -> Text("Add an Anthropic API key in Settings and AI will turn your journal into a beautifully written story.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                            f.journal.isEmpty() -> Text("Write a few journal entries first.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            else -> Text("AI reads your entries and writes a summary with highlights and a day-by-day recap.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                        }
                        error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp)) }
                        Spacer(Modifier.height(12.dp))
                        Button(
                            enabled = settings.aiEnabled && f.journal.isNotEmpty() && !loading,
                            onClick = {
                                loading = true; error = null
                                scope.launch {
                                    try { story = app.ai.journalStory(tripId) } catch (e: AiException) { error = e.message } finally { loading = false }
                                }
                            },
                        ) {
                            if (loading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White) else Icon(Icons.Default.AutoAwesome, null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (story == null) "Write my story" else "Rewrite")
                        }
                    }
                }
            }
        }
    }
}
