package com.fernandogodinho.teclado.core

import android.content.Context
import com.fernandogodinho.teclado.ime.ui.KeyboardThemeId
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** How aggressively the AI rewrites dictated text. */
enum class PolishStyle(val label: String, val description: String) {
    FORMAL(
        "Norma-padrão",
        "Texto impecável e formal, sem nenhuma marca de oralidade.",
    ),
    NATURAL(
        "Natural",
        "Corrige e limpa a fala, mantendo o seu jeito de escrever.",
    ),
    MESSAGE(
        "Mensagem",
        "Correto e direto, com tom leve para conversas.",
    ),
    OFF(
        "Sem IA",
        "Insere a transcrição apenas com limpeza básica.",
    ),
}

enum class ThemeMode(val label: String) { SYSTEM("Automático"), LIGHT("Claro"), DARK("Escuro") }

enum class OneHandedMode(val label: String) { OFF("Desligado"), LEFT("Esquerda"), RIGHT("Direita") }

/** Languages offered for dictation (Whisper language codes). */
enum class DictationLanguage(val code: String, val label: String) {
    PT("pt", "Português (Brasil)"),
    AUTO("auto", "Detectar automaticamente"),
    EN("en", "English"),
    ES("es", "Español"),
    FR("fr", "Français"),
    IT("it", "Italiano"),
    DE("de", "Deutsch"),
}

data class AppSettings(
    // Models
    val speechModelId: String? = null,
    val textModelId: String? = null,
    // Dictation
    val language: DictationLanguage = DictationLanguage.PT,
    val polishStyle: PolishStyle = PolishStyle.FORMAL,
    val autoInsert: Boolean = true,
    val fastTranscription: Boolean = true,
    val keepModelsLoadedMinutes: Int = 5,
    val vocabulary: Set<String> = emptySet(),
    val pauseToTranscribe: Boolean = true,
    // Keyboard
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val keyboardTheme: KeyboardThemeId = KeyboardThemeId.CLASSIC,
    val numberRow: Boolean = false,
    val haptics: Boolean = true,
    val sound: Boolean = false,
    val keyPopup: Boolean = true,
    val autoCapitalize: Boolean = true,
    val autoCorrect: Boolean = true,
    val suggestions: Boolean = true,
    val doubleSpacePeriod: Boolean = true,
    /** Fraction of the full keyboard height (1.0 = 100%, the largest size; 0.7 = compact). */
    val keyboardHeight: Float = 1.0f,
    val longPressDelayMs: Int = 300,
    val clipboardHistory: Boolean = true,
    val smartPaste: Boolean = true,
    val learnWords: Boolean = true,
    /** Touch and hold the space bar: the whole keyboard becomes a trackpad for the cursor. */
    val spaceTrackpad: Boolean = true,
    /** Slide over the letters to write a word (swipe / gesture typing). */
    val swipeTyping: Boolean = true,
    val adaptToneToApp: Boolean = false,
    val oneHanded: OneHandedMode = OneHandedMode.OFF,
    // TV
    val tvListen: Boolean = false,
    // Updates
    val checkUpdates: Boolean = true,
    // Onboarding
    val onboardingDone: Boolean = false,
)

const val MIN_KEYBOARD_HEIGHT = 0.7f

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "teclado_settings")

/** Persistent settings, observable as a [StateFlow] so the keyboard reacts instantly to changes. */
class SettingsRepository(private val context: Context, scope: CoroutineScope) {

    private object K {
        val speechModel = stringPreferencesKey("speech_model")
        val textModel = stringPreferencesKey("text_model")
        val language = stringPreferencesKey("language")
        val style = stringPreferencesKey("polish_style")
        val autoInsert = booleanPreferencesKey("auto_insert")
        val fast = booleanPreferencesKey("fast_transcription")
        val keepLoaded = intPreferencesKey("keep_loaded_minutes")
        val vocabulary = stringSetPreferencesKey("vocabulary")
        val pauseToTranscribe = booleanPreferencesKey("pause_to_transcribe")
        val theme = stringPreferencesKey("theme")
        val keyboardTheme = stringPreferencesKey("keyboard_theme")
        val numberRow = booleanPreferencesKey("number_row")
        val haptics = booleanPreferencesKey("haptics")
        val sound = booleanPreferencesKey("sound")
        val keyPopup = booleanPreferencesKey("key_popup")
        val autoCap = booleanPreferencesKey("auto_cap")
        val autoCorrect = booleanPreferencesKey("auto_correct")
        val suggestions = booleanPreferencesKey("suggestions")
        val doubleSpace = booleanPreferencesKey("double_space")
        // v2: 100% became the maximum (and default) size; the old 0.8..1.3 scale is not migrated,
        // so everyone starts at the full height.
        val height = floatPreferencesKey("keyboard_height_v2")
        val longPress = intPreferencesKey("long_press_ms")
        val clipboard = booleanPreferencesKey("clipboard_history")
        val smartPaste = booleanPreferencesKey("smart_paste")
        val learnWords = booleanPreferencesKey("learn_words")
        val spaceTrackpad = booleanPreferencesKey("space_trackpad")
        val swipeTyping = booleanPreferencesKey("swipe_typing")
        val adaptTone = booleanPreferencesKey("adapt_tone_to_app")
        val oneHanded = stringPreferencesKey("one_handed")
        val checkUpdates = booleanPreferencesKey("check_updates")
        val tvListen = booleanPreferencesKey("tv_listen")
        val onboarding = booleanPreferencesKey("onboarding_done")
    }

    val settings: StateFlow<AppSettings> = context.dataStore.data
        .map { it.toSettings() }
        .stateIn(scope, SharingStarted.Eagerly, AppSettings())

    val current: AppSettings get() = settings.value

    private val writeScope = scope

    fun update(block: (MutablePreferences) -> Unit) {
        writeScope.launch { context.dataStore.edit { block(it) } }
    }

    fun setSpeechModel(id: String?) = update { if (id == null) it.remove(K.speechModel) else it[K.speechModel] = id }
    fun setTextModel(id: String?) = update { if (id == null) it.remove(K.textModel) else it[K.textModel] = id }
    fun setLanguage(v: DictationLanguage) = update { it[K.language] = v.name }
    fun setPolishStyle(v: PolishStyle) = update { it[K.style] = v.name }
    fun setAutoInsert(v: Boolean) = update { it[K.autoInsert] = v }
    fun setFastTranscription(v: Boolean) = update { it[K.fast] = v }
    fun setKeepLoadedMinutes(v: Int) = update { it[K.keepLoaded] = v }
    fun setPauseToTranscribe(v: Boolean) = update { it[K.pauseToTranscribe] = v }
    fun setTheme(v: ThemeMode) = update { it[K.theme] = v.name }
    fun setKeyboardTheme(v: KeyboardThemeId) = update { it[K.keyboardTheme] = v.name }
    fun setNumberRow(v: Boolean) = update { it[K.numberRow] = v }
    fun setHaptics(v: Boolean) = update { it[K.haptics] = v }
    fun setSound(v: Boolean) = update { it[K.sound] = v }
    fun setKeyPopup(v: Boolean) = update { it[K.keyPopup] = v }
    fun setAutoCapitalize(v: Boolean) = update { it[K.autoCap] = v }
    fun setAutoCorrect(v: Boolean) = update { it[K.autoCorrect] = v }
    fun setSuggestions(v: Boolean) = update { it[K.suggestions] = v }
    fun setDoubleSpacePeriod(v: Boolean) = update { it[K.doubleSpace] = v }
    fun setKeyboardHeight(v: Float) = update { it[K.height] = v.coerceIn(MIN_KEYBOARD_HEIGHT, 1f) }
    fun setLongPressDelay(v: Int) = update { it[K.longPress] = v.coerceIn(150, 700) }
    fun setClipboardHistory(v: Boolean) = update { it[K.clipboard] = v }
    fun setOnboardingDone(v: Boolean) = update { it[K.onboarding] = v }
    fun setSmartPaste(v: Boolean) = update { it[K.smartPaste] = v }
    fun setLearnWords(v: Boolean) = update { it[K.learnWords] = v }
    fun setSpaceTrackpad(v: Boolean) = update { it[K.spaceTrackpad] = v }
    fun setSwipeTyping(v: Boolean) = update { it[K.swipeTyping] = v }
    fun setAdaptToneToApp(v: Boolean) = update { it[K.adaptTone] = v }
    fun setOneHanded(v: OneHandedMode) = update { it[K.oneHanded] = v.name }
    fun setCheckUpdates(v: Boolean) = update { it[K.checkUpdates] = v }
    fun setTvListen(v: Boolean) = update { it[K.tvListen] = v }

    /** Reads the stored settings (the [settings] flow starts with defaults until the file is read). */
    suspend fun load(): AppSettings = context.dataStore.data.first().toSettings()

    fun addVocabulary(term: String) {
        val clean = term.trim()
        if (clean.isEmpty()) return
        update { it[K.vocabulary] = (it[K.vocabulary] ?: emptySet()) + clean }
    }

    fun removeVocabulary(term: String) = update { it[K.vocabulary] = (it[K.vocabulary] ?: emptySet()) - term }

    private fun Preferences.toSettings(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            speechModelId = this[K.speechModel],
            textModelId = this[K.textModel],
            language = enumOr(this[K.language], d.language),
            polishStyle = enumOr(this[K.style], d.polishStyle),
            autoInsert = this[K.autoInsert] ?: d.autoInsert,
            fastTranscription = this[K.fast] ?: d.fastTranscription,
            keepModelsLoadedMinutes = this[K.keepLoaded] ?: d.keepModelsLoadedMinutes,
            vocabulary = this[K.vocabulary] ?: d.vocabulary,
            pauseToTranscribe = this[K.pauseToTranscribe] ?: d.pauseToTranscribe,
            theme = enumOr(this[K.theme], d.theme),
            keyboardTheme = enumOr(this[K.keyboardTheme], d.keyboardTheme),
            numberRow = this[K.numberRow] ?: d.numberRow,
            haptics = this[K.haptics] ?: d.haptics,
            sound = this[K.sound] ?: d.sound,
            keyPopup = this[K.keyPopup] ?: d.keyPopup,
            autoCapitalize = this[K.autoCap] ?: d.autoCapitalize,
            autoCorrect = this[K.autoCorrect] ?: d.autoCorrect,
            suggestions = this[K.suggestions] ?: d.suggestions,
            doubleSpacePeriod = this[K.doubleSpace] ?: d.doubleSpacePeriod,
            keyboardHeight = (this[K.height] ?: d.keyboardHeight).coerceIn(MIN_KEYBOARD_HEIGHT, 1f),
            longPressDelayMs = this[K.longPress] ?: d.longPressDelayMs,
            clipboardHistory = this[K.clipboard] ?: d.clipboardHistory,
            smartPaste = this[K.smartPaste] ?: d.smartPaste,
            learnWords = this[K.learnWords] ?: d.learnWords,
            spaceTrackpad = this[K.spaceTrackpad] ?: d.spaceTrackpad,
            swipeTyping = this[K.swipeTyping] ?: d.swipeTyping,
            adaptToneToApp = this[K.adaptTone] ?: d.adaptToneToApp,
            oneHanded = enumOr(this[K.oneHanded], d.oneHanded),
            checkUpdates = this[K.checkUpdates] ?: d.checkUpdates,
            tvListen = this[K.tvListen] ?: d.tvListen,
            onboardingDone = this[K.onboarding] ?: d.onboardingDone,
        )
    }

    private inline fun <reified T : Enum<T>> enumOr(name: String?, default: T): T =
        name?.let { n -> enumValues<T>().firstOrNull { it.name == n } } ?: default
}
