package com.joymerge.quest.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp

/**
 * Dark by default, and deliberately high contrast: this UI is mostly read
 * through a Quest 2 display, where thin low-contrast text is unreadable.
 */
private val DarkColors = darkColorScheme(
    primary = Color(0xFF4FC3F7),
    onPrimary = Color(0xFF00272E),
    primaryContainer = Color(0xFF13363F),
    onPrimaryContainer = Color(0xFFB3E5FC),
    secondary = Color(0xFFFF8A65),
    onSecondary = Color(0xFF3E1300),
    background = Color(0xFF0E1116),
    onBackground = Color(0xFFE4E8EE),
    surface = Color(0xFF161B22),
    onSurface = Color(0xFFE4E8EE),
    surfaceVariant = Color(0xFF1F2630),
    onSurfaceVariant = Color(0xFFAEB8C4),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF3B0000),
    outline = Color(0xFF3A4552),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF00658F),
    secondary = Color(0xFFB2431A),
    background = Color(0xFFF7F9FC),
    surface = Color(0xFFFFFFFF),
    error = Color(0xFFB3261E),
)

val StatusOk = Color(0xFF66DD9A)
val StatusWarn = Color(0xFFFFC46B)
val StatusBad = Color(0xFFFF7A7A)
val StatusIdle = Color(0xFF7F8B99)

val MonoTextStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp)

@Composable
fun JoyMergeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        // The Quest launcher renders apps on a dark backdrop, so light mode is
        // only really useful when bench-testing on a phone.
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = Typography(),
        content = content,
    )
}
