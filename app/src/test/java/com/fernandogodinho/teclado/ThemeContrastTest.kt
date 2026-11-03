package com.fernandogodinho.teclado

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.fernandogodinho.teclado.ime.ui.KeyboardColors
import com.fernandogodinho.teclado.ime.ui.KeyboardPalette
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Guards legibility: every keyboard palette must meet WCAG contrast ratios for the pairs that
 * actually appear on screen (4.5:1 for text, 3:1 for icons and large shapes).
 */
class ThemeContrastTest {

    private fun channel(c: Int): Double {
        val v = c / 255.0
        return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }

    private fun luminance(color: Color): Double {
        val argb = color.toArgb()
        return 0.2126 * channel(argb shr 16 and 0xFF) + 0.7152 * channel(argb shr 8 and 0xFF) + 0.0722 * channel(argb and 0xFF)
    }

    fun contrast(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    private fun checks(c: KeyboardColors) = listOf(
        Triple("texto/tecla", c.text to c.key, 7.0),
        Triple("texto/tecla pressionada", c.text to c.keyPressed, 4.5),
        Triple("texto/tecla de função", c.text to c.functionKey, 4.5),
        Triple("texto/fundo", c.text to c.background, 4.5),
        Triple("texto/painel", c.text to c.surface, 4.5),
        Triple("texto/balão", c.text to c.popup, 4.5),
        Triple("texto/chip", c.text to c.chip, 4.5),
        Triple("secundário/tecla", c.textMuted to c.key, 4.5),
        Triple("secundário/fundo", c.textMuted to c.background, 4.5),
        Triple("secundário/painel", c.textMuted to c.surface, 4.5),
        Triple("secundário/chip", c.textMuted to c.chip, 4.5),
        Triple("secundário/tecla de função", c.textMuted to c.functionKey, 3.0),
        Triple("sobre destaque", c.onAccent to c.accent, 4.5),
        Triple("sobre seleção", c.onAccent to c.popupSelected, 4.5),
        Triple("destaque/painel", c.accent to c.surface, 3.0),
        Triple("destaque/fundo", c.accent to c.background, 3.0),
        Triple("destaque/chip", c.accent to c.chip, 3.0),
        Triple("ícone do microfone", c.onMic to c.mic, 4.5),
        Triple("microfone/fundo", c.mic to c.background, 3.0),
        Triple("erro/painel", c.recording to c.surface, 4.5),
        Triple("gravação/fundo", c.recording to c.background, 3.0),
        Triple("ícone branco/gravação", Color.White to c.recording, 3.0),
    )

    @Test
    fun everyKeyboardThemeIsLegible() {
        val failures = KeyboardPalette.all.flatMap { (name, palette) ->
            checks(palette).mapNotNull { (what, pair, minimum) ->
                val ratio = contrast(pair.first, pair.second)
                if (ratio < minimum) "$name: $what = ${"%.2f".format(ratio)} (mínimo $minimum)" else null
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun appColorSchemesAreLegible() {
        val failures = listOf("claro" to com.fernandogodinho.teclado.ui.theme.Light, "escuro" to com.fernandogodinho.teclado.ui.theme.Dark)
            .flatMap { (name, s) ->
                listOf(
                    Triple("texto/fundo", s.onSurface to s.background, 7.0),
                    Triple("secundário/fundo", s.onSurfaceVariant to s.background, 4.5),
                    Triple("secundário/cartão", s.onSurfaceVariant to s.surfaceContainerLow, 4.5),
                    Triple("secundário/cartão 2", s.onSurfaceVariant to s.surfaceContainer, 4.5),
                    Triple("destaque/fundo", s.primary to s.background, 4.5),
                    Triple("destaque/cartão", s.primary to s.surfaceContainerLow, 4.5),
                    Triple("texto/destaque", s.onPrimary to s.primary, 4.5),
                    Triple("texto/caixa de destaque", s.onPrimaryContainer to s.primaryContainer, 4.5),
                    Triple("status/fundo", s.tertiary to s.background, 4.5),
                    Triple("erro/fundo", s.error to s.background, 4.5),
                    Triple("texto/caixa secundária", s.onSecondaryContainer to s.secondaryContainer, 4.5),
                ).mapNotNull { (what, pair, minimum) ->
                    val ratio = contrast(pair.first, pair.second)
                    if (ratio < minimum) "$name: $what = ${"%.2f".format(ratio)} (mínimo $minimum)" else null
                }
            }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }
}
