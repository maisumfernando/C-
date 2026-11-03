package com.fernandogodinho.teclado.engine

import com.fernandogodinho.teclado.models.ModelKind
import com.fernandogodinho.teclado.models.ModelSpec
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/** Fixed inputs of the speed test, identical on every phone so results can be compared. */
object BenchmarkSamples {
    /** 15 s of 16 kHz mono PCM (public domain, see [AUDIO_CREDIT]). */
    const val AUDIO_ASSET = "benchmark/alienista.wav"

    /** What is said in the audio sample; used to measure each Whisper model's accuracy. */
    const val AUDIO_REFERENCE =
        "As crônicas da vila de Itaguaí dizem que, em tempos remotos, vivera ali um certo médico, o doutor Simão " +
            "Bacamarte, filho da nobreza da terra e o maior dos médicos do Brasil, de Portugal e das Espanhas."

    const val AUDIO_CREDIT =
        "Trecho de “O Alienista”, de Machado de Assis, na leitura de Leni para o LibriVox (domínio público)."

    /** A typical messy dictation (55 words) that every language model revises. */
    const val DICTATION =
        "então é tipo assim eu queria avisar que a reunião de amanhã vai ser às três da tarde não pera às quatro " +
            "e aí eu preciso que cada um traga os números do mês né porque o diretor vai querer ver tudo e se alguém " +
            "não puder ir me avisa até hoje à noite tá"

    /** Whisper prompt used in real dictation (see DictationSession). */
    const val WHISPER_PROMPT = "Olá, tudo bem? Esta é uma transcrição em português, com pontuação."

    /** Average speaking rate, used to turn a number of words into seconds of speech. */
    const val WORDS_PER_SECOND = 2.4

    /** Text lengths shown in the results: a short message, a paragraph and a long text. */
    val LENGTHS = listOf(15, 50, 150)
}

data class SpeechBench(
    val modelId: String,
    val loadMs: Long,
    /** Time to transcribe the whole sample. */
    val runMs: Long,
    val audioMs: Long,
    val transcript: String,
    /** Share of the reference words transcribed correctly (0..1). */
    val accuracy: Double,
) {
    /** Real-time factor: processing time per second of speech. */
    val rtf: Double get() = runMs.toDouble() / audioMs.coerceAtLeast(1)
}

data class TextBench(
    val modelId: String,
    val loadMs: Long,
    /** One-time processing of the instructions (done while the user is still talking). */
    val warmupMs: Long,
    val inputWords: Int,
    val inputTokens: Int,
    val outputTokens: Int,
    /** From the request to the first generated token (reading the dictation). */
    val firstTokenMs: Long,
    /** From the request to the last token. */
    val totalMs: Long,
    val output: String,
) {
    /** Tokens per second while writing the answer. */
    val generationRate: Double
        get() = (outputTokens - 1).coerceAtLeast(1) * 1000.0 / (totalMs - firstTokenMs).coerceAtLeast(1)
}

data class BenchmarkReport(
    val createdAt: Long,
    val device: String,
    val ramGb: Double,
    val threads: Int,
    val appVersion: String,
    val speech: List<SpeechBench>,
    val text: List<TextBench>,
    /** Models that could not be tested (failed to load or to run). */
    val failed: List<String> = emptyList(),
) {
    fun speechFor(id: String) = speech.firstOrNull { it.modelId == id }
    fun textFor(id: String) = text.firstOrNull { it.modelId == id }

    fun toJson(): String = JSONObject().apply {
        put("version", 1)
        put("createdAt", createdAt)
        put("device", device)
        put("ramGb", ramGb)
        put("threads", threads)
        put("appVersion", appVersion)
        put("speech", JSONArray().apply {
            speech.forEach {
                put(JSONObject().apply {
                    put("id", it.modelId); put("loadMs", it.loadMs); put("runMs", it.runMs); put("audioMs", it.audioMs)
                    put("transcript", it.transcript); put("accuracy", it.accuracy)
                })
            }
        })
        put("text", JSONArray().apply {
            text.forEach {
                put(JSONObject().apply {
                    put("id", it.modelId); put("loadMs", it.loadMs); put("warmupMs", it.warmupMs)
                    put("inputWords", it.inputWords); put("inputTokens", it.inputTokens); put("outputTokens", it.outputTokens)
                    put("firstTokenMs", it.firstTokenMs); put("totalMs", it.totalMs); put("output", it.output)
                })
            }
        })
        put("failed", JSONArray(failed))
    }.toString()

    companion object {
        /** Reads a saved report; anything malformed is ignored (the test can simply be run again). */
        fun fromJson(json: String): BenchmarkReport? = runCatching {
            val o = JSONObject(json)
            if (o.optInt("version") != 1) return null
            val speech = o.getJSONArray("speech").let { a ->
                (0 until a.length()).map { i ->
                    val s = a.getJSONObject(i)
                    SpeechBench(
                        modelId = s.getString("id"),
                        loadMs = s.getLong("loadMs"),
                        runMs = s.getLong("runMs"),
                        audioMs = s.getLong("audioMs"),
                        transcript = s.getString("transcript"),
                        accuracy = s.getDouble("accuracy"),
                    )
                }
            }
            val text = o.getJSONArray("text").let { a ->
                (0 until a.length()).map { i ->
                    val t = a.getJSONObject(i)
                    TextBench(
                        modelId = t.getString("id"),
                        loadMs = t.getLong("loadMs"),
                        warmupMs = t.getLong("warmupMs"),
                        inputWords = t.getInt("inputWords"),
                        inputTokens = t.getInt("inputTokens"),
                        outputTokens = t.getInt("outputTokens"),
                        firstTokenMs = t.getLong("firstTokenMs"),
                        totalMs = t.getLong("totalMs"),
                        output = t.getString("output"),
                    )
                }
            }
            val failed = o.optJSONArray("failed")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
            BenchmarkReport(
                createdAt = o.getLong("createdAt"),
                device = o.getString("device"),
                ramGb = o.getDouble("ramGb"),
                threads = o.getInt("threads"),
                appVersion = o.getString("appVersion"),
                speech = speech,
                text = text,
                failed = failed,
            )
        }.getOrNull()
    }
}

/** How long one combination of models takes for a text of a given length. */
data class ComboEstimate(
    val speech: ModelSpec,
    /** Null means "no AI revision" (rule-based clean-up only). */
    val text: ModelSpec?,
    /** Waiting time after the user stops talking, per entry of [BenchmarkSamples.LENGTHS]. */
    val waitMs: List<Double>,
    /** True when the times come from this phone's measurements (not scaled from another model). */
    val speechMeasured: Boolean,
    val textMeasured: Boolean,
    /** Measured Whisper accuracy (0..1), when available. */
    val speechAccuracy: Double?,
    /** Overall quality on a 2..10 scale, used to rank and to pick the badges. */
    val quality: Int,
) {
    val measured: Boolean get() = speechMeasured && (text == null || textMeasured)
}

/** Pure calculations behind the speed test (unit-tested; no Android dependencies). */
object BenchmarkMath {

    // --- Accuracy ---------------------------------------------------------------------------

    fun words(text: String): List<String> {
        val lower = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFC)
        return lower.split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.isNotBlank() }
            .map { if (it == "dr") "doutor" else it }
    }

    /** 1 − word error rate, clamped to 0..1. */
    fun wordAccuracy(reference: String, hypothesis: String): Double {
        val ref = words(reference)
        val hyp = words(hypothesis)
        if (ref.isEmpty()) return if (hyp.isEmpty()) 1.0 else 0.0
        var prev = IntArray(hyp.size + 1) { it }
        for (i in 1..ref.size) {
            val cur = IntArray(hyp.size + 1)
            cur[0] = i
            for (j in 1..hyp.size) {
                val cost = if (ref[i - 1] == hyp[j - 1]) 0 else 1
                cur[j] = min(min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost)
            }
            prev = cur
        }
        return (1.0 - prev[hyp.size].toDouble() / ref.size).coerceIn(0.0, 1.0)
    }

    /** Measured accuracy mapped to the 1..5 scale used for the catalog's quality bars. */
    fun accuracyStars(accuracy: Double): Int = when {
        accuracy >= 0.96 -> 5
        accuracy >= 0.92 -> 4
        accuracy >= 0.86 -> 3
        accuracy >= 0.78 -> 2
        else -> 1
    }

    // --- Speech timing ----------------------------------------------------------------------

    private const val SEGMENT_SECONDS = 8.0

    /**
     * Processing time for one stretch of [seconds] of audio. Part of Whisper's cost is fixed per
     * stretch (the encoder always looks at least ~15 s of audio context in fast mode), the rest
     * grows with the amount of speech.
     */
    fun segmentMs(seconds: Double, bench: SpeechBench): Double {
        val benchSeconds = bench.audioMs / 1000.0
        fun context(s: Double) = (s * 50 + 128).coerceIn(750.0, 1500.0)
        val units = 0.5 * context(seconds) / context(benchSeconds) + 0.5 * seconds / benchSeconds
        return bench.runMs * units
    }

    /**
     * Waiting time after the user stops talking. With "transcribe during pauses" each stretch is
     * transcribed while the user keeps speaking, so only the backlog remains at the end.
     */
    fun speechWaitMs(words: Int, bench: SpeechBench, transcribeDuringPauses: Boolean = true): Double {
        val audio = words / BenchmarkSamples.WORDS_PER_SECOND
        if (!transcribeDuringPauses) {
            // Everything is transcribed only at the end, in windows of up to 30 s.
            var total = 0.0
            var left = audio
            while (left > 0.01) {
                val s = min(left, 30.0)
                total += segmentMs(s, bench)
                left -= s
            }
            return total
        }
        // Stretches are cut at natural pauses (~every 8 s) and transcribed one after the other.
        val count = max(1, ceil(audio / SEGMENT_SECONDS).toInt())
        var free = 0.0
        var end = 0.0
        for (i in 0 until count) {
            val length = if (i == count - 1) audio - SEGMENT_SECONDS * (count - 1) else SEGMENT_SECONDS
            end += length
            free = max(free, end * 1000) + segmentMs(length, bench)
        }
        return free - audio * 1000
    }

    // --- Text timing ------------------------------------------------------------------------

    /** Time for the language model to revise [words] words (instructions already cached). */
    fun polishMs(words: Int, bench: TextBench): Double {
        val perWordIn = bench.inputTokens.toDouble() / bench.inputWords.coerceAtLeast(1)
        val perWordOut = bench.outputTokens.toDouble() / bench.inputWords.coerceAtLeast(1)
        val readRate = bench.inputTokens.coerceAtLeast(1) / bench.firstTokenMs.coerceAtLeast(1).toDouble()
        val writeRate = bench.generationRate / 1000.0
        return words * perWordIn / readRate + words * perWordOut / writeRate
    }

    // --- Estimates for models that were not measured -----------------------------------------

    /**
     * Relative compute cost of each model within its kind. Used only to estimate models that are
     * not installed, scaling from the closest model measured on this phone.
     */
    private val knownCost = mapOf(
        "whisper-base-q8" to 1.0,
        "whisper-small-q8" to 3.2,
        "whisper-large-v3-turbo-q5" to 10.0,
        "qwen3.5-0.8b-q8" to 0.85,
        "qwen3.5-2b-q4" to 2.0,
        "gemma-4-e2b-q4" to 2.4,
        "qwen3.5-4b-q4" to 4.2,
    )

    fun relativeCost(spec: ModelSpec): Double = knownCost[spec.id]
        ?: if (spec.kind == ModelKind.SPEECH) spec.sizeBytes / 82_000_000.0 else spec.sizeBytes / 640_000_000.0

    private fun <T> closest(target: ModelSpec, measured: List<Pair<ModelSpec, T>>): Pair<ModelSpec, T>? =
        measured.minByOrNull { (spec, _) -> abs(ln(relativeCost(spec) / relativeCost(target))) }

    fun speechBenchFor(spec: ModelSpec, measured: List<Pair<ModelSpec, SpeechBench>>): Pair<SpeechBench, Boolean>? {
        measured.firstOrNull { it.first.id == spec.id }?.let { return it.second to true }
        val (base, bench) = closest(spec, measured) ?: return null
        val factor = relativeCost(spec) / relativeCost(base)
        return bench.copy(modelId = spec.id, runMs = (bench.runMs * factor).toLong(), transcript = "", accuracy = -1.0) to false
    }

    fun textBenchFor(spec: ModelSpec, measured: List<Pair<ModelSpec, TextBench>>): Pair<TextBench, Boolean>? {
        measured.firstOrNull { it.first.id == spec.id }?.let { return it.second to true }
        val (base, bench) = closest(spec, measured) ?: return null
        val factor = relativeCost(spec) / relativeCost(base)
        return bench.copy(
            modelId = spec.id,
            firstTokenMs = (bench.firstTokenMs * factor).toLong(),
            totalMs = (bench.firstTokenMs * factor + (bench.totalMs - bench.firstTokenMs) * factor).toLong(),
            output = "",
        ) to false
    }

    /**
     * Every combination of a speech model and a text model (or none), with the waiting time for
     * each text length. Models without a measurement are estimated from the closest measured one.
     */
    fun combinations(
        report: BenchmarkReport,
        speechModels: List<ModelSpec>,
        textModels: List<ModelSpec>,
        transcribeDuringPauses: Boolean = true,
        includeWithoutRevision: Boolean = true,
    ): List<ComboEstimate> {
        val measuredSpeech = report.speech.mapNotNull { b -> speechModels.firstOrNull { it.id == b.modelId }?.let { it to b } }
        val measuredText = report.text.mapNotNull { b -> textModels.firstOrNull { it.id == b.modelId }?.let { it to b } }
        val result = mutableListOf<ComboEstimate>()
        for (speech in speechModels) {
            val (sBench, sMeasured) = speechBenchFor(speech, measuredSpeech) ?: continue
            val accuracy = sBench.accuracy.takeIf { sMeasured && it >= 0 }
            val speechStars = accuracy?.let { accuracyStars(it) } ?: speech.quality
            val texts: List<ModelSpec?> = if (includeWithoutRevision) textModels + listOf(null) else textModels
            for (text in texts) {
                val tPair = text?.let { textBenchFor(it, measuredText) ?: continue }
                val waits = BenchmarkSamples.LENGTHS.map { words ->
                    speechWaitMs(words, sBench, transcribeDuringPauses) + (tPair?.first?.let { polishMs(words, it) } ?: 0.0)
                }
                result += ComboEstimate(
                    speech = speech,
                    text = text,
                    waitMs = waits,
                    speechMeasured = sMeasured,
                    textMeasured = tPair?.second ?: true,
                    speechAccuracy = accuracy,
                    quality = speechStars + (text?.quality ?: 1),
                )
            }
        }
        return result.sortedBy { it.waitMs[1] }
    }

    enum class Badge(val label: String) { FASTEST("Mais rápida"), BEST("Melhor qualidade"), BALANCED("Equilíbrio") }

    /** Picks the fastest, the best and the most balanced combination (among AI-revised ones). */
    fun badges(combos: List<ComboEstimate>): Map<ComboEstimate, Badge> {
        val revised = combos.filter { it.text != null }
        if (revised.isEmpty()) return emptyMap()
        val fastest = revised.minBy { it.waitMs[1] }
        val best = revised.maxWith(compareBy<ComboEstimate> { it.quality }.thenByDescending { it.waitMs[1] })
        val limit = max(fastest.waitMs[1] * 2.0, 4000.0)
        val balanced = revised.filter { it.waitMs[1] <= limit }
            .maxWith(compareBy<ComboEstimate> { it.quality }.thenByDescending { it.waitMs[1] })
        val out = linkedMapOf<ComboEstimate, Badge>()
        out[fastest] = Badge.FASTEST
        out[best] = Badge.BEST
        if (balanced !== fastest && balanced !== best) out[balanced] = Badge.BALANCED
        return out
    }

    /** "0,8 s", "12 s", "1 min 05 s" */
    fun formatSeconds(ms: Double): String {
        val s = ms / 1000.0
        return when {
            s < 10 -> String.format(java.util.Locale.ROOT, "%.1f s", s).replace('.', ',')
            s < 60 -> "${s.toInt()} s"
            else -> "${(s / 60).toInt()} min ${String.format(java.util.Locale.ROOT, "%02d", (s % 60).toInt())} s"
        }
    }
}
