package com.fernandogodinho.teclado

import com.fernandogodinho.teclado.engine.BenchmarkMath
import com.fernandogodinho.teclado.engine.BenchmarkReport
import com.fernandogodinho.teclado.engine.BenchmarkSamples
import com.fernandogodinho.teclado.engine.SpeechBench
import com.fernandogodinho.teclado.engine.TextBench
import com.fernandogodinho.teclado.models.ModelCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BenchmarkTest {

    private val small = SpeechBench("whisper-small-q8", loadMs = 900, runMs = 3_000, audioMs = 15_400, transcript = "", accuracy = 0.95)
    private val gemma = TextBench(
        modelId = "gemma-4-e2b-q4", loadMs = 2_000, warmupMs = 4_000, inputWords = 55, inputTokens = 80,
        outputTokens = 70, firstTokenMs = 800, totalMs = 5_800, output = "Texto revisado.",
    )
    private val report = BenchmarkReport(
        createdAt = 1L, device = "Teste", ramGb = 12.0, threads = 4, appVersion = "1.1.0",
        speech = listOf(small), text = listOf(gemma),
    )

    @Test
    fun accuracyIgnoresCaseAndPunctuation() {
        assertEquals(1.0, BenchmarkMath.wordAccuracy(BenchmarkSamples.AUDIO_REFERENCE, BenchmarkSamples.AUDIO_REFERENCE.uppercase().replace(",", "")), 1e-9)
        val heard = "As crônicas da Vila de Taguaí dizem que em tempos remotos viverá ali um certo médico, o Dr. Simão Bacamarte, " +
            "filho da nobreza da terra e o maior dos médicos do Brasil, de Portugal e das Espanhas."
        // Two wrong words out of 37 ("Taguaí", "viverá"); "Dr." counts as "doutor".
        assertEquals(1 - 2.0 / 37, BenchmarkMath.wordAccuracy(BenchmarkSamples.AUDIO_REFERENCE, heard), 1e-9)
        assertEquals(0.0, BenchmarkMath.wordAccuracy(BenchmarkSamples.AUDIO_REFERENCE, ""), 1e-9)
    }

    @Test
    fun sampleDictationHas55Words() {
        assertEquals(55, BenchmarkMath.words(BenchmarkSamples.DICTATION).size)
        assertEquals(37, BenchmarkMath.words(BenchmarkSamples.AUDIO_REFERENCE).size)
    }

    @Test
    fun speechWaitGrowsWithLengthButStaysBelowTotalWhenTranscribingDuringPauses() {
        val short = BenchmarkMath.speechWaitMs(15, small)
        val long = BenchmarkMath.speechWaitMs(150, small)
        val withoutPauses = BenchmarkMath.speechWaitMs(150, small, transcribeDuringPauses = false)
        assertTrue(short > 0)
        assertTrue(long >= short)
        // Fast model (RTF ~0.2): the backlog never grows, the wait is about one stretch.
        assertTrue(long < 4_000)
        assertTrue(withoutPauses > long)
    }

    @Test
    fun slowSpeechModelAccumulatesBacklog() {
        val slow = small.copy(runMs = 30_000) // twice slower than real time
        val wait = BenchmarkMath.speechWaitMs(150, slow)
        // 62 s of speech at RTF ~2: well over a minute of work is left when the user stops.
        assertTrue(wait > 50_000)
    }

    @Test
    fun polishTimeScalesWithWords() {
        val p15 = BenchmarkMath.polishMs(15, gemma)
        val p150 = BenchmarkMath.polishMs(150, gemma)
        assertEquals(p15 * 10, p150, 1.0)
        // 55 words take about the measured 5.8 s.
        assertEquals(5_800.0, BenchmarkMath.polishMs(55, gemma), 300.0)
    }

    @Test
    fun combinationsEstimateUninstalledModelsFromMeasuredOnes() {
        val combos = BenchmarkMath.combinations(report, ModelCatalog.speechModels, ModelCatalog.textModels)
        // 3 speech models × (4 text models + no revision)
        assertEquals(15, combos.size)
        val measured = combos.single { it.speech.id == "whisper-small-q8" && it.text?.id == "gemma-4-e2b-q4" }
        assertTrue(measured.measured)
        assertEquals(0.95, measured.speechAccuracy!!, 1e-9)
        val turbo = combos.single { it.speech.id == "whisper-large-v3-turbo-q5" && it.text?.id == "gemma-4-e2b-q4" }
        assertFalse(turbo.measured)
        assertTrue(turbo.waitMs[1] > measured.waitMs[1])
        val qwen4 = combos.single { it.speech.id == "whisper-small-q8" && it.text?.id == "qwen3.5-4b-q4" }
        assertTrue(qwen4.waitMs[1] > measured.waitMs[1])
        // Sorted from the fastest paragraph to the slowest.
        assertEquals(combos.map { it.waitMs[1] }.sorted(), combos.map { it.waitMs[1] })
    }

    @Test
    fun badgesPickFastestAndBest() {
        val combos = BenchmarkMath.combinations(report, ModelCatalog.speechModels, ModelCatalog.textModels, includeWithoutRevision = false)
        val badges = BenchmarkMath.badges(combos)
        val fastest = badges.entries.single { it.value == BenchmarkMath.Badge.FASTEST }.key
        val best = badges.entries.single { it.value == BenchmarkMath.Badge.BEST }.key
        assertEquals(combos.first(), fastest)
        assertEquals("qwen3.5-4b-q4", best.text?.id)
        assertEquals("whisper-large-v3-turbo-q5", best.speech.id)
    }

    @Test
    fun reportRoundTripsThroughJson() {
        val restored = BenchmarkReport.fromJson(report.copy(failed = listOf("qwen3.5-4b-q4")).toJson())
        assertNotNull(restored)
        assertEquals(report.copy(failed = listOf("qwen3.5-4b-q4")), restored)
        assertEquals(null, BenchmarkReport.fromJson("{not json"))
    }

    @Test
    fun formatsSeconds() {
        assertEquals("0,8 s", BenchmarkMath.formatSeconds(800.0))
        assertEquals("12 s", BenchmarkMath.formatSeconds(12_400.0))
        assertEquals("1 min 05 s", BenchmarkMath.formatSeconds(65_000.0))
    }
}
