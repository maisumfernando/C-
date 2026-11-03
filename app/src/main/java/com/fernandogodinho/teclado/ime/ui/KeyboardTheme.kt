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
    val isDark: Boolean,
)

object KeyboardPalette {
    val light = KeyboardColors(
        background = Color(0xFFEFEFEC),
        key = Color(0xFFFFFFFF),
        keyPressed = Color(0xFFDDDDD8),
        functionKey = Color(0xFFDCDCD7),
        functionKeyPressed = Color(0xFFC6C6C0),
        keyShadow = Color(0x26000000),
        text = Color(0xFF1B1B19),
        textMuted = Color(0xFF6F6F69),
        accent = Color(0xFF3D5CEB),
        accentPressed = Color(0xFF2F47C7),
        onAccent = Color(0xFFFFFFFF),
        popup = Color(0xFFFFFFFF),
        popupSelected = Color(0xFF3D5CEB),
        divider = Color(0x1F000000),
        chip = Color(0xFFE3E3DF),
        recording = Color(0xFFE5484D),
        surface = Color(0xFFFFFFFF),
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
        textMuted = Color(0xFFA3A3A0),
        accent = Color(0xFF8FA4FF),
        accentPressed = Color(0xFF7489F0),
        onAccent = Color(0xFF0E1433),
        popup = Color(0xFF38383B),
        popupSelected = Color(0xFF8FA4FF),
        divider = Color(0x1FFFFFFF),
        chip = Color(0xFF2B2B2D),
        recording = Color(0xFFFF6369),
        surface = Color(0xFF1C1C1E),
        isDark = true,
    )
}

val LocalKeyboardColors = staticCompositionLocalOf { KeyboardPalette.light }
