package com.wanderlust.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wanderlust.app.ui.theme.Display

/** Minimal Markdown renderer for AI answers: headings, bullets, numbered lists, **bold**, *italic*, `code`. */
@Composable
fun MarkdownText(md: String, modifier: Modifier = Modifier, color: Color = Color.White) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        md.lines().forEach { raw ->
            val line = raw.trimEnd()
            when {
                line.isBlank() -> Text("", fontSize = 4.sp)
                line.startsWith("# ") -> Text(inline(line.drop(2)), fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 26.sp, color = color)
                line.startsWith("## ") -> Text(inline(line.drop(3)), fontFamily = Display, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, color = color, modifier = Modifier.padding(top = 6.dp))
                line.startsWith("### ") -> Text(inline(line.drop(4)), fontWeight = FontWeight.Bold, fontSize = 16.sp, color = color)
                line.trimStart().startsWith("- ") || line.trimStart().startsWith("* ") -> Row(Modifier.padding(start = (line.length - line.trimStart().length).coerceAtMost(8).dp * 2)) {
                    Text("•", color = MaterialTheme.colorScheme.secondary, modifier = Modifier.width(16.dp))
                    Text(inline(line.trimStart().drop(2)), color = color)
                }
                Regex("^\\s*\\d+[.)] ").containsMatchIn(line) -> Row {
                    val num = line.trimStart().substringBefore(' ')
                    Text(num, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.width(24.dp))
                    Text(inline(line.trimStart().substringAfter(' ')), color = color)
                }
                else -> Text(inline(line), color = color)
            }
        }
    }
}

private fun inline(s: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < s.length) {
        when {
            s.startsWith("**", i) && s.indexOf("**", i + 2) > 0 -> {
                val end = s.indexOf("**", i + 2)
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(s.substring(i + 2, end)) }
                i = end + 2
            }
            s[i] == '`' && s.indexOf('`', i + 1) > 0 -> {
                val end = s.indexOf('`', i + 1)
                withStyle(SpanStyle(background = Color.White.copy(alpha = 0.1f))) { append(s.substring(i + 1, end)) }
                i = end + 1
            }
            s[i] == '*' && i + 1 < s.length && s[i + 1] != ' ' && s.indexOf('*', i + 1) > 0 -> {
                val end = s.indexOf('*', i + 1)
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(s.substring(i + 1, end)) }
                i = end + 1
            }
            s[i] == '[' && s.indexOf("](", i) > 0 && s.indexOf(')', s.indexOf("](", i)) > 0 -> {
                // [label](url) -> label
                val mid = s.indexOf("](", i)
                val end = s.indexOf(')', mid)
                withStyle(SpanStyle(color = Color(0xFF9BF6FF))) { append(s.substring(i + 1, mid)) }
                i = end + 1
            }
            else -> { append(s[i]); i++ }
        }
    }
}
