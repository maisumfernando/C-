package com.fernandogodinho.teclado.engine

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.fernandogodinho.teclado.BuildConfig
import com.fernandogodinho.teclado.core.DeviceProfile
import com.fernandogodinho.teclado.core.PolishStyle
import com.fernandogodinho.teclado.models.ModelCatalog
import com.fernandogodinho.teclado.models.ModelRepository
import com.fernandogodinho.teclado.models.ModelSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Where the speed test is. */
data class BenchmarkState(
    val running: Boolean = false,
    /** Models to test, in order. */
    val plan: List<ModelSpec> = emptyList(),
    /** Index in [plan] of the model being tested. */
    val current: Int = -1,
    val step: String = "",
    val error: String? = null,
    val report: BenchmarkReport? = null,
)

/**
 * Measures, on this phone, how fast each installed model runs: every Whisper model transcribes
 * the same 15 s recording and every language model revises the same dictation. Only one model is
 * loaded at a time. Nothing leaves the device; the results are kept in a small local file.
 */
class BenchmarkRunner(
    private val context: Context,
    private val engines: EngineManager,
    private val models: ModelRepository,
    private val scope: CoroutineScope,
) {
    private val file = File(context.filesDir, "benchmark.json")
    private val _state = MutableStateFlow(BenchmarkState(report = load()))
    val state: StateFlow<BenchmarkState> = _state.asStateFlow()
    private var job: Job? = null

    private fun load(): BenchmarkReport? =
        runCatching { if (file.isFile) BenchmarkReport.fromJson(file.readText()) else null }.getOrNull()

    fun installedPlan(): List<ModelSpec> {
        val speech = ModelCatalog.speechModels.filter { models.isInstalled(it) }
            .sortedBy { BenchmarkMath.relativeCost(it) }
        val text = ModelCatalog.textModels.filter { models.isInstalled(it) }
            .sortedBy { BenchmarkMath.relativeCost(it) }
        return speech + text
    }

    fun start() {
        if (_state.value.running) return
        val plan = installedPlan()
        if (plan.isEmpty()) {
            _state.update { it.copy(error = "Baixe pelo menos um modelo para fazer o teste.") }
            return
        }
        _state.update { it.copy(running = true, plan = plan, current = 0, step = "Preparando…", error = null) }
        job = scope.launch(Dispatchers.Default) {
            try {
                val report = run(plan)
                withContext(Dispatchers.IO) { runCatching { file.writeText(report.toJson()) } }
                _state.update { it.copy(running = false, current = -1, step = "", report = report) }
            } catch (e: CancellationException) {
                _state.update { it.copy(running = false, current = -1, step = "") }
                throw e
            } catch (t: Throwable) {
                Log.w(TAG, "speed test failed", t)
                _state.update { it.copy(running = false, current = -1, step = "", error = "Não foi possível concluir o teste.") }
            }
        }
    }

    /** Lets screenshot tests show results without running models. */
    @androidx.annotation.VisibleForTesting
    internal fun showPreview(report: BenchmarkReport?) {
        _state.value = BenchmarkState(report = report)
    }

    fun cancel() {
        job?.cancel()
        job = null
    }

    private suspend fun run(plan: List<ModelSpec>): BenchmarkReport {
        // Free the memory held by dictation so each model is measured on its own.
        engines.releaseAllNow()
        val audio = withContext(Dispatchers.IO) { readWav(BenchmarkSamples.AUDIO_ASSET) }
        val audioMs = audio.size * 1000L / SpeechEngine.SAMPLE_RATE
        val speech = mutableListOf<SpeechBench>()
        val text = mutableListOf<TextBench>()
        val failed = mutableListOf<String>()
        val threads = engines.threads

        plan.forEachIndexed { index, spec ->
            _state.update { it.copy(current = index, step = "Carregando ${spec.name}…") }
            if (spec.kind == com.fernandogodinho.teclado.models.ModelKind.SPEECH) {
                val result = engines.withDedicatedSpeech(spec) { engine ->
                    _state.update { it.copy(step = "${spec.name}: transcrevendo 15 s de fala…") }
                    val started = SystemClock.elapsedRealtime()
                    val out = engine.transcribe(
                        samples = audio,
                        offset = 0,
                        length = audio.size,
                        language = "pt",
                        prompt = BenchmarkSamples.WHISPER_PROMPT,
                        threads = threads,
                        fast = true,
                    )
                    (SystemClock.elapsedRealtime() - started) to out
                }
                val (loadMs, pair) = result ?: run { failed += spec.id; return@forEachIndexed }
                val (runMs, out) = pair
                val transcript = out?.text?.let { TranscriptCleaner.cleanSegment(it) }
                if (transcript == null) {
                    failed += spec.id
                } else {
                    speech += SpeechBench(
                        modelId = spec.id,
                        loadMs = loadMs,
                        runMs = runMs,
                        audioMs = audioMs,
                        transcript = transcript,
                        accuracy = BenchmarkMath.wordAccuracy(BenchmarkSamples.AUDIO_REFERENCE, transcript),
                    )
                }
            } else {
                // Text models need the memory more than Whisper does.
                engines.releaseAllNow()
                val result = engines.withDedicatedText(spec) { engine -> runText(spec, engine) }
                val bench = result?.let { (loadMs, b) -> b?.copy(loadMs = loadMs) }
                if (bench == null) failed += spec.id else text += bench
            }
        }
        return BenchmarkReport(
            createdAt = System.currentTimeMillis(),
            device = deviceName(),
            ramGb = DeviceProfile.totalRamGb(context),
            threads = threads,
            appVersion = BuildConfig.VERSION_NAME,
            speech = speech,
            text = text,
            failed = failed,
        )
    }

    private suspend fun runText(spec: ModelSpec, engine: TextEngine): TextBench? {
        val style = PolishStyle.FORMAL
        _state.update { it.copy(step = "${spec.name}: lendo as instruções…") }
        var started = SystemClock.elapsedRealtime()
        engine.warmup(Prompts.prefixMessages(style))
        val warmupMs = SystemClock.elapsedRealtime() - started

        val input = BenchmarkSamples.DICTATION
        val inputTokens = engine.countTokens(input).takeIf { it > 0 } ?: (input.length / 3)
        _state.update { it.copy(step = "${spec.name}: revisando um ditado de 55 palavras…") }
        var firstTokenMs = -1L
        started = SystemClock.elapsedRealtime()
        val output = engine.generate(Prompts.conversation(style, input, null), inputTokens * 2 + 64) {
            if (firstTokenMs < 0) firstTokenMs = SystemClock.elapsedRealtime() - started
        } ?: return null
        val totalMs = SystemClock.elapsedRealtime() - started
        val cleaned = TranscriptCleaner.sanitizeModelOutput(output)
        val outputTokens = engine.countTokens(output).takeIf { it > 0 } ?: (output.length / 3)
        if (cleaned.isBlank() || firstTokenMs < 0) return null
        return TextBench(
            modelId = spec.id,
            loadMs = 0,
            warmupMs = warmupMs,
            inputWords = BenchmarkMath.words(input).size,
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            firstTokenMs = firstTokenMs,
            totalMs = totalMs,
            output = cleaned,
        )
    }

    /** Reads 16-bit mono PCM from a WAV asset as floats. */
    private fun readWav(asset: String): FloatArray {
        val bytes = context.assets.open(asset).use { it.readBytes() }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(bytes.size > 44 && String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WAVE") { "invalid wav" }
        var pos = 12
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4)
            val size = buf.getInt(pos + 4)
            if (id == "data") {
                val start = pos + 8
                val count = (minOf(size, bytes.size - start)) / 2
                return FloatArray(count) { i -> buf.getShort(start + i * 2) / 32768f }
            }
            pos += 8 + size + (size and 1)
        }
        error("wav without data")
    }

    private fun deviceName(): String {
        val maker = Build.MANUFACTURER.orEmpty().replaceFirstChar { it.uppercase() }
        val model = Build.MODEL.orEmpty()
        return if (model.startsWith(maker, ignoreCase = true)) model else "$maker $model".trim()
    }

    companion object {
        private const val TAG = "BenchmarkRunner"
    }
}
