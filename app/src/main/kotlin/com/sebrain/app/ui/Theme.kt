package com.sebrain.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Dark = darkColorScheme(
    primary = Color(0xFF7CC4FF),
    onPrimary = Color(0xFF00213A),
    primaryContainer = Color(0xFF123A5C),
    onPrimaryContainer = Color(0xFFD3E8FF),
    secondary = Color(0xFF9BE9A8),
    onSecondary = Color(0xFF07310F),
    secondaryContainer = Color(0xFF1B3A24),
    onSecondaryContainer = Color(0xFFC9F5D1),
    background = Color(0xFF0D1117),
    onBackground = Color(0xFFE6EDF3),
    surface = Color(0xFF0D1117),
    onSurface = Color(0xFFE6EDF3),
    surfaceVariant = Color(0xFF1C2330),
    onSurfaceVariant = Color(0xFF9DA7B3),
    surfaceContainer = Color(0xFF161B22),
    surfaceContainerHigh = Color(0xFF1C2330),
    outline = Color(0xFF3A4452),
    error = Color(0xFFFF7B72),
)

private val Light = lightColorScheme(
    primary = Color(0xFF0B63B0),
    secondary = Color(0xFF1F7A35),
    background = Color(0xFFF8FAFC),
    surface = Color(0xFFF8FAFC),
    surfaceVariant = Color(0xFFE6EBF1),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFEEF2F6),
)

@Composable
fun SeBrainTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
