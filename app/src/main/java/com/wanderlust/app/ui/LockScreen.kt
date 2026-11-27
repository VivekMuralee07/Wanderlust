package com.wanderlust.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wanderlust.app.ui.theme.Coral
import com.wanderlust.app.ui.theme.Display
import com.wanderlust.app.ui.theme.Night
import com.wanderlust.app.ui.theme.Violet

/** Covers the whole app until the user unlocks with fingerprint/face/PIN. */
@Composable
fun LockScreen(onUnlock: () -> Unit) {
    Box(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(Violet.copy(alpha = 0.6f), Night, Night)))
            // swallow touches so nothing underneath can be used
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Box(Modifier.size(96.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Violet, Coral))), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Lock, null, tint = Color.White, modifier = Modifier.size(44.dp))
            }
            Spacer(Modifier.height(20.dp))
            Text("Wanderlust is locked", fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 26.sp)
            Spacer(Modifier.height(6.dp))
            Text("Your tickets and documents are protected", color = Color.White.copy(alpha = 0.75f))
            Spacer(Modifier.height(24.dp))
            Button(onClick = onUnlock) { Text("Unlock") }
        }
    }
}
