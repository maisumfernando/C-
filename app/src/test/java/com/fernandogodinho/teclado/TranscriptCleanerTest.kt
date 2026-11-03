package com.fernandogodinho.teclado

import com.fernandogodinho.teclado.engine.Prompts
import com.fernandogodinho.teclado.engine.TranscriptCleaner
import com.fernandogodinho.teclado.core.PolishStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptCleanerTest {

    @Test
    fun removesWhisperHallucinations() {
        assertEquals("", TranscriptCleaner.cleanSegment(" Legendas pela comunidade Amara.org"))
        assertEquals("Vamos almoçar.", TranscriptCleaner.cleanSegment(" Vamos almoçar. Obrigado por assistir!"))
        assertEquals("Oi, tudo bem?", TranscriptCleaner.cleanSegment("[Música] Oi, tudo bem?"))
    }

    @Test
    fun keepsLegitimateShortReplies() {
        assertEquals("Obrigado!", TranscriptCleaner.cleanSegment(" Obrigado!"))
        assertEquals("Tchau.", TranscriptCleaner.cleanSegment("Tchau."))
    }

    @Test
    fun detectsDegenerateOutput() {
        assertTrue(TranscriptCleaner.looksDegenerate("a a a a a a a a a a", 3.0))
        assertTrue(TranscriptCleaner.looksDegenerate("eu acho que eu acho que eu acho que eu acho que", 4.0))
        assertTrue(TranscriptCleaner.looksDegenerate("", 5.0))
        assertFalse(TranscriptCleaner.looksDegenerate("Amanhã vou chegar mais tarde porque tenho médico.", 4.0))
    }

    @Test
    fun basicPolishCleansFillers() {
        val out = TranscriptCleaner.basicPolish("ééé eu eu vou pra reunião amanhã, né")
        assertEquals("Eu vou para reunião amanhã.", out)
    }

    @Test
    fun sanitizesModelOutput() {
        assertEquals("Texto final.", TranscriptCleaner.sanitizeModelOutput("<think>\nhmm\n</think>\nTexto final."))
        assertEquals("Texto final.", TranscriptCleaner.sanitizeModelOutput("Texto revisado: Texto final."))
        assertEquals("Texto final.", TranscriptCleaner.sanitizeModelOutput("\"Texto final.\""))
        assertEquals("Olá", TranscriptCleaner.sanitizeModelOutput("<ditado>\nOlá\n</ditado>"))
    }

    @Test
    fun promptPrefixIsStableAcrossRequests() {
        // The KV-cache reuse depends on the prefix being byte-identical between calls.
        val a = Prompts.conversation(PolishStyle.FORMAL, "primeiro texto", null)
        val b = Prompts.conversation(PolishStyle.FORMAL, "segundo texto", "Ana")
        assertEquals(a.dropLast(1), b.dropLast(1))
        assertEquals(Prompts.prefixMessages(PolishStyle.FORMAL), a.dropLast(1))
        assertTrue(b.last().content.startsWith("Vocabulário: Ana\n<ditado>"))
    }
}
