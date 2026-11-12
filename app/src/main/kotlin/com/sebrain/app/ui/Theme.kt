package com.sebrain.app.ui

import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Cyberpunk palette: neon cyan, magenta and yellow on near-black. The app is always dark. */
object Neon {
    val Cyan = Color(0xFF00F0FF)
    val Magenta = Color(0xFFFF2A6D)
    val Yellow = Color(0xFFFCEE0A)
    val Void = Color(0xFF07070D)
    val Panel = Color(0xFF0F1020)
    val PanelHigh = Color(0xFF171A2E)
    val Line = Color(0xFF1F5F7A)
    val Dim = Color(0xFF7C8BA6)
}

private val Colors = darkColorScheme(
    primary = Neon.Cyan,
    onPrimary = Color(0xFF00202A),
    primaryContainer = Color(0xFF06323D),
    onPrimaryContainer = Neon.Cyan,
    secondary = Neon.Magenta,
    onSecondary = Color(0xFF2A0010),
    secondaryContainer = Color(0xFF3D0A1E),
    onSecondaryContainer = Color(0xFFFFB3C8),
    tertiary = Neon.Yellow,
    onTertiary = Color(0xFF1F1D00),
    background = Neon.Void,
    onBackground = Color(0xFFD7F9FF),
    surface = Neon.Void,
    onSurface = Color(0xFFD7F9FF),
    surfaceVariant = Neon.PanelHigh,
    onSurfaceVariant = Neon.Dim,
    surfaceContainerLowest = Neon.Void,
    surfaceContainerLow = Neon.Panel,
    surfaceContainer = Neon.Panel,
    surfaceContainerHigh = Neon.PanelHigh,
    surfaceContainerHighest = Neon.PanelHigh,
    outline = Neon.Line,
    outlineVariant = Color(0xFF16384A),
    error = Color(0xFFFF4D4D),
)

private val Cut = Shapes(
    extraSmall = CutCornerShape(4.dp),
    small = CutCornerShape(6.dp),
    medium = CutCornerShape(topStart = 12.dp, bottomEnd = 12.dp),
    large = CutCornerShape(topStart = 16.dp, bottomEnd = 16.dp),
    extraLarge = CutCornerShape(topStart = 14.dp, bottomEnd = 14.dp),
)

private val Mono = FontFamily.Monospace

private fun typography(): Typography {
    val base = Typography()
    return base.copy(
        headlineSmall = base.headlineSmall.copy(fontFamily = Mono, fontWeight = FontWeight.Bold),
        titleLarge = base.titleLarge.copy(fontFamily = Mono, fontWeight = FontWeight.Bold),
        titleMedium = base.titleMedium.copy(fontFamily = Mono, fontWeight = FontWeight.Bold),
        titleSmall = base.titleSmall.copy(fontFamily = Mono, fontWeight = FontWeight.Bold),
        labelLarge = base.labelLarge.copy(fontFamily = Mono, letterSpacing = 1.sp),
        labelMedium = base.labelMedium.copy(fontFamily = Mono, letterSpacing = 1.sp),
        labelSmall = base.labelSmall.copy(fontFamily = Mono, letterSpacing = 1.sp),
    )
}

@Composable
fun SeBrainTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, shapes = Cut, typography = typography(), content = content)
}
