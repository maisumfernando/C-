package com.fernandogodinho.teclado.ime

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.fernandogodinho.teclado.TecladoApp
import com.fernandogodinho.teclado.core.PolishStyle
import com.fernandogodinho.teclado.core.OneHandedMode
import com.fernandogodinho.teclado.dictation.DictationMode
import com.fernandogodinho.teclado.dictation.DictationPhase
import com.fernandogodinho.teclado.dictation.DictationSession
import com.fernandogodinho.teclado.dictation.DictationUiState
import com.fernandogodinho.teclado.ime.layout.FieldKind
import com.fernandogodinho.teclado.ime.layout.KeyAction
import com.fernandogodinho.teclado.ime.layout.KeySpec
import com.fernandogodinho.teclado.ime.layout.LayoutMode
import com.fernandogodinho.teclado.engine.TextPolisher
import com.fernandogodinho.teclado.engine.TextTools
import com.fernandogodinho.teclado.engine.WritingTool
import com.fernandogodinho.teclado.ime.suggest.Dictionary
import com.fernandogodinho.teclado.ui.MainActivity
import com.fernandogodinho.teclado.ui.PermissionActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.BreakIterator

enum class ShiftState { OFF, ON, LOCKED }

enum class Panel { KEYBOARD, EMOJI, CLIPBOARD, DICTATION, TOOLS }

data class PasteChip(val label: String, val text: String)

enum class EnterKind { NEWLINE, GO, SEARCH, SEND, NEXT, DONE, PREVIOUS }

data class Suggestion(val text: String, val verbatim: Boolean = false, val autoCorrect: Boolean = false)

/** Text inserted by the last dictation, so the user can swap between polished and original. */
/** A text revision applied by the AI, kept so it can be undone. */
data class Revision(val original: String, val revised: String)

data class InsertedDictation(val polished: String, val raw: String, val inserted: String, val showingOriginal: Boolean)

/**
 * Keyboard state and all editing logic. The Compose UI only renders this state and forwards
 * key events; everything that touches the text field lives here.
 */
class KeyboardController(
    private val service: TecladoIme,
    private val app: TecladoApp,
    private val scope: CoroutineScope,
) {
    // ---------------------------------------------------------------------------------------
    // Observable UI state
    // ---------------------------------------------------------------------------------------
    var layoutMode by mutableStateOf(LayoutMode.LETTERS)
        private set
    var shift by mutableStateOf(ShiftState.OFF)
        private set
    var panel by mutableStateOf(Panel.KEYBOARD)
        private set
    var fieldKind by mutableStateOf(FieldKind.TEXT)
        private set
    /**
     * True in password fields and in fields that ask for no personalised learning (incognito
     * browser tabs, banking apps...). Nothing typed there is learned, suggested, recorded in the
     * clipboard history or shown in key previews.
     */
    var privateField by mutableStateOf(false)
        private set
    var privateReason by mutableStateOf(PrivateReason.NONE)
        private set
    /** Private mode switched on by the user with the toolbar button (kept until switched off). */
    private var manualPrivate = false
    /** Password fields: the keyboard window is also protected from screenshots and recording. */
    val secureField: Boolean get() = fieldKind == FieldKind.PASSWORD
    var enterKind by mutableStateOf(EnterKind.NEWLINE)
        private set
    var suggestions by mutableStateOf<List<Suggestion>>(emptyList())
        private set
    var dictation by mutableStateOf<DictationSession?>(null)
        private set
    var lastDictation by mutableStateOf<InsertedDictation?>(null)
        private set
    var notice by mutableStateOf<String?>(null)
        private set
    var revising by mutableStateOf(false)
        private set
    var revisingLabel by mutableStateOf("Revisando o texto…")
        private set
    var pasteChip by mutableStateOf<PasteChip?>(null)
        private set
    /** True while dictating by holding the space bar (the keyboard stays visible). */
    var quickDictation by mutableStateOf(false)
        private set
    private var dismissedPaste: String? = null
    var lastRevision by mutableStateOf<Revision?>(null)
        private set
    private var revisionJob: Job? = null

    val clipboard = ClipboardHistory(service)

    private var dictionary: Dictionary? = null
    private var editorInfo: EditorInfo? = null
    private val ic: InputConnection? get() = service.currentInputConnection

    private val settings get() = app.settings.current

    // Typing state
    private val word = StringBuilder()
    private var cursorBelief = -1
    private var resyncJob: Job? = null
    private var suggestJob: Job? = null
    private var noticeJob: Job? = null
    private var lastCorrection: Pair<String, String>? = null // original -> corrected
    private var autoSpace = false
    private var lastSpaceAt = 0L
    private var lastShiftTapAt = 0L
    private var backspaceRepeats = 0

    init {
        clipboard.onChange = { refreshPasteChip() }
        scope.launch(Dispatchers.Default) {
            val dict = runCatching { Dictionary.load(service) }.getOrNull() ?: return@launch
            dict.addUserWords(app.settings.current.vocabulary + app.learnedWords.words.value)
            dictionary = dict
        }
        scope.launch {
            app.learnedWords.words.collect { learned ->
                dictionary?.addUserWords(app.settings.current.vocabulary + learned)
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------------------------

    fun onStartInput(info: EditorInfo) {
        editorInfo = info
        val cls = info.inputType and InputType.TYPE_MASK_CLASS
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        fieldKind = when {
            cls == InputType.TYPE_CLASS_TEXT && (variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS) -> FieldKind.EMAIL
            cls == InputType.TYPE_CLASS_TEXT && variation == InputType.TYPE_TEXT_VARIATION_URI -> FieldKind.URL
            isPasswordField(info) -> FieldKind.PASSWORD
            else -> FieldKind.TEXT
        }
        layoutMode = when (cls) {
            InputType.TYPE_CLASS_NUMBER -> LayoutMode.NUMBERS
            InputType.TYPE_CLASS_PHONE -> LayoutMode.PHONE
            InputType.TYPE_CLASS_DATETIME -> LayoutMode.NUMBERS
            else -> LayoutMode.LETTERS
        }
        enterKind = enterKindFor(info)
        applyPrivacy(info)
        if (panel != Panel.DICTATION) panel = Panel.KEYBOARD
        shift = ShiftState.OFF
        lastCorrection = null
        autoSpace = false
        cursorBelief = info.initialSelStart
        if (dictation == null) lastDictation = null
        resyncWord()
        updateAutoCaps()
        refreshPasteChip()
    }

    /** Decides whether this field is private: passwords, incognito tabs, privacy apps or the user's toggle. */
    private fun applyPrivacy(info: EditorInfo) {
        privateReason = when {
            fieldKind == FieldKind.PASSWORD -> PrivateReason.PASSWORD
            (info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0 -> PrivateReason.INCOGNITO
            settings.autoPrivateApps && PrivacyApps.matches(info.packageName) -> PrivateReason.PRIVACY_APP
            manualPrivate -> PrivateReason.MANUAL
            else -> PrivateReason.NONE
        }
        privateField = privateReason != PrivateReason.NONE
        clipboard.paused = privateField
        if (privateField) pasteChip = pasteChip?.takeIf { codeLikeField() }
    }

    /** Toolbar button: private mode on/off, like the incognito switch of other keyboards. */
    fun togglePrivateMode() {
        val automatic = privateReason == PrivateReason.PASSWORD || privateReason == PrivateReason.INCOGNITO ||
            privateReason == PrivateReason.PRIVACY_APP
        if (automatic && !manualPrivate) {
            showNotice("Modo privado automático: ${privateReason.label.lowercase()}")
            return
        }
        manualPrivate = !manualPrivate
        editorInfo?.let { applyPrivacy(it) }
        showNotice(
            if (manualPrivate) "Modo privado ligado: nada do que você digitar será aprendido ou guardado"
            else "Modo privado desligado",
        )
    }

    fun onFinishInput() {
        word.clear()
        suggestions = emptyList()
        resyncJob?.cancel()
    }

    fun onUpdateSelection(newSelStart: Int, newSelEnd: Int) {
        if (newSelStart == newSelEnd && newSelStart == cursorBelief) return
        cursorBelief = newSelStart
        resyncJob?.cancel()
        resyncJob = scope.launch {
            delay(45)
            if (newSelStart != newSelEnd) {
                word.clear()
                publishSuggestions()
                return@launch
            }
            resyncWord()
            updateAutoCaps()
        }
    }

    fun onWindowHidden() {
        // Without a visible keyboard there is no field to insert into: abandon the dictation.
        if (dictation?.state?.value?.phase.let { it == DictationPhase.LISTENING || it == DictationPhase.STARTING }) {
            cancelDictation()
        }
        if (panel == Panel.EMOJI || panel == Panel.CLIPBOARD) panel = Panel.KEYBOARD
    }

    // ---------------------------------------------------------------------------------------
    // Keys
    // ---------------------------------------------------------------------------------------

    fun onKey(key: KeySpec) = onAction(key.action)

    fun onAction(action: KeyAction) {
        when (action) {
            is KeyAction.Text -> commitCharacter(action.text)
            KeyAction.Space -> onSpace()
            KeyAction.Backspace -> onBackspace(repeat = false)
            KeyAction.Enter -> onEnter()
            KeyAction.Shift -> onShift()
            KeyAction.ToSymbols -> { layoutMode = LayoutMode.SYMBOLS; shift = ShiftState.OFF }
            KeyAction.ToMoreSymbols -> layoutMode = LayoutMode.MORE_SYMBOLS
            KeyAction.ToLetters -> { layoutMode = LayoutMode.LETTERS; updateAutoCaps() }
            KeyAction.Emoji -> showPanel(Panel.EMOJI)
            KeyAction.Mic -> startDictation()
        }
    }

    /** Text chosen from a long-press popup, the emoji panel or the clipboard. */
    fun commitText(text: String, fromPopup: Boolean = false) {
        if (fromPopup && text.length == 1) commitCharacter(text) else commitRaw(text)
    }

    fun displayLabel(key: KeySpec): String {
        if (!key.isCharacter || layoutMode != LayoutMode.LETTERS) return key.label
        return if (shift != ShiftState.OFF) key.label.uppercase() else key.label
    }

    fun displayAlternate(text: String): String =
        if (shift != ShiftState.OFF && layoutMode == LayoutMode.LETTERS) text.uppercase() else text

    private fun commitCharacter(raw: String) {
        val ic = ic ?: return
        val text = if (shift != ShiftState.OFF && layoutMode == LayoutMode.LETTERS) raw.uppercase() else raw
        val isWordChar = text.length == 1 && (text[0].isLetterOrDigit() || text[0] == '\'' || text[0] == '-')

        if (!isWordChar && text.length == 1 && text[0] in SMART_PUNCTUATION && autoSpace) {
            // "palavra |" + "," -> "palavra, |"
            ic.beginBatchEdit()
            if (ic.getTextBeforeCursor(1, 0)?.toString() == " ") {
                ic.deleteSurroundingText(1, 0)
                cursorBelief -= 1
            }
            ic.commitText("$text ", 1)
            ic.endBatchEdit()
            cursorBelief += 2
            word.clear()
            autoSpace = true
        } else {
            ic.commitText(text, 1)
            cursorBelief += text.length
            if (isWordChar) word.append(text) else word.clear()
            autoSpace = false
        }
        lastCorrection = null
        if (shift == ShiftState.ON) shift = ShiftState.OFF
        if (!isWordChar) updateAutoCaps()
        publishSuggestions()
    }

    private fun commitRaw(text: String) {
        val ic = ic ?: return
        ic.commitText(text, 1)
        cursorBelief += text.length
        word.clear()
        autoSpace = false
        lastCorrection = null
        publishSuggestions()
        updateAutoCaps()
    }

    private fun onSpace() {
        val ic = ic ?: return
        val now = SystemClock.uptimeMillis()
        val typed = word.toString()

        if (settings.doubleSpacePeriod && now - lastSpaceAt < 450 && typed.isEmpty() && fieldKind == FieldKind.TEXT) {
            val before = ic.getTextBeforeCursor(2, 0)?.toString().orEmpty()
            if (before.length == 2 && before[1] == ' ' && before[0].isLetterOrDigit()) {
                ic.beginBatchEdit()
                ic.deleteSurroundingText(1, 0)
                ic.commitText(". ", 1)
                ic.endBatchEdit()
                cursorBelief += 1
                lastSpaceAt = 0
                autoSpace = true
                updateAutoCaps()
                return
            }
        }
        lastSpaceAt = now

        val correction = if (autoCorrectAllowed() && layoutMode == LayoutMode.LETTERS && typed.isNotEmpty()) {
            dictionary?.autoCorrection(typed)?.let { matchCase(it, typed) }?.takeIf { it != typed }
        } else {
            null
        }
        if (correction != null) {
            ic.beginBatchEdit()
            ic.deleteSurroundingText(typed.length, 0)
            ic.commitText("$correction ", 1)
            ic.endBatchEdit()
            cursorBelief += correction.length - typed.length + 1
            lastCorrection = typed to correction
        } else {
            ic.commitText(" ", 1)
            cursorBelief += 1
            lastCorrection = null
        }
        word.clear()
        autoSpace = false
        if (layoutMode != LayoutMode.LETTERS && layoutMode != LayoutMode.NUMBERS && layoutMode != LayoutMode.PHONE) {
            layoutMode = LayoutMode.LETTERS
        }
        publishSuggestions()
        updateAutoCaps()
    }

    fun onBackspace(repeat: Boolean) {
        val ic = ic ?: return
        if (!repeat) backspaceRepeats = 0 else backspaceRepeats++

        lastCorrection?.let { (original, corrected) ->
            lastCorrection = null
            val before = ic.getTextBeforeCursor(corrected.length + 1, 0)?.toString()
            if (before == "$corrected ") {
                ic.beginBatchEdit()
                ic.deleteSurroundingText(corrected.length + 1, 0)
                ic.commitText(original, 1)
                ic.endBatchEdit()
                cursorBelief += original.length - corrected.length - 1
                word.clear().append(original)
                learn(original) // the user rejected the correction: remember the word
                publishSuggestions()
                return
            }
        }
        autoSpace = false

        val selected = ic.getSelectedText(0)
        if (!selected.isNullOrEmpty()) {
            ic.commitText("", 1)
            word.clear()
            resyncWord()
            return
        }

        // Long presses accelerate to deleting whole words.
        if (backspaceRepeats > 18) {
            val before = ic.getTextBeforeCursor(64, 0)?.toString().orEmpty()
            if (before.isNotEmpty()) {
                val trimmed = before.trimEnd()
                val start = trimmed.lastIndexOfAny(charArrayOf(' ', '\n')).let { if (it < 0) 0 else it + 1 }
                val count = (before.length - start).coerceAtLeast(1)
                ic.deleteSurroundingText(count, 0)
                cursorBelief -= count
                word.clear()
                resyncWord()
                return
            }
        }

        val before = ic.getTextBeforeCursor(16, 0)?.toString()
        if (before == null) {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
            return
        }
        if (before.isEmpty()) {
            // Nothing to delete in our view: let the app handle it (e.g. deleting a chip).
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
            return
        }
        val count = lastGraphemeLength(before)
        ic.deleteSurroundingText(count, 0)
        cursorBelief -= count
        if (word.isNotEmpty()) {
            word.setLength((word.length - count).coerceAtLeast(0))
            publishSuggestions()
        } else {
            resyncWord()
        }
        updateAutoCaps()
    }

    private fun onEnter() {
        val ic = ic ?: return
        val info = editorInfo
        word.clear()
        autoSpace = false
        lastCorrection = null
        val action = info?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: EditorInfo.IME_ACTION_NONE
        if (enterKind != EnterKind.NEWLINE && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            ic.performEditorAction(action)
        } else {
            ic.commitText("\n", 1)
            cursorBelief += 1
        }
        publishSuggestions()
        updateAutoCaps()
    }

    private fun onShift() {
        val now = SystemClock.uptimeMillis()
        shift = when (shift) {
            ShiftState.OFF -> ShiftState.ON
            ShiftState.ON -> if (now - lastShiftTapAt < 350) ShiftState.LOCKED else ShiftState.OFF
            ShiftState.LOCKED -> ShiftState.OFF
        }
        lastShiftTapAt = now
    }

    /** Moves the cursor by [chars] characters (space-bar swipe). */
    fun moveCursor(chars: Int) {
        val ic = ic ?: return
        if (chars == 0) return
        val code = if (chars < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
        repeat(kotlin.math.abs(chars)) {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        }
        lastCorrection = null
        autoSpace = false
    }

    fun showInputMethodPicker() {
        val imm = service.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
        imm?.showInputMethodPicker()
    }

    fun showPanel(p: Panel) {
        panel = p
    }

    fun openApp(route: String? = null) {
        val intent = Intent(service, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (route != null) intent.putExtra(MainActivity.EXTRA_ROUTE, route)
        service.startActivity(intent)
    }

    // ---------------------------------------------------------------------------------------
    // Suggestions
    // ---------------------------------------------------------------------------------------

    fun pickSuggestion(s: Suggestion) {
        val ic = ic ?: return
        val typed = word.toString()
        ic.beginBatchEdit()
        if (typed.isNotEmpty()) ic.deleteSurroundingText(typed.length, 0)
        ic.commitText(s.text + " ", 1)
        ic.endBatchEdit()
        cursorBelief += s.text.length - typed.length + 1
        if (s.verbatim && dictionary?.isWord(s.text) != true) learn(s.text)
        word.clear()
        autoSpace = true
        lastCorrection = null
        if (shift == ShiftState.ON) shift = ShiftState.OFF
        publishSuggestions()
        updateAutoCaps()
    }

    /** Remembers a word typed by the user, never in private fields and never digits-only tokens. */
    private fun learn(word: String) {
        if (privateField || !settings.learnWords) return
        if (word.any { it.isDigit() } || word.length > 30) return
        app.learnedWords.add(word)
    }

    private fun publishSuggestions() {
        suggestJob?.cancel()
        val typed = word.toString()
        if (typed.isNotEmpty()) pasteChip = null
        if (typed.isEmpty() || !settings.suggestions || secureField || layoutMode != LayoutMode.LETTERS) {
            suggestions = emptyList()
            return
        }
        val dict = dictionary ?: run {
            suggestions = listOf(Suggestion(typed, verbatim = true))
            return
        }
        val auto = autoCorrectAllowed()
        suggestJob = scope.launch {
            val result = withContext(Dispatchers.Default) {
                val correction = if (auto) dict.autoCorrection(typed)?.let { matchCase(it, typed) } else null
                val candidates = dict.candidates(typed, 4).map { matchCase(it.word, typed) }
                buildSuggestions(typed, correction, candidates)
            }
            suggestions = result
        }
    }

    private fun buildSuggestions(typed: String, correction: String?, candidates: List<String>): List<Suggestion> {
        val list = mutableListOf<Suggestion>()
        val rest = candidates.filter { it != typed && it != correction }
        val center = correction ?: rest.firstOrNull()
        list += Suggestion(typed, verbatim = true)
        if (center != null) list += Suggestion(center, autoCorrect = correction != null)
        rest.filter { it != center }.firstOrNull()?.let { list += Suggestion(it) }
        return list
    }

    private fun autoCorrectAllowed(): Boolean {
        val type = editorInfo?.inputType ?: 0
        val noSuggestions = type and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0
        return settings.autoCorrect && fieldKind == FieldKind.TEXT && !noSuggestions &&
            enterKind != EnterKind.SEARCH && enterKind != EnterKind.GO
    }

    private fun matchCase(word: String, typed: String): String = when {
        typed.length > 1 && typed.all { !it.isLetter() || it.isUpperCase() } -> word.uppercase()
        typed.firstOrNull()?.isUpperCase() == true -> word.replaceFirstChar { it.uppercaseChar() }
        else -> word
    }

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

    private fun resyncWord() {
        val before = ic?.getTextBeforeCursor(48, 0)?.toString().orEmpty()
        word.clear()
        var i = before.length
        while (i > 0) {
            val c = before[i - 1]
            if (c.isLetterOrDigit() || c == '\'' || c == '-') i-- else break
        }
        word.append(before, i, before.length)
        publishSuggestions()
    }

    fun updateAutoCaps() {
        if (!settings.autoCapitalize || layoutMode != LayoutMode.LETTERS || shift == ShiftState.LOCKED) return
        val info = editorInfo ?: return
        val ic = ic ?: return
        val cls = info.inputType and InputType.TYPE_MASK_CLASS
        if (cls != InputType.TYPE_CLASS_TEXT || fieldKind != FieldKind.TEXT) {
            if (shift == ShiftState.ON) shift = ShiftState.OFF
            return
        }
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        val searchLike = enterKind == EnterKind.SEARCH || enterKind == EnterKind.GO
        val wantsCaps = if (variation in SENTENCE_VARIATIONS && !searchLike) {
            val before = ic.getTextBeforeCursor(64, 0)?.toString() ?: ""
            sentenceStart(before)
        } else {
            ic.getCursorCapsMode(info.inputType) != 0
        }
        shift = if (wantsCaps) ShiftState.ON else ShiftState.OFF
    }

    private fun sentenceStart(before: String): Boolean {
        val trimmed = before.trimEnd(' ', ' ')
        if (trimmed.isEmpty()) return true
        if (trimmed.length < before.length || before.endsWith("\n")) {
            val last = trimmed.last()
            return last == '.' || last == '!' || last == '?' || last == '\n' || last == '…'
        }
        return false
    }

    private fun lastGraphemeLength(text: String): Int {
        val it = BreakIterator.getCharacterInstance()
        it.setText(text)
        val end = it.last()
        val start = it.previous()
        return if (start == BreakIterator.DONE) 1 else (end - start).coerceAtLeast(1)
    }

    private fun isPasswordField(info: EditorInfo): Boolean {
        val cls = info.inputType and InputType.TYPE_MASK_CLASS
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        return (cls == InputType.TYPE_CLASS_TEXT && (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)) ||
            (cls == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
    }

    private fun enterKindFor(info: EditorInfo): EnterKind {
        if (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0) return EnterKind.NEWLINE
        return when (info.imeOptions and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_GO -> EnterKind.GO
            EditorInfo.IME_ACTION_SEARCH -> EnterKind.SEARCH
            EditorInfo.IME_ACTION_SEND -> EnterKind.SEND
            EditorInfo.IME_ACTION_NEXT -> EnterKind.NEXT
            EditorInfo.IME_ACTION_DONE -> EnterKind.DONE
            EditorInfo.IME_ACTION_PREVIOUS -> EnterKind.PREVIOUS
            else -> EnterKind.NEWLINE
        }
    }

    private fun showNotice(message: String) {
        notice = message
        noticeJob?.cancel()
        noticeJob = scope.launch {
            delay(3500)
            notice = null
        }
    }

    // ---------------------------------------------------------------------------------------
    // AI writing tools (everything runs on the device)
    // ---------------------------------------------------------------------------------------

    fun hideKeyboard() {
        service.requestHideSelf(0)
    }

    /** Opens the writing tools for the selected text, or for the whole field. */
    fun openWritingTools() {
        if (secureField) {
            showNotice("As ferramentas de IA ficam desligadas em campos de senha")
            return
        }
        if (app.engines.selectedTextModel() == null) {
            showNotice("Baixe um modelo de texto no app do teclado para usar a IA")
            openApp(MainActivity.ROUTE_MODELS)
            return
        }
        // Start loading the model while the user picks a tool.
        app.appScope.launch { runCatching { app.engines.text() } }
        panel = Panel.TOOLS
    }

    /** Short description of what the tools will change, for the panel header. */
    fun toolTarget(): String {
        val selected = ic?.getSelectedText(0)?.toString()
        return if (!selected.isNullOrBlank()) "Trecho selecionado · ${selected.length} caracteres" else "Todo o texto do campo"
    }

    fun reviseText() = runTool(WritingTool.REVISE)

    /** Applies [tool] (or a spoken [instruction]) to the selection or the whole field. */
    fun runTool(tool: WritingTool, instruction: String? = null) {
        val ic = ic ?: return
        panel = Panel.KEYBOARD
        if (secureField) return
        val selected = ic.getSelectedText(0)?.toString()
        val target: String
        val replaceSelection: Boolean
        val beforeLen: Int
        val afterLen: Int
        if (!selected.isNullOrBlank()) {
            target = selected
            replaceSelection = true
            beforeLen = 0
            afterLen = 0
        } else {
            val before = ic.getTextBeforeCursor(MAX_REVISION_CHARS, 0)?.toString().orEmpty()
            val after = ic.getTextAfterCursor(MAX_REVISION_CHARS, 0)?.toString().orEmpty()
            target = before + after
            replaceSelection = false
            beforeLen = before.length
            afterLen = after.length
        }
        if (target.isBlank()) {
            showNotice("Escreva ou selecione um texto primeiro")
            return
        }
        if (target.length >= MAX_REVISION_CHARS) {
            showNotice("Texto muito longo: selecione o trecho que deseja alterar")
            return
        }
        val style = settings.polishStyle.takeIf { it != PolishStyle.OFF } ?: PolishStyle.FORMAL
        revising = true
        revisingLabel = tool.progressLabel
        lastRevision = null
        revisionJob?.cancel()
        revisionJob = scope.launch {
            val result = withContext(Dispatchers.Default) {
                runCatching {
                    app.engines.touch()
                    if (tool == WritingTool.REVISE) {
                        TextPolisher(app.engines).polish(target, style, settings.vocabulary) { }.takeIf { it.usedModel }?.text
                    } else {
                        TextTools(app.engines).apply(tool, target, instruction)
                    }
                }.getOrNull()
            }
            revising = false
            if (result.isNullOrBlank()) {
                showNotice("Não foi possível concluir agora. Tente de novo.")
                return@launch
            }
            val conn = this@KeyboardController.ic ?: return@launch
            conn.beginBatchEdit()
            if (replaceSelection) {
                if (conn.getSelectedText(0)?.toString() != target) {
                    conn.endBatchEdit()
                    showNotice("O texto mudou enquanto a IA trabalhava")
                    return@launch
                }
                conn.commitText(result, 1)
            } else {
                val nowBefore = conn.getTextBeforeCursor(MAX_REVISION_CHARS, 0)?.toString().orEmpty()
                val nowAfter = conn.getTextAfterCursor(MAX_REVISION_CHARS, 0)?.toString().orEmpty()
                if (nowBefore.length != beforeLen || nowAfter.length != afterLen || nowBefore + nowAfter != target) {
                    conn.endBatchEdit()
                    showNotice("O texto mudou enquanto a IA trabalhava")
                    return@launch
                }
                conn.deleteSurroundingText(beforeLen, afterLen)
                conn.commitText(result, 1)
            }
            conn.endBatchEdit()
            lastRevision = Revision(target, result)
            word.clear()
            publishSuggestions()
        }
    }

    /** Dictate an instruction ("transforme em um e-mail para o meu chefe") for the selected text. */
    fun startVoiceInstruction() {
        startDictation(forcedMode = DictationMode.INSTRUCTION)
    }

    fun cancelRevision() {
        revisionJob?.cancel()
        revising = false
    }

    fun undoRevision() {
        val rev = lastRevision ?: return
        val conn = ic ?: return
        lastRevision = null
        val before = conn.getTextBeforeCursor(rev.revised.length, 0)?.toString()
        if (before != rev.revised) {
            showNotice("O texto foi alterado; não é possível desfazer")
            return
        }
        conn.beginBatchEdit()
        conn.deleteSurroundingText(rev.revised.length, 0)
        conn.commitText(rev.original, 1)
        conn.endBatchEdit()
    }

    fun dismissRevision() {
        lastRevision = null
    }

    // ---------------------------------------------------------------------------------------
    // Smart paste and quick editing
    // ---------------------------------------------------------------------------------------

    /** Offers what was just copied (or the code inside it) as a one-tap paste. */
    fun refreshPasteChip() {
        val clip = clipboard.recent()
        pasteChip = when {
            !settings.smartPaste || clip == null || clip.text == dismissedPaste -> null
            codeLikeField() -> OTP_REGEX.find(clip.text)?.value?.let { PasteChip("Colar código $it", it) }
            privateField -> null
            else -> PasteChip("Colar “${clip.text.replace('\n', ' ').take(28)}${if (clip.text.length > 28) "…" else ""}”", clip.text)
        }
    }

    fun pastePasteChip() {
        val chip = pasteChip ?: return
        commitRaw(chip.text)
        dismissedPaste = clipboard.recent()?.text
        pasteChip = null
    }

    fun dismissPasteChip() {
        dismissedPaste = clipboard.recent()?.text
        pasteChip = null
    }

    /** Deletes the word before the cursor (backspace swipe). */
    fun deleteWordBefore() {
        val ic = ic ?: return
        val before = ic.getTextBeforeCursor(64, 0)?.toString().orEmpty()
        if (before.isEmpty()) return
        val trimmed = before.trimEnd()
        val start = trimmed.lastIndexOfAny(charArrayOf(' ', '\n')).let { if (it < 0) 0 else it + 1 }
        val count = (before.length - start).coerceAtLeast(1)
        ic.deleteSurroundingText(count, 0)
        cursorBelief -= count
        word.clear()
        lastCorrection = null
        resyncWord()
        updateAutoCaps()
    }

    fun toggleOneHanded() {
        app.settings.setOneHanded(if (settings.oneHanded == OneHandedMode.OFF) OneHandedMode.RIGHT else OneHandedMode.OFF)
    }

    fun switchOneHandedSide() {
        app.settings.setOneHanded(if (settings.oneHanded == OneHandedMode.LEFT) OneHandedMode.RIGHT else OneHandedMode.LEFT)
    }

    private fun codeLikeField(): Boolean {
        val info = editorInfo ?: return false
        val cls = info.inputType and InputType.TYPE_MASK_CLASS
        return cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE || looksLikeCodeField(info)
    }

    private fun looksLikeCodeField(info: EditorInfo): Boolean {
        val hint = listOfNotNull(info.hintText, info.label, info.fieldName).joinToString(" ").lowercase()
        if (hint.isBlank()) return false
        return CODE_HINTS.any { it in hint }
    }

    // ---------------------------------------------------------------------------------------
    // Dictation
    // ---------------------------------------------------------------------------------------

    fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(service, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** The kind of text the focused field expects. */
    private fun dictationModeForField(): DictationMode {
        val info = editorInfo ?: return DictationMode.TEXT
        val cls = info.inputType and InputType.TYPE_MASK_CLASS
        return when {
            fieldKind == FieldKind.PASSWORD -> if (cls == InputType.TYPE_CLASS_NUMBER) DictationMode.CODE else DictationMode.SECRET
            cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE || cls == InputType.TYPE_CLASS_DATETIME -> DictationMode.CODE
            looksLikeCodeField(info) -> DictationMode.CODE_ALNUM
            fieldKind == FieldKind.EMAIL || fieldKind == FieldKind.URL -> DictationMode.ADDRESS
            else -> DictationMode.TEXT
        }
    }

    /** Adapts the tone to the app when the user enabled it (messages vs. e-mail). */
    private fun effectiveStyle(): PolishStyle {
        val s = settings
        if (!s.adaptToneToApp || s.polishStyle == PolishStyle.OFF) return s.polishStyle
        val pkg = editorInfo?.packageName ?: return s.polishStyle
        return when {
            MESSAGING_APPS.any { pkg.startsWith(it) } -> PolishStyle.MESSAGE
            EMAIL_APPS.any { pkg.startsWith(it) } -> PolishStyle.FORMAL
            else -> s.polishStyle
        }
    }

    /**
     * Starts dictating. With [quick] (holding the space bar) the keyboard stays on screen and the
     * text is inserted as soon as the user lets go.
     */
    fun startDictation(quick: Boolean = false, forcedMode: DictationMode? = null) {
        if (dictation != null) return
        if (!hasMicPermission()) {
            service.startActivity(
                Intent(service, PermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return
        }
        if (app.engines.selectedSpeechModel() == null) {
            showNotice("Baixe um modelo de voz no app do teclado para ditar")
            openApp(MainActivity.ROUTE_MODELS)
            return
        }
        val s = settings
        val mode = forcedMode ?: dictationModeForField()
        val session = DictationSession(
            app = app,
            scope = app.appScope,
            style = if (mode == DictationMode.TEXT) effectiveStyle() else PolishStyle.OFF,
            language = s.language,
            mode = mode,
            // Passwords never see the personal vocabulary or any other context.
            vocabulary = if (mode == DictationMode.SECRET) emptyList() else s.vocabulary.toList(),
            onFinished = { state -> scope.launch { onDictationFinished(state) } },
        )
        lastDictation = null
        pasteChip = null
        quickDictation = quick
        dictation = session
        panel = if (quick) Panel.KEYBOARD else Panel.DICTATION
        session.start()
    }

    @androidx.annotation.VisibleForTesting
    internal fun showPreview(session: DictationSession?, suggestions: List<Suggestion> = emptyList(), shift: ShiftState = ShiftState.OFF) {
        dictation = session
        panel = if (session != null) Panel.DICTATION else Panel.KEYBOARD
        this.suggestions = suggestions
        this.shift = shift
    }

    fun finishDictation() {
        dictation?.finish()
    }

    fun cancelDictation() {
        dictation?.cancel()
        dictation = null
        quickDictation = false
        panel = Panel.KEYBOARD
        updateAutoCaps()
    }

    private fun onDictationFinished(state: DictationUiState) {
        val session = dictation ?: return
        val text = state.finalText.trim()
        if (text.isEmpty()) {
            dictation = null
            quickDictation = false
            panel = Panel.KEYBOARD
            showNotice("Não ouvi nada. Tente falar mais perto do microfone.")
            return
        }
        when {
            session.mode == DictationMode.INSTRUCTION -> {
                dictation = null
                quickDictation = false
                runTool(WritingTool.CUSTOM, instruction = text)
            }
            // Passwords are always confirmed on screen (masked) before they go into the field.
            session.mode == DictationMode.SECRET || fieldKind == FieldKind.PASSWORD -> panel = Panel.DICTATION
            session.mode != DictationMode.TEXT || settings.autoInsert -> insertDictation(state)
            else -> panel = Panel.DICTATION
        }
    }

    /** Inserts the dictated text at the cursor with sensible spacing and capitalisation. */
    fun insertDictation(state: DictationUiState, original: Boolean = false) {
        val ic = ic
        val mode = state.mode
        val polished = state.finalText.trim()
        val raw = state.transcript.trim().ifEmpty { polished }
        val text = if (original) raw else polished
        dictation = null
        quickDictation = false
        panel = Panel.KEYBOARD
        if (ic == null || text.isEmpty()) return
        val inserted = if (mode == DictationMode.TEXT) adaptToContext(ic, text) else text
        ic.commitText(inserted, 1)
        cursorBelief += inserted.length
        // Codes and passwords leave no trace: no "original" toggle is kept for them.
        lastDictation = if (mode == DictationMode.TEXT) InsertedDictation(polished, raw, inserted, showingOriginal = original) else null
        word.clear()
        autoSpace = false
        lastCorrection = null
        publishSuggestions()
        updateAutoCaps()
    }

    /** Swaps the inserted dictation between the polished and the original transcript. */
    fun toggleDictationVersion() {
        val last = lastDictation ?: return
        val ic = ic ?: return
        val before = ic.getTextBeforeCursor(last.inserted.length, 0)?.toString()
        if (before != last.inserted) {
            showNotice("O texto foi alterado; não é possível trocar a versão")
            lastDictation = null
            return
        }
        val target = if (last.showingOriginal) last.polished else last.raw
        val replacement = adaptToContext(ic, target, ignoreChars = last.inserted.length)
        ic.beginBatchEdit()
        ic.deleteSurroundingText(last.inserted.length, 0)
        ic.commitText(replacement, 1)
        ic.endBatchEdit()
        cursorBelief += replacement.length - last.inserted.length
        lastDictation = last.copy(inserted = replacement, showingOriginal = !last.showingOriginal)
    }

    /** Removes the text inserted by the last dictation. */
    fun undoDictation() {
        val last = lastDictation ?: return
        val ic = ic ?: return
        lastDictation = null
        val before = ic.getTextBeforeCursor(last.inserted.length, 0)?.toString()
        if (before != last.inserted) {
            showNotice("O texto foi alterado; não é possível desfazer")
            return
        }
        ic.deleteSurroundingText(last.inserted.length, 0)
        cursorBelief -= last.inserted.length
        resyncWord()
        updateAutoCaps()
    }

    fun dismissDictationChip() {
        lastDictation = null
    }

    private fun adaptToContext(ic: InputConnection, text: String, ignoreChars: Int = 0): String {
        val before = ic.getTextBeforeCursor(ignoreChars + 2, 0)?.toString()?.dropLast(ignoreChars).orEmpty()
        val last = before.lastOrNull()
        var t = text
        // Mid-sentence insertion: continue the sentence instead of starting a new one.
        val midSentence = last != null && (last.isLetterOrDigit() || last == ',' ||
            (last == ' ' && before.dropLast(1).lastOrNull()?.let { it.isLetterOrDigit() || it == ',' } == true))
        if (midSentence) {
            val firstWord = t.substringBefore(' ').trimEnd(',', '.', '!', '?')
            if (firstWord.lowercase() in SENTENCE_STARTERS && firstWord.firstOrNull()?.isUpperCase() == true) {
                t = t[0].lowercaseChar() + t.substring(1)
            }
        }
        if (last != null && !last.isWhitespace() && t.firstOrNull()?.let { it in ",.;:!?)" } != true) {
            t = " $t"
        }
        return t
    }

    companion object {
        private const val MAX_REVISION_CHARS = 4000
        private val OTP_REGEX = Regex("(?<![0-9])[0-9]{4,8}(?![0-9])")
        private val CODE_HINTS = listOf("código", "codigo", "code", "otp", "verifica", "token", "2fa", "pin", "uso único")
        private val MESSAGING_APPS = listOf(
            "com.whatsapp", "org.telegram", "com.facebook.orca", "com.instagram.android", "org.thoughtcrime.securesms",
            "com.discord", "com.Slack", "com.google.android.apps.messaging", "com.samsung.android.messaging",
            "jp.naver.line.android", "com.viber.voip", "com.snapchat.android", "com.twitter.android", "com.huawei.message",
            "com.android.mms", "com.google.android.apps.dynamite",
        )
        private val EMAIL_APPS = listOf(
            "com.google.android.gm", "com.microsoft.office.outlook", "com.samsung.android.email", "com.huawei.email",
            "ch.protonmail.android", "com.yahoo.mobile.client.android.mail", "com.microsoft.office.word",
            "com.google.android.apps.docs", "com.linkedin.android",
        )
        private const val SMART_PUNCTUATION = ".,;:!?)"
        /** Common words that are safe to lowercase when dictation continues an existing sentence. */
        private val SENTENCE_STARTERS = setOf(
            "eu", "a", "o", "as", "os", "um", "uma", "e", "mas", "que", "se", "não", "para", "por", "com", "como",
            "quando", "então", "também", "já", "ele", "ela", "nós", "eles", "elas", "isso", "isto", "este", "esta",
            "esse", "essa", "aqui", "lá", "hoje", "amanhã", "ontem", "depois", "antes", "agora", "ainda", "só",
            "muito", "mais", "porque", "pois", "porém", "além", "talvez", "acho", "vou", "estou", "tenho", "preciso",
        )
        private val SENTENCE_VARIATIONS = setOf(
            InputType.TYPE_TEXT_VARIATION_NORMAL,
            InputType.TYPE_TEXT_VARIATION_LONG_MESSAGE,
            InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE,
            InputType.TYPE_TEXT_VARIATION_EMAIL_SUBJECT,
            InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT,
        )
    }
}
