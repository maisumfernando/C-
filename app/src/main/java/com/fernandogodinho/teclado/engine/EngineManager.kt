package com.fernandogodinho.teclado.engine

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.util.Log
import com.fernandogodinho.teclado.core.AppSettings
import com.fernandogodinho.teclado.core.DeviceProfile
import com.fernandogodinho.teclado.core.PolishStyle
import com.fernandogodinho.teclado.core.SettingsRepository
import com.fernandogodinho.teclado.models.ModelCatalog
import com.fernandogodinho.teclado.models.ModelRepository
import com.fernandogodinho.teclado.models.ModelSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

data class EngineStatus(
    val speechLoading: Boolean = false,
    val speechReady: Boolean = false,
    val textLoading: Boolean = false,
    val textReady: Boolean = false,
    val lastError: String? = null,
)

/**
 * Owns the native models. Loads them on demand, keeps them warm while dictation is in use
 * and frees the memory after a period of inactivity (or when the system is low on memory).
 */
class EngineManager(
    private val context: Context,
    private val settings: SettingsRepository,
    private val models: ModelRepository,
    private val scope: CoroutineScope,
) {
    /** One thread per engine: native contexts are not thread-safe and must not block each other. */
    // Large stacks: template rendering and model loading in native code can recurse deeply.
    val speechDispatcher = Executors.newSingleThreadExecutor { r -> Thread(null, r, "teclado-speech", NATIVE_STACK) }.asCoroutineDispatcher()
    val textDispatcher = Executors.newSingleThreadExecutor { r -> Thread(null, r, "teclado-text", NATIVE_STACK) }.asCoroutineDispatcher()

    private val speechMutex = Mutex()
    private val textMutex = Mutex()

    private var speech: SpeechEngine? = null
    private var text: TextEngine? = null
    private var vad: VadEngine? = null

    private val _status = MutableStateFlow(EngineStatus())
    val status: StateFlow<EngineStatus> = _status.asStateFlow()

    private var idleJob: Job? = null

    val threads: Int get() = DeviceProfile.inferenceThreads

    init {
        // Swap engines when the user picks a different model.
        scope.launch {
            settings.settings.map { it.speechModelId }.distinctUntilChanged().collect { id ->
                speechMutex.withLock {
                    if (speech != null && speech?.spec?.id != id) releaseSpeechLocked()
                }
            }
        }
        scope.launch {
            settings.settings.map { it.textModelId }.distinctUntilChanged().collect { id ->
                textMutex.withLock {
                    if (text != null && text?.spec?.id != id) releaseTextLocked()
                }
            }
        }
        context.registerComponentCallbacks(object : ComponentCallbacks2 {
            override fun onConfigurationChanged(newConfig: Configuration) = Unit
            @Deprecated("Deprecated in Java")
            override fun onLowMemory() = releaseAll()
            override fun onTrimMemory(level: Int) {
                @Suppress("DEPRECATION")
                if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL && level != ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
                    releaseAll()
                }
            }
        })
    }

    fun ensureNative() = NativeBridge.ensureInitialized(context)

    fun selectedSpeechModel(s: AppSettings = settings.current): ModelSpec? =
        ModelCatalog.byId(s.speechModelId)?.takeIf { models.isInstalled(it) }
            ?: ModelCatalog.speechModels.firstOrNull { models.isInstalled(it) }

    fun selectedTextModel(s: AppSettings = settings.current): ModelSpec? =
        ModelCatalog.byId(s.textModelId)?.takeIf { models.isInstalled(it) }
            ?: ModelCatalog.textModels.firstOrNull { models.isInstalled(it) }

    fun polishingAvailable(s: AppSettings = settings.current): Boolean =
        s.polishStyle != PolishStyle.OFF && selectedTextModel(s) != null

    // ---------------------------------------------------------------------------------------
    // Speech
    // ---------------------------------------------------------------------------------------

    suspend fun speech(): SpeechEngine? = speechMutex.withLock {
        touch()
        val spec = selectedSpeechModel() ?: return@withLock null
        speech?.takeIf { it.spec.id == spec.id }?.let { return@withLock it }
        releaseSpeechLocked()
        _status.value = _status.value.copy(speechLoading = true, lastError = null)
        val engine = withContext(speechDispatcher) {
            ensureNative()
            val handle = NativeBridge.whisperLoad(models.file(spec).absolutePath, true)
            if (handle == 0L) null else SpeechEngine(handle, spec, speechDispatcher, vadModelPath())
        }
        speech = engine
        _status.value = _status.value.copy(
            speechLoading = false,
            speechReady = engine != null,
            lastError = if (engine == null) "Não foi possível carregar o modelo de voz" else null,
        )
        engine
    }

    /** Streaming VAD used while recording. Cheap (under 1 MB) so it is kept for the app lifetime. */
    fun vad(): VadEngine? {
        vad?.let { return it }
        return synchronized(this) {
            vad ?: run {
                ensureNative()
                val handle = NativeBridge.vadLoad(vadModelPath())
                if (handle == 0L) null else VadEngine(handle).also { vad = it }
            }
        }
    }

    private fun vadModelPath(): String {
        val file = File(context.filesDir, "silero-vad.bin")
        if (!file.isFile || file.length() == 0L) {
            context.assets.open("models/silero-vad.bin").use { input ->
                file.outputStream().use { input.copyTo(it) }
            }
        }
        return file.absolutePath
    }

    // ---------------------------------------------------------------------------------------
    // Text
    // ---------------------------------------------------------------------------------------

    suspend fun text(): TextEngine? = textMutex.withLock {
        touch()
        val spec = selectedTextModel() ?: return@withLock null
        text?.takeIf { it.spec.id == spec.id }?.let { return@withLock it }
        releaseTextLocked()
        _status.value = _status.value.copy(textLoading = true, lastError = null)
        val engine = withContext(textDispatcher) {
            ensureNative()
            val handle = NativeBridge.llmLoad(models.file(spec).absolutePath, TextEngine.CONTEXT_SIZE, threads)
            if (handle == 0L) null else TextEngine(handle, spec, textDispatcher)
        }
        text = engine
        _status.value = _status.value.copy(
            textLoading = false,
            textReady = engine != null,
            lastError = if (engine == null) "Não foi possível carregar o modelo de texto" else null,
        )
        engine
    }

    /** Loads both models in the background so they are ready when the user stops talking. */
    fun prewarm(withText: Boolean) {
        scope.launch {
            runCatching { speech() }.onFailure { Log.w(TAG, "speech prewarm failed", it) }
        }
        if (withText) {
            scope.launch {
                runCatching {
                    val engine = text() ?: return@runCatching
                    engine.warmup(Prompts.prefixMessages(settings.current.polishStyle))
                }.onFailure { Log.w(TAG, "text prewarm failed", it) }
            }
        }
    }

    /** Marks the engines as used and (re)schedules automatic unloading. */
    @Synchronized
    fun touch() {
        idleJob?.cancel()
        val minutes = settings.current.keepModelsLoadedMinutes
        idleJob = scope.launch {
            delay(minutes.coerceAtLeast(1) * 60_000L)
            releaseAll()
        }
    }

    fun releaseAll() {
        scope.launch {
            speechMutex.withLock { releaseSpeechLocked() }
            textMutex.withLock { releaseTextLocked() }
        }
    }

    private suspend fun releaseSpeechLocked() {
        val engine = speech ?: return
        speech = null
        engine.abort()
        withContext(speechDispatcher) { NativeBridge.whisperFree(engine.handle) }
        _status.value = _status.value.copy(speechReady = false)
    }

    private suspend fun releaseTextLocked() {
        val engine = text ?: return
        text = null
        engine.abort()
        withContext(textDispatcher) { NativeBridge.llmFree(engine.handle) }
        _status.value = _status.value.copy(textReady = false)
    }

    companion object {
        private const val TAG = "EngineManager"
        private const val NATIVE_STACK = 16L * 1024 * 1024
    }
}
