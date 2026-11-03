package app.escriba.ime

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
import app.escriba.EscribaApp
import app.escriba.core.PolishStyle
import app.escriba.dictation.DictationPhase
import app.escriba.dictation.DictationSession
import app.escriba.dictation.DictationUiState
import app.escriba.ime.layout.FieldKind
import app.escriba.ime.layout.KeyAction
import app.escriba.ime.layout.KeySpec
import app.escriba.ime.layout.LayoutMode
import app.escriba.engine.TextPolisher
import app.escriba.ime.suggest.Dictionary
import app.escriba.ui.MainActivity
import app.escriba.ui.PermissionActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.BreakIterator

enum class ShiftState { OFF, ON, LOCKED }

enum class Panel { KEYBOARD, EMOJI, CLIPBOARD, DICTATION }

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
    private val service: EscribaIme,
    private val app: EscribaApp,
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
        if (panel != Panel.DICTATION) panel = Panel.KEYBOARD
        shift = ShiftState.OFF
        lastCorrection = null
        autoSpace = false
        cursorBelief = info.initialSelStart
        if (dictation == null) lastDictation = null
        resyncWord()
        updateAutoCaps()
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
                app.learnedWords.add(original) // the user rejected the correction: remember the word
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
        if (s.verbatim && dictionary?.isWord(s.text) != true) app.learnedWords.add(s.text)
        word.clear()
        autoSpace = true
        lastCorrection = null
        if (shift == ShiftState.ON) shift = ShiftState.OFF
        publishSuggestions()
        updateAutoCaps()
    }

    private fun publishSuggestions() {
        suggestJob?.cancel()
        val typed = word.toString()
        if (typed.isEmpty() || !settings.suggestions || fieldKind == FieldKind.PASSWORD || layoutMode != LayoutMode.LETTERS) {
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
    // AI revision of typed text
    // ---------------------------------------------------------------------------------------

    fun hideKeyboard() {
        service.requestHideSelf(0)
    }

    /** Rewrites the selected text (or the whole field) with the local language model. */
    fun reviseText() {
        val ic = ic ?: return
        if (fieldKind == FieldKind.PASSWORD) return
        if (app.engines.selectedTextModel() == null) {
            showNotice("Baixe um modelo de texto no app Escriba para revisar")
            openApp(MainActivity.ROUTE_MODELS)
            return
        }
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
            showNotice("Escreva ou selecione um texto para revisar")
            return
        }
        if (target.length >= MAX_REVISION_CHARS) {
            showNotice("Texto muito longo: selecione o trecho que deseja revisar")
            return
        }
        val style = settings.polishStyle.takeIf { it != PolishStyle.OFF } ?: PolishStyle.FORMAL
        revising = true
        lastRevision = null
        revisionJob?.cancel()
        revisionJob = scope.launch {
            val result = withContext(Dispatchers.Default) {
                runCatching {
                    app.engines.touch()
                    TextPolisher(app.engines).polish(target, style, settings.vocabulary) { }
                }.getOrNull()
            }
            revising = false
            val revised = result?.takeIf { it.usedModel }?.text
            if (revised.isNullOrBlank()) {
                showNotice("Não foi possível revisar o texto agora")
                return@launch
            }
            val conn = this@KeyboardController.ic ?: return@launch
            conn.beginBatchEdit()
            if (replaceSelection) {
                if (conn.getSelectedText(0)?.toString() != target) {
                    conn.endBatchEdit()
                    showNotice("O texto mudou durante a revisão")
                    return@launch
                }
                conn.commitText(revised, 1)
            } else {
                val nowBefore = conn.getTextBeforeCursor(MAX_REVISION_CHARS, 0)?.toString().orEmpty()
                val nowAfter = conn.getTextAfterCursor(MAX_REVISION_CHARS, 0)?.toString().orEmpty()
                if (nowBefore.length != beforeLen || nowAfter.length != afterLen || nowBefore + nowAfter != target) {
                    conn.endBatchEdit()
                    showNotice("O texto mudou durante a revisão")
                    return@launch
                }
                conn.deleteSurroundingText(beforeLen, afterLen)
                conn.commitText(revised, 1)
            }
            conn.endBatchEdit()
            lastRevision = Revision(target, revised)
            word.clear()
            publishSuggestions()
        }
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
    // Dictation
    // ---------------------------------------------------------------------------------------

    fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(service, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun startDictation() {
        if (dictation != null) return
        if (!hasMicPermission()) {
            service.startActivity(
                Intent(service, PermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return
        }
        if (app.engines.selectedSpeechModel() == null) {
            showNotice("Baixe um modelo de voz no app Escriba para ditar")
            openApp(MainActivity.ROUTE_MODELS)
            return
        }
        val s = settings
        val style = if (fieldKind == FieldKind.TEXT) s.polishStyle else PolishStyle.OFF
        val session = DictationSession(
            app = app,
            scope = app.appScope,
            style = style,
            language = s.language,
            vocabulary = s.vocabulary.toList(),
            onFinished = { state -> scope.launch { onDictationFinished(state) } },
        )
        lastDictation = null
        dictation = session
        panel = Panel.DICTATION
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
        panel = Panel.KEYBOARD
        updateAutoCaps()
    }

    private fun onDictationFinished(state: DictationUiState) {
        if (dictation == null) return
        val text = state.finalText.trim()
        if (text.isEmpty()) {
            dictation = null
            panel = Panel.KEYBOARD
            showNotice("Não ouvi nada. Tente falar mais perto do microfone.")
            return
        }
        if (settings.autoInsert) insertDictation(state)
    }

    /** Inserts the dictated text at the cursor with sensible spacing and capitalisation. */
    fun insertDictation(state: DictationUiState, original: Boolean = false) {
        val ic = ic
        val polished = state.finalText.trim()
        val raw = state.transcript.trim().ifEmpty { polished }
        val text = if (original) raw else polished
        dictation = null
        panel = Panel.KEYBOARD
        if (ic == null || text.isEmpty()) return
        val inserted = adaptToContext(ic, text)
        ic.commitText(inserted, 1)
        cursorBelief += inserted.length
        lastDictation = InsertedDictation(polished, raw, inserted, showingOriginal = original)
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
