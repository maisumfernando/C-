package com.fernandogodinho.teclado

import com.fernandogodinho.teclado.ime.suggest.Dictionary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

class DictionaryTest {

    companion object {
        private lateinit var dict: Dictionary

        @BeforeClass
        @JvmStatic
        fun load() {
            val dir = listOf("src/main/assets/dict", "app/src/main/assets/dict").map(::File).first { it.isDirectory }
            dict = Dictionary.build(File(dir, "pt_br.txt").readLines(), File(dir, "en.txt").readLines())
        }
    }

    @Test
    fun restoresMissingAccents() {
        assertEquals("não", dict.autoCorrection("nao"))
        assertEquals("você", dict.autoCorrection("voce"))
        assertEquals("também", dict.autoCorrection("tambem"))
        assertEquals("então", dict.autoCorrection("entao"))
        assertEquals("até", dict.autoCorrection("ate"))
    }

    @Test
    fun leavesValidAccentPairsAlone() {
        // "esta" and "está" are both correct Portuguese: never auto-correct between them.
        assertNull(dict.autoCorrection("esta"))
        assertNull(dict.autoCorrection("e"))
        assertNull(dict.autoCorrection("pais"))
        assertNull(dict.autoCorrection("casa"))
    }

    @Test
    fun fixesCommonTypos() {
        assertEquals("obrigado", dict.autoCorrection("obrigaso"))
        assertEquals("trabalho", dict.autoCorrection("trabalhp"))
        assertEquals("amanhã", dict.autoCorrection("amanha"))
    }

    @Test
    fun suggestsCompletionsAndAccents() {
        val words = dict.candidates("voc", 3).map { it.word }
        assertTrue("got $words", "você" in words)
        val accent = dict.candidates("nao", 3).map { it.word }
        assertEquals("não", accent.first())
    }

    @Test
    fun userWordsAreNeverCorrected() {
        dict.addUserWords(listOf("Kubernetes", "Escriba"))
        assertNull(dict.autoCorrection("kubernetes"))
        assertTrue(dict.candidates("kuber", 3).any { it.word == "Kubernetes" })
        dict.addUserWords(emptyList())
    }
}
