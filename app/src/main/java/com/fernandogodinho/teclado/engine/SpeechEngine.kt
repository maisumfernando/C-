package com.fernandogodinho.teclado.engine

import com.fernandogodinho.teclado.models.ModelSpec
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.math.ceil

data class Transcript(val text: String, val language: String)

/** Whisper speech-to-text bound to one loaded model. */
class SpeechEngine(
    val handle: Long,
    val spec: ModelSpec,
    private val dispatcher: CoroutineDispatcher,
    private val vadModelPath: String,
) {
    @Volatile
    private var aborted = false

    private val beamSize = if (spec.id.contains("large")) 3 else 1

    fun abort() {
        aborted = true
        NativeBridge.whisperAbort(handle)
    }

    /**
     * Transcribes [length] samples of 16 kHz mono PCM starting at [offset].
     * With [fast] enabled the encoder only processes the audio actually present instead of a full
     * 30 s window; if the result looks degenerate it is redone with the full window.
     */
    suspend fun transcribe(
        samples: FloatArray,
        offset: Int,
        length: Int,
        language: String,
        prompt: String,
        threads: Int,
        fast: Boolean,
    ): Transcript? {
        val job = coroutineContext[Job]
        val registration = job?.invokeOnCompletion { cause -> if (cause != null) NativeBridge.whisperAbort(handle) }
        try {
            return withContext(dispatcher) {
                if (aborted) return@withContext null
                val seconds = length / SAMPLE_RATE.toDouble()
                val reducedCtx = if (fast) reducedAudioContext(seconds) else 0
                var result = run(samples, offset, length, language, prompt, threads, reducedCtx)
                if (reducedCtx > 0 && result != null && TranscriptCleaner.looksDegenerate(result.text, seconds)) {
                    result = run(samples, offset, length, language, prompt, threads, 0)
                }
                result
            }
        } finally {
            registration?.dispose()
        }
    }

    private fun run(
        samples: FloatArray, offset: Int, length: Int, language: String, prompt: String, threads: Int, audioCtx: Int,
    ): Transcript? {
        val out = NativeBridge.whisperTranscribe(
            handle, samples, offset, length, language, prompt, threads, beamSize, audioCtx, vadModelPath, 0.6f,
        ) ?: return null
        return Transcript(text = out.getOrElse(0) { "" }, language = out.getOrElse(1) { "" })
    }

    private fun reducedAudioContext(seconds: Double): Int {
        // 50 encoder frames per second of audio, plus margin. Never below 15 s: very small
        // contexts make Whisper prone to repetitions.
        val frames = ceil(seconds * 50).toInt() + 128
        return if (frames >= FULL_CONTEXT - 100) 0 else frames.coerceAtLeast(MIN_REDUCED_CONTEXT)
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        private const val FULL_CONTEXT = 1500
        private const val MIN_REDUCED_CONTEXT = 750
    }
}

/** Streaming Silero voice-activity detector (32 ms windows). */
class VadEngine(private val handle: Long) {
    fun reset() = NativeBridge.vadReset(handle)

    /** Returns one speech probability per 512 samples. */
    fun process(samples: FloatArray, length: Int): FloatArray = NativeBridge.vadProcess(handle, samples, length)

    companion object {
        const val WINDOW = 512
    }
}
