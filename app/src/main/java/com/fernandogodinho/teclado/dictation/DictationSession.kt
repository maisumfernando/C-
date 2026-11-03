package com.fernandogodinho.teclado.dictation

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.fernandogodinho.teclado.TecladoApp
import com.fernandogodinho.teclado.core.DictationLanguage
import com.fernandogodinho.teclado.core.PolishStyle
import com.fernandogodinho.teclado.engine.PolishResult
import com.fernandogodinho.teclado.engine.SpeechEngine
import com.fernandogodinho.teclado.engine.SpokenText
import com.fernandogodinho.teclado.engine.TextPolisher
import com.fernandogodinho.teclado.engine.TranscriptCleaner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

enum class DictationPhase { STARTING, LISTENING, FINISHING, POLISHING, DONE, ERROR, CANCELLED }

/** How speech becomes text, chosen from the focused field. */
enum class DictationMode {
    /** Prose: transcribed, then rewritten by the language model in the chosen style. */
    TEXT,
    /** Verification codes and numbers: digits only, inserted immediately. */
    CODE,
    /** Alphanumeric codes (e.g. "G-58A2"). */
    CODE_ALNUM,
    /** Passwords spelled out loud. Never revised, never auto-inserted, never stored. */
    SECRET,
    /** E-mail addresses and links ("arroba", "ponto com"...). */
    ADDRESS,
    /** A spoken instruction for the writing tools (not inserted into the field). */
    INSTRUCTION,
}

data class DictationUiState(
    val phase: DictationPhase = DictationPhase.STARTING,
    /** Raw (cleaned) transcript of everything transcribed so far. */
    val transcript: String = "",
    /** True while a stretch of audio is being transcribed. */
    val transcribing: Boolean = false,
    /** Polished text, streamed while the language model writes it. */
    val polished: String = "",
    val usedModel: Boolean = false,
    val speaking: Boolean = false,
    val elapsedMs: Long = 0,
    val language: String = "",
    val error: String? = null,
    val modelLoading: Boolean = false,
    val mode: DictationMode = DictationMode.TEXT,
) {
    val finalText: String get() = polished.ifBlank { transcript }
}

/**
 * One dictation from microphone to finished text.
 *
 * While the user speaks, a streaming VAD finds natural pauses; each stretch of speech is
 * transcribed in the background immediately, so when the user stops only the last few seconds
 * remain. The full transcript is then rewritten by the local language model.
 */
class DictationSession(
    private val app: TecladoApp,
    private val scope: CoroutineScope,
    private val style: PolishStyle,
    private val language: DictationLanguage,
    val mode: DictationMode = DictationMode.TEXT,
    private val vocabulary: Collection<String>,
    private val onFinished: (DictationUiState) -> Unit,
) {
    private val _state = MutableStateFlow(DictationUiState(mode = mode))
    val state: StateFlow<DictationUiState> = _state.asStateFlow()

    /** Recent input levels (0..1), newest last, for the waveform. */
    private val _levels = MutableStateFlow(FloatArray(LEVEL_HISTORY))
    val levels: StateFlow<FloatArray> = _levels.asStateFlow()

    private val engines = app.engines
    private val settings = app.settings.current

    private var pcm = FloatArray(SAMPLE_RATE * 60)
    @Volatile private var pcmSize = 0
    private val pcmLock = Any()

    private val segments = Channel<IntRange>(Channel.UNLIMITED)
    @Volatile private var recording = false
    @Volatile private var cancelled = false
    private var audioThread: Thread? = null
    private var transcriberJob: Job? = null
    private var pipelineJob: Job? = null
    private var polishJob: Job? = null
    @Volatile private var skipPolishRequested = false
    private val transcript = StringBuilder()

    // Segmenter state (audio thread only)
    private var segmentStart = 0
    private var speechSamplesInSegment = 0
    private var silenceSamples = 0
    private var lastSpeechEnd = 0
    private var speaking = false
    private val recentProbs = ArrayDeque<Float>()

    private val polishes: Boolean get() = mode == DictationMode.TEXT && style != PolishStyle.OFF

    fun start() {
        val polish = polishes && engines.polishingAvailable()
        engines.prewarm(withText = polish)
        _state.update { it.copy(phase = DictationPhase.STARTING, modelLoading = !engines.status.value.speechReady) }

        transcriberJob = scope.launch(Dispatchers.Default) { runTranscriber() }
        recording = true
        audioThread = Thread({ recordLoop() }, "teclado-audio").also { it.start() }
    }

    /** Stops listening and produces the final text. */
    fun finish() {
        if (!recording) return
        recording = false
    }

    /** Lets screenshot tests render the panel in any state without a microphone. */
    @androidx.annotation.VisibleForTesting
    internal fun showPreviewState(state: DictationUiState, levels: FloatArray? = null) {
        _state.value = state
        if (levels != null) _levels.value = levels
    }

    /** Stops the AI revision and uses the cleaned-up transcript instead. */
    fun skipPolish() {
        skipPolishRequested = true
        polishJob?.cancel()
    }

    fun cancel() {
        cancelled = true
        polishJob?.cancel()
        recording = false
        segments.close()
        transcriberJob?.cancel()
        pipelineJob?.cancel()
        wipeAudio()
        _state.update { it.copy(phase = DictationPhase.CANCELLED) }
    }

    // ---------------------------------------------------------------------------------------
    // Audio capture + segmentation
    // ---------------------------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun recordLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val record = createRecorder()
        if (record == null) {
            fail("Não foi possível acessar o microfone")
            return
        }
        val vad = runCatching { engines.vad() }.getOrNull()
        vad?.reset()
        val frame = FloatArray(FRAME)
        val shorts = ShortArray(FRAME)
        val isFloat = record.audioFormat == AudioFormat.ENCODING_PCM_FLOAT
        val started = SystemClock.elapsedRealtime()
        var lastUiUpdate = 0L
        try {
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                fail("O microfone está sendo usado por outro aplicativo")
                return
            }
            _state.update { it.copy(phase = DictationPhase.LISTENING) }
            while (recording && !cancelled) {
                var filled = 0
                while (filled < FRAME && recording) {
                    val n = if (isFloat) {
                        record.read(frame, filled, FRAME - filled, AudioRecord.READ_BLOCKING)
                    } else {
                        val r = record.read(shorts, filled, FRAME - filled)
                        for (i in 0 until max(r, 0)) frame[filled + i] = shorts[filled + i] / 32768f
                        r
                    }
                    if (n < 0) {
                        fail("Erro ao ler o microfone ($n)")
                        return
                    }
                    filled += n
                }
                if (filled < FRAME) break
                append(frame)
                updateLevel(frame)

                val prob = vad?.process(frame, FRAME)?.firstOrNull() ?: energyProbability(frame)
                segment(prob)

                val elapsed = SystemClock.elapsedRealtime() - started
                if (elapsed - lastUiUpdate >= 100 || speaking != _state.value.speaking) {
                    lastUiUpdate = elapsed
                    _state.update { it.copy(elapsedMs = elapsed, speaking = speaking) }
                }
                if (pcmSize >= MAX_SAMPLES) break
            }
        } catch (t: Throwable) {
            Log.e(TAG, "recording failed", t)
            fail("Falha na gravação")
            return
        } finally {
            runCatching { record.stop() }
            record.release()
        }
        if (cancelled) return

        // Flush the remaining audio as the final segment.
        val end = pcmSize
        if (end > segmentStart && (speechSamplesInSegment >= MIN_SPEECH_SAMPLES || vad == null)) {
            segments.trySend(segmentStart until end)
        }
        segments.close()
        pipelineJob = scope.launch(Dispatchers.Default) { finishPipeline() }
    }

    @SuppressLint("MissingPermission")
    private fun createRecorder(): AudioRecord? {
        val sources = listOf(MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC)
        for (source in sources) {
            for (encoding in listOf(AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_16BIT)) {
                val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, encoding)
                if (minBuf <= 0) continue
                val record = runCatching {
                    AudioRecord(source, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, encoding, max(minBuf, FRAME * 16))
                }.getOrNull() ?: continue
                if (record.state == AudioRecord.STATE_INITIALIZED) return record
                record.release()
            }
        }
        return null
    }

    private fun append(frame: FloatArray) {
        synchronized(pcmLock) {
            if (pcmSize + frame.size > pcm.size) {
                pcm = pcm.copyOf(min(MAX_SAMPLES + FRAME, pcm.size * 2))
            }
            System.arraycopy(frame, 0, pcm, pcmSize, frame.size)
            pcmSize += frame.size
        }
    }

    private fun updateLevel(frame: FloatArray) {
        var sum = 0.0
        for (s in frame) sum += s * s
        val rms = sqrt(sum / frame.size).toFloat()
        // Perceptual mapping: ~-60 dBFS → 0, ~-12 dBFS → 1
        val db = 20 * kotlin.math.log10(rms.coerceAtLeast(1e-6f))
        val level = ((db + 60f) / 48f).coerceIn(0f, 1f)
        val history = _levels.value
        val next = FloatArray(history.size)
        System.arraycopy(history, 1, next, 0, history.size - 1)
        next[next.size - 1] = level
        _levels.value = next
    }

    private fun energyProbability(frame: FloatArray): Float {
        var sum = 0.0
        for (s in frame) sum += s * s
        val rms = sqrt(sum / frame.size)
        return if (rms > 0.01) 0.9f else 0.1f
    }

    /** Decides where to cut the audio so each piece can be transcribed while the user keeps talking. */
    private fun segment(prob: Float) {
        val pos = pcmSize
        speaking = if (speaking) prob > VAD_OFF else prob > VAD_ON
        if (speaking) {
            speechSamplesInSegment += FRAME
            silenceSamples = 0
            lastSpeechEnd = pos
        } else {
            silenceSamples += FRAME
        }
        recentProbs.addLast(prob)
        if (recentProbs.size > FORCE_LOOKBACK_FRAMES) recentProbs.removeFirst()

        val length = pos - segmentStart
        val pauseCut = settings.pauseToTranscribe &&
            silenceSamples >= PAUSE_SAMPLES &&
            speechSamplesInSegment >= MIN_SPEECH_SAMPLES &&
            length >= MIN_SEGMENT_SAMPLES
        if (pauseCut) {
            val cut = min(pos, lastSpeechEnd + PAD_SAMPLES)
            emit(cut)
            return
        }
        if (!settings.pauseToTranscribe) return

        if (length >= MAX_SEGMENT_SAMPLES) {
            // Cut at the quietest moment of the last few seconds to avoid splitting a word.
            var minIdx = recentProbs.size - 1
            for (i in recentProbs.indices) if (recentProbs[i] < recentProbs[minIdx]) minIdx = i
            val cut = pos - (recentProbs.size - 1 - minIdx) * FRAME
            emit(max(cut, segmentStart + MIN_SEGMENT_SAMPLES))
        } else if (!speaking && speechSamplesInSegment == 0 && length >= SILENCE_SKIP_SAMPLES) {
            // Long silence without speech: skip it so it is never transcribed.
            segmentStart = pos - PAD_SAMPLES
        }
    }

    private fun emit(cut: Int) {
        if (speechSamplesInSegment >= MIN_SPEECH_SAMPLES) {
            segments.trySend(segmentStart until cut)
        }
        segmentStart = cut
        speechSamplesInSegment = 0
        recentProbs.clear()
    }

    // ---------------------------------------------------------------------------------------
    // Transcription + polishing
    // ---------------------------------------------------------------------------------------

    private suspend fun runTranscriber() {
        val engine: SpeechEngine? = engines.speech()
        _state.update { it.copy(modelLoading = false) }
        if (engine == null) {
            fail(engines.status.value.lastError ?: "Nenhum modelo de voz instalado")
            return
        }
        for (range in segments) {
            if (cancelled) return
            _state.update { it.copy(transcribing = true) }
            val copy: FloatArray
            synchronized(pcmLock) {
                val from = range.first.coerceIn(0, pcmSize)
                val to = (range.last + 1).coerceIn(from, pcmSize)
                copy = pcm.copyOfRange(from, to)
            }
            if (copy.isEmpty()) continue
            val result = runCatching {
                engine.transcribe(
                    samples = copy,
                    offset = 0,
                    length = copy.size,
                    language = language.code,
                    prompt = whisperPrompt(),
                    threads = engines.threads,
                    fast = settings.fastTranscription,
                )
            }.getOrNull()
            engines.touch()
            val text = result?.text?.let { TranscriptCleaner.cleanSegment(it) }.orEmpty()
            if (text.isNotBlank()) {
                if (transcript.isNotEmpty()) transcript.append(' ')
                transcript.append(text)
            }
            _state.update {
                it.copy(
                    transcript = transcript.toString(),
                    transcribing = false,
                    language = result?.language?.ifBlank { it.language } ?: it.language,
                )
            }
        }
    }

    /**
     * Whisper conditions on the prompt as if it were the preceding text: the user's vocabulary
     * teaches spelling and the tail of the transcript keeps sentences flowing across segments.
     */
    private fun whisperPrompt(): String {
        when (mode) {
            // Bias Whisper towards writing digits rather than words.
            DictationMode.CODE, DictationMode.CODE_ALNUM -> return "Código: 4 8 2 9 1 3. 7 0 5 5 1 2."
            DictationMode.ADDRESS -> return "fernando.godinho@gmail.com, www.exemplo.com.br"
            // No context at all for passwords: nothing about the user leaks into the decoder.
            DictationMode.SECRET -> return ""
            else -> Unit
        }
        val vocab = vocabulary.take(30).joinToString(", ")
        val tail = transcript.takeLast(180).let { t -> t.substring(t.indexOf(' ').coerceAtLeast(0)).trim() }
        val seed = when (language) {
            DictationLanguage.PT, DictationLanguage.AUTO -> "Olá, tudo bem? Esta é uma transcrição em português, com pontuação."
            else -> ""
        }
        return buildString {
            if (vocab.isNotEmpty()) append(vocab).append(". ")
            append(tail.ifEmpty { seed })
        }.trim()
    }

    /** Overwrites the recorded audio so it does not linger in memory after use. */
    private fun wipeAudio() {
        synchronized(pcmLock) {
            pcm.fill(0f)
            pcmSize = 0
        }
    }

    private suspend fun finishPipeline() {
        _state.update { it.copy(phase = DictationPhase.FINISHING) }
        transcriberJob?.join()
        wipeAudio()
        if (cancelled || _state.value.phase == DictationPhase.ERROR) return

        val raw = transcript.toString().trim()
        if (raw.isEmpty()) {
            _state.update { it.copy(phase = DictationPhase.DONE, transcript = "", polished = "") }
            onFinished(_state.value)
            return
        }

        if (mode != DictationMode.TEXT) {
            val text = when (mode) {
                DictationMode.CODE -> SpokenText.code(raw, allowLetters = false)
                DictationMode.CODE_ALNUM -> SpokenText.code(raw, allowLetters = true)
                DictationMode.SECRET -> SpokenText.secret(raw)
                DictationMode.ADDRESS -> SpokenText.address(raw)
                else -> TranscriptCleaner.tidy(raw)
            }
            _state.update { it.copy(phase = DictationPhase.DONE, polished = text, usedModel = false) }
            onFinished(_state.value)
            return
        }

        val polish = polishes && engines.polishingAvailable()
        if (!polish) {
            val text = TranscriptCleaner.basicPolish(raw)
            _state.update { it.copy(phase = DictationPhase.DONE, polished = text, usedModel = false) }
            onFinished(_state.value)
            return
        }

        _state.update { it.copy(phase = DictationPhase.POLISHING) }
        var result: PolishResult? = null
        val job = scope.launch(Dispatchers.Default) {
            result = try {
                TextPolisher(engines).polish(raw, style, vocabulary) { partial ->
                    if (!skipPolishRequested) _state.update { it.copy(polished = partial) }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                null
            } catch (e: Throwable) {
                Log.w(TAG, "polish failed", e)
                null
            }
        }
        polishJob = job
        job.join()
        engines.touch()
        if (cancelled) return
        val polished = result
        val final = polished?.text?.ifBlank { null } ?: TranscriptCleaner.basicPolish(raw)
        _state.update { it.copy(phase = DictationPhase.DONE, polished = final, usedModel = polished?.usedModel == true) }
        onFinished(_state.value)
    }

    private fun fail(message: String) {
        recording = false
        segments.close()
        _state.update { it.copy(phase = DictationPhase.ERROR, error = message, transcribing = false) }
    }

    companion object {
        private const val TAG = "DictationSession"
        const val SAMPLE_RATE = 16_000
        private const val FRAME = 512
        const val LEVEL_HISTORY = 48
        private const val VAD_ON = 0.5f
        private const val VAD_OFF = 0.35f
        private const val PAUSE_SAMPLES = SAMPLE_RATE * 650 / 1000
        private const val PAD_SAMPLES = SAMPLE_RATE * 250 / 1000
        private const val MIN_SPEECH_SAMPLES = SAMPLE_RATE * 300 / 1000
        private const val MIN_SEGMENT_SAMPLES = SAMPLE_RATE * 3
        private const val MAX_SEGMENT_SAMPLES = SAMPLE_RATE * 24
        private const val SILENCE_SKIP_SAMPLES = SAMPLE_RATE * 4
        private const val FORCE_LOOKBACK_FRAMES = (SAMPLE_RATE * 5) / FRAME
        private const val MAX_SAMPLES = SAMPLE_RATE * 60 * 10
    }
}
