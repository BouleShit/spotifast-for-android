package com.spotifast.android.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Spotifast's dark palette (src/theme.rs).
val Window = Color(0xFF0F1114)
val Panel = Color(0xFF15181C)
val Surface = Color(0xFF1D2127)
val SurfaceActive = Color(0xFF2F353F)
val Outline = Color(0xFF2A3038)
val TextMain = Color(0xFFF2F4F6)
val TextSecondary = Color(0xFFA9B1BC)
val TextDim = Color(0xFF6E7784)
val Accent = Color(0xFF1ED760)
val OnAccent = Color(0xFF0A140E)
val Danger = Color(0xFFF5717F)

private val colors = darkColorScheme(
    primary = Accent,
    onPrimary = OnAccent,
    primaryContainer = SurfaceActive,
    onPrimaryContainer = TextMain,
    secondary = Accent,
    onSecondary = OnAccent,
    secondaryContainer = SurfaceActive,
    onSecondaryContainer = TextMain,
    background = Window,
    onBackground = TextMain,
    surface = Window,
    onSurface = TextMain,
    surfaceVariant = Surface,
    onSurfaceVariant = TextSecondary,
    surfaceContainerLowest = Window,
    surfaceContainerLow = Panel,
    surfaceContainer = Panel,
    surfaceContainerHigh = Surface,
    surfaceContainerHighest = SurfaceActive,
    outline = Outline,
    outlineVariant = Outline,
    error = Danger,
)

@Composable
fun SpotifastTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, typography = Typography(), content = content)
}
