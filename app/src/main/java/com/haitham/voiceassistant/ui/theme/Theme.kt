package com.haitham.voiceassistant.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp

val Night = Color(0xFF0B1020)
val NightSurface = Color(0xFF141B2F)
val NightSurfaceHigh = Color(0xFF1C2540)
val Accent = Color(0xFF3DDCFF)
val AccentDeep = Color(0xFF1F7AFF)
val Listening = Color(0xFF2EE59D)
val Warning = Color(0xFFFFB547)
val Danger = Color(0xFFFF5C7A)

private val scheme = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF00222E),
    primaryContainer = Color(0xFF0E3A52),
    onPrimaryContainer = Color(0xFFBDEFFF),
    secondary = Listening,
    onSecondary = Color(0xFF002114),
    tertiary = Warning,
    error = Danger,
    background = Night,
    onBackground = Color(0xFFE6EAF5),
    surface = NightSurface,
    onSurface = Color(0xFFE6EAF5),
    surfaceVariant = NightSurfaceHigh,
    onSurfaceVariant = Color(0xFFA9B3CC),
    surfaceContainer = NightSurface,
    surfaceContainerHigh = NightSurfaceHigh,
    outline = Color(0xFF3A4566)
)

private val typography = Typography(
    headlineSmall = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    labelLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
)

/** Dark Material 3 theme, forced right-to-left for Arabic. */
@Composable
fun HaithamTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typography) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) { content() }
    }
}
