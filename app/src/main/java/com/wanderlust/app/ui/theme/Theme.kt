package com.wanderlust.app.ui.theme

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Night = Color(0xFF0B1026)
val NightRaised = Color(0xFF141B3A)
val NightCard = Color(0xFF1B2347)
val Coral = Color(0xFFFF6B6B)
val Gold = Color(0xFFFFC857)
val Teal = Color(0xFF2EC4B6)
val Violet = Color(0xFF7B61FF)
val Sky = Color(0xFF9BF6FF)
val Mist = Color(0xFFB8C0E0)

data class TripPalette(val name: String, val colors: List<Color>, val accent: Color) {
    val brush get() = Brush.linearGradient(colors)
    val vertical get() = Brush.verticalGradient(colors)
}

val TripPalettes = listOf(
    TripPalette("Sunset", listOf(Color(0xFFFF6B6B), Color(0xFFFF8E53), Color(0xFFFFC857)), Color(0xFFFF6B6B)),
    TripPalette("Ocean", listOf(Color(0xFF0F4C81), Color(0xFF2EC4B6), Color(0xFF9BF6FF)), Color(0xFF2EC4B6)),
    TripPalette("Aurora", listOf(Color(0xFF7B61FF), Color(0xFF2EC4B6), Color(0xFFB8F2E6)), Color(0xFF7B61FF)),
    TripPalette("Desert", listOf(Color(0xFFC0392B), Color(0xFFE67E22), Color(0xFFF6D186)), Color(0xFFE67E22)),
    TripPalette("Midnight", listOf(Color(0xFF141E30), Color(0xFF243B55), Color(0xFF7B61FF)), Color(0xFF8E7BFF)),
    TripPalette("Blossom", listOf(Color(0xFFF72585), Color(0xFFB5179E), Color(0xFFFFAFCC)), Color(0xFFF72585)),
    TripPalette("Forest", listOf(Color(0xFF134E5E), Color(0xFF71B280), Color(0xFFD8F3DC)), Color(0xFF71B280)),
)

fun palette(index: Int) = TripPalettes[((index % TripPalettes.size) + TripPalettes.size) % TripPalettes.size]

private val colors = darkColorScheme(
    primary = Coral,
    onPrimary = Color.White,
    secondary = Gold,
    onSecondary = Night,
    tertiary = Teal,
    background = Night,
    onBackground = Color.White,
    surface = NightRaised,
    onSurface = Color.White,
    surfaceVariant = NightCard,
    onSurfaceVariant = Mist,
    surfaceContainer = NightRaised,
    surfaceContainerHigh = NightCard,
    surfaceContainerHighest = Color(0xFF232C55),
    surfaceContainerLow = Color(0xFF10163A),
    outline = Color(0xFF3A4475),
    outlineVariant = Color(0xFF2A3360),
)

val Display = FontFamily.Serif

private val typography = Typography().let { t ->
    t.copy(
        displayLarge = TextStyle(fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 48.sp, lineHeight = 52.sp),
        displayMedium = TextStyle(fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 38.sp, lineHeight = 42.sp),
        displaySmall = TextStyle(fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 36.sp),
        headlineMedium = TextStyle(fontFamily = Display, fontWeight = FontWeight.SemiBold, fontSize = 26.sp, lineHeight = 32.sp),
        headlineSmall = TextStyle(fontFamily = Display, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

@Composable
fun WanderlustTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, typography = typography) {
        CompositionLocalProvider(LocalContentColor provides Color.White, content = content)
    }
}
