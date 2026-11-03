package com.fernandogodinho.teclado.ime.ui

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

@Immutable
data class KeyboardColors(
    val background: Color,
    val key: Color,
    val keyPressed: Color,
    val functionKey: Color,
    val functionKeyPressed: Color,
    val keyShadow: Color,
    val text: Color,
    val textMuted: Color,
    val accent: Color,
    val accentPressed: Color,
    val onAccent: Color,
    val popup: Color,
    val popupSelected: Color,
    val divider: Color,
    val chip: Color,
    val recording: Color,
    val surface: Color,
    /** The dictation (microphone) button. */
    val mic: Color,
    val onMic: Color,
    val isDark: Boolean,
)

/** Keyboard colour themes. CLASSIC follows the app theme (light/dark); the others are fixed. */
enum class KeyboardThemeId(val label: String) {
    CLASSIC("Clássico"),
    GRAPHITE("Grafite"),
    MIDNIGHT("Meia-noite"),
    SAGE("Sálvia"),
    SAND("Areia"),
    LAVENDER("Lavanda"),
    HIGH_CONTRAST("Alto contraste"),
}

/**
 * Every palette is checked by KeyboardThemeContrastTest (WCAG contrast ratios): text on keys,
 * muted text, accent and microphone colours must stay legible on every surface they appear on.
 */
object KeyboardPalette {
    fun of(id: KeyboardThemeId, dark: Boolean): KeyboardColors = when (id) {
        KeyboardThemeId.CLASSIC -> if (dark) this.dark else light
        KeyboardThemeId.GRAPHITE -> graphite
        KeyboardThemeId.MIDNIGHT -> midnight
        KeyboardThemeId.SAGE -> sage
        KeyboardThemeId.SAND -> sand
        KeyboardThemeId.LAVENDER -> lavender
        KeyboardThemeId.HIGH_CONTRAST -> highContrast
    }

    /** All distinct palettes (used by tests and by the theme picker). */
    val all: List<Pair<String, KeyboardColors>> get() = listOf(
        "Clássico claro" to light, "Clássico escuro" to dark, "Grafite" to graphite, "Meia-noite" to midnight,
        "Sálvia" to sage, "Areia" to sand, "Lavanda" to lavender, "Alto contraste" to highContrast,
    )

    val light = KeyboardColors(
        background = Color(0xFFEFEFEC),
        key = Color(0xFFFFFFFF),
        keyPressed = Color(0xFFDDDDD8),
        functionKey = Color(0xFFDCDCD7),
        functionKeyPressed = Color(0xFFC6C6C0),
        keyShadow = Color(0x26000000),
        text = Color(0xFF1B1B19),
        textMuted = Color(0xFF63635D),
        accent = Color(0xFF3D5CEB),
        accentPressed = Color(0xFF2F47C7),
        onAccent = Color(0xFFFFFFFF),
        popup = Color(0xFFFFFFFF),
        popupSelected = Color(0xFF3D5CEB),
        divider = Color(0x1F000000),
        chip = Color(0xFFE3E3DF),
        recording = Color(0xFFD2353F),
        surface = Color(0xFFFFFFFF),
        mic = Color(0xFF2C2C30),
        onMic = Color(0xFFFFFFFF),
        isDark = false,
    )

    val dark = KeyboardColors(
        background = Color(0xFF121212),
        key = Color(0xFF2B2B2D),
        keyPressed = Color(0xFF3D3D40),
        functionKey = Color(0xFF3A3A3D),
        functionKeyPressed = Color(0xFF4C4C50),
        keyShadow = Color(0x66000000),
        text = Color(0xFFF2F2F0),
        textMuted = Color(0xFFA9A9A6),
        accent = Color(0xFF8FA4FF),
        accentPressed = Color(0xFF7489F0),
        onAccent = Color(0xFF0E1433),
        popup = Color(0xFF38383B),
        popupSelected = Color(0xFF8FA4FF),
        divider = Color(0x1FFFFFFF),
        chip = Color(0xFF2B2B2D),
        recording = Color(0xFFEF5A5F),
        surface = Color(0xFF1C1C1E),
        mic = Color(0xFFE4E4E0),
        onMic = Color(0xFF1B1B1D),
        isDark = true,
    )

    val graphite = KeyboardColors(
        background = Color(0xFF1C1D20),
        key = Color(0xFF34363B),
        keyPressed = Color(0xFF45484E),
        functionKey = Color(0xFF27292D),
        functionKeyPressed = Color(0xFF383A3F),
        keyShadow = Color(0x66000000),
        text = Color(0xFFF1F1EF),
        textMuted = Color(0xFFABADB2),
        accent = Color(0xFFF2B33D),
        accentPressed = Color(0xFFD99A26),
        onAccent = Color(0xFF1C1D20),
        popup = Color(0xFF3C3F45),
        popupSelected = Color(0xFFF2B33D),
        divider = Color(0x1FFFFFFF),
        chip = Color(0xFF2A2C30),
        recording = Color(0xFFEF5A5F),
        surface = Color(0xFF24262A),
        mic = Color(0xFFF2B33D),
        onMic = Color(0xFF1C1D20),
        isDark = true,
    )

    val midnight = KeyboardColors(
        background = Color(0xFF0E1424),
        key = Color(0xFF1D2740),
        keyPressed = Color(0xFF2A3654),
        functionKey = Color(0xFF162035),
        functionKeyPressed = Color(0xFF24304B),
        keyShadow = Color(0x80000000),
        text = Color(0xFFEEF2FA),
        textMuted = Color(0xFFA3AEC6),
        accent = Color(0xFF4FD1C5),
        accentPressed = Color(0xFF38B2A6),
        onAccent = Color(0xFF0B1220),
        popup = Color(0xFF26324C),
        popupSelected = Color(0xFF4FD1C5),
        divider = Color(0x1FFFFFFF),
        chip = Color(0xFF1A2439),
        recording = Color(0xFFEF5A5F),
        surface = Color(0xFF151E33),
        mic = Color(0xFF4FD1C5),
        onMic = Color(0xFF0B1220),
        isDark = true,
    )

    val sage = KeyboardColors(
        background = Color(0xFFDFE7DD),
        key = Color(0xFFF8FBF7),
        keyPressed = Color(0xFFD3DECF),
        functionKey = Color(0xFFC9D6C6),
        functionKeyPressed = Color(0xFFB6C6B3),
        keyShadow = Color(0x26203520),
        text = Color(0xFF1C2A1F),
        textMuted = Color(0xFF4F6153),
        accent = Color(0xFF2E6A44),
        accentPressed = Color(0xFF235536),
        onAccent = Color(0xFFFFFFFF),
        popup = Color(0xFFFFFFFF),
        popupSelected = Color(0xFF2E6A44),
        divider = Color(0x1F1C2A1F),
        chip = Color(0xFFD0DCCD),
        recording = Color(0xFFB83232),
        surface = Color(0xFFF8FBF7),
        mic = Color(0xFF2E6A44),
        onMic = Color(0xFFFFFFFF),
        isDark = false,
    )

    val sand = KeyboardColors(
        background = Color(0xFFEEE4D4),
        key = Color(0xFFFFFBF4),
        keyPressed = Color(0xFFE6D9C4),
        functionKey = Color(0xFFDFD0B9),
        functionKeyPressed = Color(0xFFCFBCA0),
        keyShadow = Color(0x2E3B2A14),
        text = Color(0xFF2B2118),
        textMuted = Color(0xFF65564A),
        accent = Color(0xFFA2462A),
        accentPressed = Color(0xFF86391F),
        onAccent = Color(0xFFFFFFFF),
        popup = Color(0xFFFFFBF4),
        popupSelected = Color(0xFFA2462A),
        divider = Color(0x1F2B2118),
        chip = Color(0xFFE4D7C2),
        recording = Color(0xFFB03524),
        surface = Color(0xFFFFFBF4),
        mic = Color(0xFFA2462A),
        onMic = Color(0xFFFFFFFF),
        isDark = false,
    )

    val lavender = KeyboardColors(
        background = Color(0xFFEAE5F5),
        key = Color(0xFFFFFFFF),
        keyPressed = Color(0xFFDDD5F0),
        functionKey = Color(0xFFD8CFEE),
        functionKeyPressed = Color(0xFFC5B9E5),
        keyShadow = Color(0x262A1E52),
        text = Color(0xFF1E1A2E),
        textMuted = Color(0xFF58516E),
        accent = Color(0xFF6145C4),
        accentPressed = Color(0xFF4F37A8),
        onAccent = Color(0xFFFFFFFF),
        popup = Color(0xFFFFFFFF),
        popupSelected = Color(0xFF6145C4),
        divider = Color(0x1F1E1A2E),
        chip = Color(0xFFDED6F1),
        recording = Color(0xFFB8304F),
        surface = Color(0xFFFFFFFF),
        mic = Color(0xFF6145C4),
        onMic = Color(0xFFFFFFFF),
        isDark = false,
    )

    val highContrast = KeyboardColors(
        background = Color(0xFF000000),
        key = Color(0xFF262626),
        keyPressed = Color(0xFF404040),
        functionKey = Color(0xFF3D3D3D),
        functionKeyPressed = Color(0xFF575757),
        keyShadow = Color(0x00000000),
        text = Color(0xFFFFFFFF),
        textMuted = Color(0xFFE6E6E6),
        accent = Color(0xFFFFD60A),
        accentPressed = Color(0xFFE6BF00),
        onAccent = Color(0xFF000000),
        popup = Color(0xFF262626),
        popupSelected = Color(0xFFFFD60A),
        divider = Color(0x66FFFFFF),
        chip = Color(0xFF262626),
        recording = Color(0xFFEF5A5F),
        surface = Color(0xFF121212),
        mic = Color(0xFFFFD60A),
        onMic = Color(0xFF000000),
        isDark = true,
    )
}

val LocalKeyboardColors = staticCompositionLocalOf { KeyboardPalette.light }
