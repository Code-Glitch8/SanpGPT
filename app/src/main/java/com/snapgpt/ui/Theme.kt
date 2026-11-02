package com.snapgpt.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.snapgpt.settings.ThemeMode

private val Indigo = Color(0xFF4F46E5)
private val Violet = Color(0xFF9333EA)

private val LightColors = lightColorScheme(
    primary = Indigo,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE0E0FF),
    onPrimaryContainer = Color(0xFF14137A),
    secondary = Violet,
    background = Color(0xFFFBFAFF),
    surface = Color(0xFFFBFAFF),
    surfaceVariant = Color(0xFFE7E5F2),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB5B4FF),
    onPrimary = Color(0xFF1E1B8F),
    primaryContainer = Color(0xFF3730D1),
    onPrimaryContainer = Color(0xFFE0E0FF),
    secondary = Color(0xFFD9B8FF),
    background = Color(0xFF121218),
    surface = Color(0xFF121218),
    surfaceVariant = Color(0xFF2C2B38),
)

@Composable
fun SnapGptTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, content = content)
}
