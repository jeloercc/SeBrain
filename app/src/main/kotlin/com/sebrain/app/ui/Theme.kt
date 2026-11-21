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

/** The post-punk palette (docs/design-postpunk.md), named by what each colour is. */
object Palette {
    /** Bone white: text and controls. */
    val Bone = Color(0xFFE7E1D5)
    /** Oxidized red: emphasis. */
    val Rust = Color(0xFFD04A4A)
    /** Coral: actions and the active tab. */
    val Coral = Color(0xFFE56B61)
    /** Muted olive: secondary accents and good states. */
    val Olive = Color(0xFFB4BE72)
    /** Ink black: the background. */
    val Ink = Color(0xFF101010)
    val Panel = Color(0xFF191817)
    val PanelHigh = Color(0xFF24211F)
    val Line = Color(0xFF5A4A43)
    val Dim = Color(0xFFA79D91)
}

private val Colors = darkColorScheme(
    primary = Palette.Bone,
    onPrimary = Color(0xFF00202A),
    primaryContainer = Color(0xFF06323D),
    onPrimaryContainer = Palette.Bone,
    secondary = Palette.Rust,
    onSecondary = Color(0xFF2A0010),
    secondaryContainer = Color(0xFF3D0A1E),
    onSecondaryContainer = Color(0xFFFFB3C8),
    tertiary = Palette.Olive,
    onTertiary = Color(0xFF1F1D00),
    background = Palette.Ink,
    onBackground = Palette.Bone,
    surface = Palette.Ink,
    onSurface = Palette.Bone,
    surfaceVariant = Palette.PanelHigh,
    onSurfaceVariant = Palette.Dim,
    surfaceContainerLowest = Palette.Ink,
    surfaceContainerLow = Palette.Panel,
    surfaceContainer = Palette.Panel,
    surfaceContainerHigh = Palette.PanelHigh,
    surfaceContainerHighest = Palette.PanelHigh,
    outline = Palette.Line,
    outlineVariant = Color(0xFF3A2D2A),
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
