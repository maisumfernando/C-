package app.escriba.ime.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.KeyboardHide
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Undo
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.escriba.core.ThemeMode
import app.escriba.dictation.DictationPhase
import app.escriba.escriba
import app.escriba.ime.EscribaIme
import app.escriba.ime.Feedback
import app.escriba.ime.KeyboardController
import app.escriba.ime.Panel
import app.escriba.ime.Suggestion
import app.escriba.ime.layout.LayoutMode
import app.escriba.ime.layout.Layouts
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val PopupSpace = 40.dp
private val ToolbarHeight = 46.dp

@Composable
fun KeyboardRoot(service: EscribaIme, controller: KeyboardController) {
    val app = service.escriba
    val settings by app.settings.settings.collectAsState()
    val dark = when (settings.theme) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val colors = if (dark) KeyboardPalette.dark else KeyboardPalette.light
    val feedback = remember { Feedback(service) }

    val layout = remember(controller.layoutMode, controller.fieldKind, settings.numberRow) {
        when (controller.layoutMode) {
            LayoutMode.LETTERS -> Layouts.letters(settings.numberRow, controller.fieldKind)
            LayoutMode.SYMBOLS -> Layouts.symbols(controller.fieldKind)
            LayoutMode.MORE_SYMBOLS -> Layouts.moreSymbols(controller.fieldKind)
            LayoutMode.NUMBERS -> Layouts.numbers()
            LayoutMode.PHONE -> Layouts.phone()
        }
    }
    val baseRows = if (settings.numberRow) 5 else 4
    val rowHeight: Dp = (if (settings.numberRow) 50.dp else 55.dp) * settings.keyboardHeight
    val keysArea = rowHeight * baseRows
    val gridRowHeight = keysArea / layout.rows.size

    CompositionLocalProvider(LocalKeyboardColors provides colors) {
        Column(Modifier.fillMaxWidth()) {
            Spacer(Modifier.height(PopupSpace))
            Column(
                Modifier
                    .fillMaxWidth()
                    .onSizeChanged { service.keyboardHeightPx = it.height }
                    .background(colors.background)
                    .navigationBarsPadding()
                    .padding(bottom = 4.dp),
            ) {
                Toolbar(controller, settings.haptics)
                Box(Modifier.fillMaxWidth().height(keysArea)) {
                    val session = controller.dictation
                    if (controller.panel == Panel.DICTATION && session != null) {
                        DictationPanel(session = session, controller = controller)
                    } else {
                        when (controller.panel) {
                            Panel.EMOJI -> EmojiPanel(controller)
                            Panel.CLIPBOARD -> ClipboardPanel(controller)
                            else -> KeyGrid(
                                layout = layout,
                                controller = controller,
                                settings = settings,
                                rowHeight = gridRowHeight,
                                feedback = feedback,
                                modifier = Modifier.padding(horizontal = 3.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Toolbar(controller: KeyboardController, haptics: Boolean) {
    val colors = LocalKeyboardColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .height(ToolbarHeight)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
            val session = controller.dictation
            val mode = when {
                session != null && controller.panel == Panel.DICTATION -> 6
                controller.revising -> 0
                controller.notice != null -> 1
                controller.suggestions.isNotEmpty() -> 2
                controller.lastDictation != null -> 3
                controller.lastRevision != null -> 4
                else -> 5
            }
            AnimatedContent(
                targetState = mode,
                transitionSpec = { fadeIn(tween(140)) togetherWith fadeOut(tween(90)) },
                label = "toolbar",
            ) { m ->
                when (m) {
                    0 -> RevisingIndicator(onCancel = { controller.cancelRevision() })
                    1 -> NoticeRow(controller.notice.orEmpty())
                    2 -> SuggestionStrip(controller.suggestions, onPick = controller::pickSuggestion)
                    3 -> DictationChip(controller)
                    4 -> RevisionChip(controller)
                    6 -> controller.dictation?.let { DictationToolbarContent(it, onClose = { controller.cancelDictation() }) }
                    else -> ToolbarActions(controller)
                }
            }
        }
        MicButton(controller, haptics)
    }
}

@Composable
private fun ToolbarActions(controller: KeyboardController) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        ToolbarIcon(Icons.Outlined.Settings, "Configurações") { controller.openApp() }
        ToolbarIcon(Icons.Outlined.ContentPaste, "Área de transferência") { controller.showPanel(Panel.CLIPBOARD) }
        ToolbarIcon(Icons.Outlined.AutoAwesome, "Revisar texto com IA") { controller.reviseText() }
        ToolbarIcon(Icons.Outlined.KeyboardHide, "Fechar teclado") { controller.hideKeyboard() }
    }
}

@Composable
private fun ToolbarIcon(icon: ImageVector, description: String, onClick: () -> Unit) {
    val colors = LocalKeyboardColors.current
    Box(
        Modifier
            .size(42.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = colors.textMuted, modifier = Modifier.size(21.dp))
    }
}

@Composable
private fun SuggestionStrip(items: List<Suggestion>, onPick: (Suggestion) -> Unit) {
    val colors = LocalKeyboardColors.current
    // Best guess in the middle, like the keyboards people are used to.
    val ordered = when (items.size) {
        3 -> listOf(items[0], items[1], items[2])
        else -> items
    }
    Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        ordered.forEachIndexed { i, s ->
            if (i > 0) {
                Box(Modifier.width(1.dp).height(18.dp).background(colors.divider))
            }
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onPick(s) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (s.verbatim && items.size > 1) "“${s.text}”" else s.text,
                    color = if (s.autoCorrect) colors.accent else colors.text,
                    fontWeight = if (s.autoCorrect) FontWeight.SemiBold else FontWeight.Normal,
                    fontSize = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun NoticeRow(text: String) {
    val colors = LocalKeyboardColors.current
    Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Info, null, tint = colors.accent, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, color = colors.text, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun DictationChip(controller: KeyboardController) {
    val colors = LocalKeyboardColors.current
    val last = controller.lastDictation ?: return
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp)) {
        Row(
            Modifier
                .clip(RoundedCornerShape(50))
                .background(colors.chip)
                .clickable { controller.toggleDictationVersion() }
                .padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (last.showingOriginal) Icons.Outlined.SwapHoriz else Icons.Outlined.AutoAwesome,
                null,
                tint = colors.accent,
                modifier = Modifier.size(15.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                if (last.showingOriginal) "Original · usar revisado" else "Revisado · ver original",
                color = colors.text,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )
        }
        ToolbarIcon(Icons.Outlined.Close, "Dispensar") { controller.dismissDictationChip() }
    }
}

@Composable
private fun RevisionChip(controller: KeyboardController) {
    val colors = LocalKeyboardColors.current
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp)) {
        Row(
            Modifier
                .clip(RoundedCornerShape(50))
                .background(colors.chip)
                .clickable { controller.undoRevision() }
                .padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Undo, null, tint = colors.accent, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(6.dp))
            Text("Texto revisado · desfazer", color = colors.text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
        ToolbarIcon(Icons.Outlined.Close, "Dispensar") { controller.dismissRevision() }
    }
}

@Composable
private fun RevisingIndicator(onCancel: () -> Unit) {
    val colors = LocalKeyboardColors.current
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 10.dp)) {
        CircularProgressIndicator(color = colors.accent, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(10.dp))
        Text("Revisando o texto…", color = colors.text, fontSize = 13.sp, modifier = Modifier.weight(1f, fill = false))
        ToolbarIcon(Icons.Outlined.Close, "Cancelar") { onCancel() }
    }
}

/** Tap to start dictating; press and hold to talk, release to finish. */
@Composable
private fun MicButton(controller: KeyboardController, haptics: Boolean) {
    val colors = LocalKeyboardColors.current
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val session = controller.dictation
    val phase = session?.state?.collectAsState()?.value?.phase
    val listening = phase == DictationPhase.LISTENING || phase == DictationPhase.STARTING
    val busy = phase == DictationPhase.FINISHING || phase == DictationPhase.POLISHING
    Box(
        Modifier
            .padding(start = 4.dp)
            .size(38.dp)
            .clip(CircleShape)
            .background(if (listening) colors.recording.copy(alpha = 0.14f) else if (busy) colors.chip else colors.accent)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        if (controller.dictation != null) {
                            // Active dictation: a tap on the (now red) button finishes it.
                            if (tryAwaitRelease()) controller.finishDictation()
                            return@detectTapGestures
                        }
                        var holding = false
                        val hold: Job = scope.launch {
                            delay(400)
                            holding = true
                            if (haptics) view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                            controller.startDictation()
                        }
                        tryAwaitRelease()
                        if (holding) {
                            controller.finishDictation()
                        } else {
                            hold.cancel()
                            controller.startDictation()
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        when {
            listening -> Icon(Icons.Filled.Stop, "Concluir ditado", tint = colors.recording, modifier = Modifier.size(20.dp))
            busy -> CircularProgressIndicator(color = colors.accent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
            else -> Icon(Icons.Filled.Mic, "Ditar", tint = colors.onAccent, modifier = Modifier.size(21.dp))
        }
    }
}
