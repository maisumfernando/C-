package com.fernandogodinho.teclado.ime.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Incognito glyph (hat and glasses), drawn so it scales crisply from toolbar icon to watermark. */
fun DrawScope.drawIncognito(color: Color) {
    val w = size.width
    val h = size.height
    val crown = Path().apply {
        moveTo(w * 0.31f, h * 0.16f)
        lineTo(w * 0.69f, h * 0.16f)
        lineTo(w * 0.77f, h * 0.45f)
        lineTo(w * 0.23f, h * 0.45f)
        close()
    }
    drawPath(crown, color)
    drawRoundRect(color, topLeft = Offset(w * 0.08f, h * 0.45f), size = Size(w * 0.84f, h * 0.08f), cornerRadius = CornerRadius(h * 0.04f))
    val stroke = Stroke(width = w * 0.065f)
    val r = w * 0.135f
    drawCircle(color, radius = r, center = Offset(w * 0.32f, h * 0.72f), style = stroke)
    drawCircle(color, radius = r, center = Offset(w * 0.68f, h * 0.72f), style = stroke)
    drawLine(color, Offset(w * 0.455f, h * 0.70f), Offset(w * 0.545f, h * 0.70f), strokeWidth = w * 0.05f)
}

/** Faint incognito mark across the keys while private mode is on (it never intercepts touches). */
@Composable
fun PrivateWatermark(modifier: Modifier = Modifier) {
    val colors = LocalKeyboardColors.current
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(150.dp)) {
            drawIncognito(colors.text.copy(alpha = if (colors.isDark) 0.08f else 0.06f))
        }
    }
}

/** Toolbar switch for private mode; highlighted while active. */
@Composable
fun PrivacyToggle(active: Boolean, onClick: () -> Unit) {
    val colors = LocalKeyboardColors.current
    Box(
        Modifier
            .size(42.dp)
            .clip(CircleShape)
            .background(if (active) colors.accent.copy(alpha = 0.16f) else Color.Transparent)
            .clickable(onClick = onClick)
            .semantics { contentDescription = if (active) "Modo privado ligado" else "Ligar modo privado" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(22.dp)) { drawIncognito(if (active) colors.accent else colors.textMuted) }
    }
}
