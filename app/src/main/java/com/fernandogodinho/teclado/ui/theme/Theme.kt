package com.fernandogodinho.teclado.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.fernandogodinho.teclado.core.ThemeMode

private val Ink = Color(0xFF3D5CEB)
private val InkDark = Color(0xFF8FA4FF)

private val Light = lightColorScheme(
    primary = Ink,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE4E8FD),
    onPrimaryContainer = Color(0xFF1B2A7A),
    secondary = Color(0xFF5E5E58),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEDECE7),
    onSecondaryContainer = Color(0xFF2A2A27),
    tertiary = Color(0xFF2E8B57),
    background = Color(0xFFF8F7F4),
    onBackground = Color(0xFF1B1B19),
    surface = Color(0xFFF8F7F4),
    onSurface = Color(0xFF1B1B19),
    surfaceVariant = Color(0xFFEFEEE9),
    onSurfaceVariant = Color(0xFF64645E),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFF1F0EB),
    surfaceContainerHigh = Color(0xFFEBEAE4),
    surfaceContainerHighest = Color(0xFFE4E3DD),
    outline = Color(0xFFD3D2CC),
    outlineVariant = Color(0xFFE6E5DF),
    error = Color(0xFFD1373D),
)

private val Dark = darkColorScheme(
    primary = InkDark,
    onPrimary = Color(0xFF0E1433),
    primaryContainer = Color(0xFF26315F),
    onPrimaryContainer = Color(0xFFDDE2FF),
    secondary = Color(0xFFB8B8B2),
    onSecondary = Color(0xFF1B1B19),
    secondaryContainer = Color(0xFF2C2C2A),
    onSecondaryContainer = Color(0xFFE6E5E0),
    tertiary = Color(0xFF5CC98B),
    background = Color(0xFF141413),
    onBackground = Color(0xFFEDEDE8),
    surface = Color(0xFF141413),
    onSurface = Color(0xFFEDEDE8),
    surfaceVariant = Color(0xFF242422),
    onSurfaceVariant = Color(0xFFA6A6A0),
    surfaceContainerLowest = Color(0xFF0F0F0E),
    surfaceContainerLow = Color(0xFF1C1C1A),
    surfaceContainer = Color(0xFF1F1F1D),
    surfaceContainerHigh = Color(0xFF282826),
    surfaceContainerHighest = Color(0xFF32322F),
    outline = Color(0xFF45453F),
    outlineVariant = Color(0xFF2E2E2B),
    error = Color(0xFFFF6B70),
)

private val AppTypography = Typography().let { t ->
    t.copy(
        displaySmall = t.displaySmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
        headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp),
        headlineSmall = t.headlineSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        bodyLarge = t.bodyLarge.copy(lineHeight = 24.sp),
        bodyMedium = t.bodyMedium.copy(lineHeight = 21.sp),
        labelLarge = t.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

@Composable
fun TecladoTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val scheme = if (dark) Dark else Light
    MaterialTheme(colorScheme = scheme, typography = AppTypography) {
        // Without a Surface, Text with no explicit colour falls back to black (LocalContentColor's
        // default), which made titles unreadable in dark mode. The Surface sets the content colour
        // for every screen and paints the background behind route transitions.
        Surface(Modifier.fillMaxSize(), color = scheme.background, contentColor = scheme.onBackground, content = content)
    }
}

val MonoLabel = TextStyle(fontSize = 12.sp, letterSpacing = 0.4.sp, fontWeight = FontWeight.Medium)
