package app.escriba.ime.ui

import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.KeyboardReturn
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.escriba.core.AppSettings
import app.escriba.ime.EnterKind
import app.escriba.ime.Feedback
import app.escriba.ime.KeyboardController
import app.escriba.ime.ShiftState
import app.escriba.ime.layout.KeyAction
import app.escriba.ime.layout.KeySpec
import app.escriba.ime.layout.KeyStyle
import app.escriba.ime.layout.KeyboardLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** A key with its on-screen rectangle (visual) and its touch rectangle (gapless). */
class KeyGeo(val key: KeySpec, val rect: Rect, val hit: Rect, val row: Int)

private fun computeGeometry(layout: KeyboardLayout, width: Float, rowHeight: Float, gapX: Float, gapY: Float): List<KeyGeo> {
    val unit = width / layout.units
    val out = ArrayList<KeyGeo>()
    layout.rows.forEachIndexed { r, row ->
        val total = row.keys.sumOf { it.width.toDouble() }.toFloat() + row.padding * 2
        var x = (layout.units - total) / 2f * unit + row.padding * unit
        val top = r * rowHeight
        row.keys.forEachIndexed { i, key ->
            val w = key.width * unit
            val hitLeft = if (i == 0) 0f else x
            val hitRight = if (i == row.keys.lastIndex) width else x + w
            out += KeyGeo(
                key = key,
                rect = Rect(x + gapX / 2, top + gapY / 2, x + w - gapX / 2, top + rowHeight - gapY / 2),
                hit = Rect(hitLeft, top, hitRight, top + rowHeight),
                row = r,
            )
            x += w
        }
    }
    return out
}

private fun hitTest(geometry: List<KeyGeo>, rows: Int, rowHeight: Float, pos: Offset): KeyGeo? {
    if (geometry.isEmpty()) return null
    val row = (pos.y / rowHeight).toInt().coerceIn(0, rows - 1)
    val inRow = geometry.filter { it.row == row }
    return inRow.firstOrNull { pos.x >= it.hit.left && pos.x < it.hit.right }
        ?: inRow.minByOrNull { abs(it.hit.center.x - pos.x) }
}

/** Long-press alternatives popup. */
class AlternatesPopup(val anchor: KeyGeo, val options: List<String>, val rect: Rect, val cell: Float) {
    var selected by mutableIntStateOf(0)
    fun indexAt(x: Float): Int = ((x - rect.left) / cell).toInt().coerceIn(0, options.lastIndex)
}

/**
 * Multi-touch key tracking: press feedback, previews, long-press alternatives, backspace repeat,
 * space-bar cursor control and fast-typing rollover.
 */
class KeyTouchTracker(
    private val controller: KeyboardController,
    private val feedback: Feedback,
    private val view: View,
    private val scope: CoroutineScope,
    private val settings: () -> AppSettings,
    private val density: Float,
) {
    var geometry: List<KeyGeo> = emptyList()
    var rows = 1
    var rowHeight = 1f
    var gridWidth = 1f

    val pressed = mutableStateListOf<KeySpec>()
    var preview by mutableStateOf<KeyGeo?>(null)
    var popup by mutableStateOf<AlternatesPopup?>(null)

    private enum class Mode { NORMAL, POPUP, CURSOR }

    private inner class Track(var geo: KeyGeo, val down: Offset) {
        var mode = Mode.NORMAL
        var consumed = false
        var timer: Job? = null
        var cursorX = down.x
        fun cancel() {
            timer?.cancel()
            timer = null
        }
    }

    private val tracks = HashMap<PointerId, Track>()

    suspend fun PointerInputScope.track() {
        try {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    for (change in event.changes) {
                        when {
                            change.pressed && !change.previousPressed -> onDown(change.id, change.position)
                            !change.pressed && change.previousPressed -> onUp(change.id)
                            change.pressed -> onMove(change.id, change.position)
                        }
                        change.consume()
                    }
                }
            }
        } finally {
            reset()
        }
    }

    fun reset() {
        tracks.values.forEach { it.cancel() }
        tracks.clear()
        pressed.clear()
        preview = null
        popup = null
    }

    private fun onDown(id: PointerId, pos: Offset) {
        // Rollover: a new finger commits keys still held by other fingers.
        for (t in tracks.values) {
            if (t.mode == Mode.NORMAL && !t.consumed && t.geo.key.isCharacter) {
                t.cancel()
                t.consumed = true
                controller.onKey(t.geo.key)
                pressed.remove(t.geo.key)
                if (preview === t.geo) preview = null
            }
        }
        val geo = hitTest(geometry, rows, rowHeight, pos) ?: return
        val track = Track(geo, pos)
        tracks[id] = track
        pressed.add(geo.key)
        val s = settings()
        feedback.keyDown(view, geo.key.action, s.haptics, s.sound)
        preview = if (geo.key.isCharacter && s.keyPopup) geo else null

        when (geo.key.action) {
            KeyAction.Backspace -> {
                track.consumed = true
                controller.onBackspace(repeat = false)
                track.timer = scope.launch {
                    delay(380)
                    var interval = 70L
                    while (isActive) {
                        controller.onBackspace(repeat = true)
                        delay(interval)
                        interval = (interval - 4).coerceAtLeast(35)
                    }
                }
            }
            KeyAction.Shift -> {
                track.consumed = true
                controller.onAction(KeyAction.Shift)
            }
            else -> scheduleLongPress(track)
        }
    }

    private fun scheduleLongPress(track: Track) {
        track.cancel()
        val key = track.geo.key
        val hasAlternates = key.alternates.isNotEmpty()
        if (!hasAlternates && key.action != KeyAction.Space) return
        track.timer = scope.launch {
            delay(settings().longPressDelayMs.toLong())
            if (track.mode != Mode.NORMAL || track.consumed) return@launch
            if (key.action == KeyAction.Space) {
                track.consumed = true
                feedback.longPress(view, settings().haptics)
                controller.showInputMethodPicker()
                return@launch
            }
            openPopup(track)
        }
    }

    private fun openPopup(track: Track) {
        val geo = track.geo
        val options = geo.key.alternates
        val cell = minOf(geo.rect.width.coerceAtLeast(36 * density), (gridWidth - 16 * density) / options.size)
        val width = cell * options.size
        val left = (geo.rect.center.x - cell / 2).coerceIn(8 * density, gridWidth - width - 8 * density)
        val height = geo.rect.height * 1.05f
        val rect = Rect(left, geo.rect.top - height - 6 * density, left + width, geo.rect.top - 6 * density)
        val popup = AlternatesPopup(geo, options, rect, cell)
        popup.selected = popup.indexAt(track.down.x).let { if (abs(track.down.x - geo.rect.center.x) < cell / 2) 0 else it }
        this.popup = popup
        preview = null
        track.mode = Mode.POPUP
        feedback.longPress(view, settings().haptics)
    }

    private fun onMove(id: PointerId, pos: Offset) {
        val track = tracks[id] ?: return
        when (track.mode) {
            Mode.POPUP -> popup?.let { p ->
                val i = p.indexAt(pos.x)
                if (i != p.selected) {
                    p.selected = i
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                }
            }
            Mode.CURSOR -> {
                val step = 11 * density
                val steps = ((pos.x - track.cursorX) / step).toInt()
                if (steps != 0) {
                    controller.moveCursor(steps)
                    track.cursorX += steps * step
                    if (settings().haptics) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                }
            }
            Mode.NORMAL -> {
                if (track.consumed) return
                val key = track.geo.key
                if (key.action == KeyAction.Space && abs(pos.x - track.down.x) > 16 * density) {
                    track.cancel()
                    track.mode = Mode.CURSOR
                    track.cursorX = pos.x
                    return
                }
                val geo = hitTest(geometry, rows, rowHeight, pos) ?: return
                if (geo !== track.geo && key.isCharacter && geo.key.isCharacter) {
                    pressed.remove(track.geo.key)
                    track.geo = geo
                    pressed.add(geo.key)
                    if (preview != null) preview = geo
                    scheduleLongPress(track)
                }
            }
        }
    }

    private fun onUp(id: PointerId) {
        val track = tracks.remove(id) ?: return
        track.cancel()
        pressed.remove(track.geo.key)
        if (preview === track.geo) preview = null
        when (track.mode) {
            Mode.POPUP -> {
                popup?.let { p -> controller.commitText(p.options[p.selected], fromPopup = true) }
                popup = null
            }
            Mode.CURSOR -> Unit
            Mode.NORMAL -> if (!track.consumed) controller.onKey(track.geo.key)
        }
    }
}

@Composable
fun KeyGrid(
    layout: KeyboardLayout,
    controller: KeyboardController,
    settings: AppSettings,
    rowHeight: Dp,
    feedback: Feedback,
    modifier: Modifier = Modifier,
) {
    val colors = LocalKeyboardColors.current
    val density = LocalDensity.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val currentSettings by rememberUpdatedState(settings)
    val tracker = remember(controller) {
        KeyTouchTracker(controller, feedback, view, scope, { currentSettings }, density.density)
    }

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(rowHeight * layout.rows.size),
    ) {
        val widthPx = constraints.maxWidth.toFloat()
        val rowPx = with(density) { rowHeight.toPx() }
        val gapX = with(density) { 5.dp.toPx() }
        val gapY = with(density) { 8.dp.toPx() }
        val geometry = remember(layout, widthPx, rowPx) { computeGeometry(layout, widthPx, rowPx, gapX, gapY) }
        tracker.geometry = geometry
        tracker.rows = layout.rows.size
        tracker.rowHeight = rowPx
        tracker.gridWidth = widthPx

        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(geometry) { with(tracker) { track() } },
        ) {
            for (geo in geometry) {
                KeyView(geo, controller, pressed = geo.key in tracker.pressed, colors = colors)
            }
            tracker.preview?.let { PreviewBubble(it, controller, colors) }
            tracker.popup?.let { AlternatesView(it, controller, colors) }
        }
    }
}

@Composable
private fun KeyView(geo: KeyGeo, controller: KeyboardController, pressed: Boolean, colors: KeyboardColors) {
    val density = LocalDensity.current
    val key = geo.key
    val shape = RoundedCornerShape(9.dp)
    val bg = when (key.style) {
        KeyStyle.ENTER -> if (pressed) colors.accentPressed else colors.accent
        KeyStyle.FUNCTION -> if (pressed) colors.functionKeyPressed else colors.functionKey
        else -> if (pressed) colors.keyPressed else colors.key
    }
    val shadowColor = colors.keyShadow
    val w = with(density) { geo.rect.width.toDp() }
    val h = with(density) { geo.rect.height.toDp() }
    Box(
        Modifier
            .offset { IntOffset(geo.rect.left.roundToInt(), geo.rect.top.roundToInt()) }
            .size(w, h)
            .drawBehind {
                val r = 9.dp.toPx()
                drawRoundRect(shadowColor, topLeft = Offset(0f, 1.2.dp.toPx()), size = size, cornerRadius = CornerRadius(r))
                drawRoundRect(bg, size = size, cornerRadius = CornerRadius(r))
            },
        contentAlignment = Alignment.Center,
    ) {
        val content = if (key.style == KeyStyle.ENTER) colors.onAccent else colors.text
        when (key.action) {
            KeyAction.Backspace -> Icon(Icons.AutoMirrored.Outlined.Backspace, "Apagar", tint = content, modifier = Modifier.size(22.dp))
            KeyAction.Shift -> ShiftIcon(controller.shift, content)
            KeyAction.Enter -> Icon(enterIcon(controller.enterKind), "Enter", tint = content, modifier = Modifier.size(22.dp))
            KeyAction.Emoji -> Icon(Icons.Outlined.EmojiEmotions, "Emojis", tint = content, modifier = Modifier.size(22.dp))
            KeyAction.Space -> Text(
                if (key.label == " ") "Português" else key.label,
                color = colors.textMuted,
                fontSize = 13.sp,
            )
            else -> {
                val label = controller.displayLabel(key)
                val isLetter = key.isCharacter && label.length == 1
                Text(
                    label,
                    color = content,
                    fontSize = when {
                        key.style == KeyStyle.FUNCTION && label.length > 1 -> 14.sp
                        label.length > 2 -> 15.sp
                        isLetter -> 23.sp
                        else -> 20.sp
                    },
                    fontWeight = if (key.style == KeyStyle.FUNCTION) FontWeight.Medium else FontWeight.Normal,
                )
                key.hint?.let { hint ->
                    Text(
                        hint,
                        color = colors.textMuted,
                        fontSize = 10.sp,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 2.dp, end = 5.dp),
                    )
                }
            }
        }
    }
}

private fun enterIcon(kind: EnterKind) = when (kind) {
    EnterKind.SEARCH -> Icons.Filled.Search
    EnterKind.SEND -> Icons.AutoMirrored.Filled.Send
    EnterKind.GO, EnterKind.NEXT -> Icons.AutoMirrored.Filled.ArrowForward
    EnterKind.DONE -> Icons.Filled.Check
    else -> Icons.AutoMirrored.Filled.KeyboardReturn
}

@Composable
private fun ShiftIcon(state: ShiftState, color: Color) {
    Box(
        Modifier
            .size(22.dp)
            .drawBehind { drawShiftArrow(state, color) },
    )
}

private fun DrawScope.drawShiftArrow(state: ShiftState, color: Color) {
    val w = size.width
    val h = size.height
    val path = Path().apply {
        moveTo(w * 0.5f, h * 0.08f)
        lineTo(w * 0.92f, h * 0.52f)
        lineTo(w * 0.68f, h * 0.52f)
        lineTo(w * 0.68f, h * 0.80f)
        lineTo(w * 0.32f, h * 0.80f)
        lineTo(w * 0.32f, h * 0.52f)
        lineTo(w * 0.08f, h * 0.52f)
        close()
    }
    if (state == ShiftState.OFF) {
        drawPath(path, color, style = Stroke(width = 1.8.dp.toPx(), join = StrokeJoin.Round))
    } else {
        drawPath(path, color, style = Fill)
        drawPath(path, color, style = Stroke(width = 1.8.dp.toPx(), join = StrokeJoin.Round))
    }
    if (state == ShiftState.LOCKED) {
        drawRoundRect(
            color,
            topLeft = Offset(w * 0.30f, h * 0.88f),
            size = Size(w * 0.40f, 2.dp.toPx()),
            cornerRadius = CornerRadius(1.dp.toPx()),
        )
    }
}

@Composable
private fun PreviewBubble(geo: KeyGeo, controller: KeyboardController, colors: KeyboardColors) {
    val density = LocalDensity.current
    val extra = with(density) { 8.dp.toPx() }
    val width = geo.rect.width + extra * 2
    val height = geo.rect.height + extra * 1.5f
    val left = geo.rect.left - extra
    val top = geo.rect.top - height - with(density) { 4.dp.toPx() }
    Box(
        Modifier
            .offset { IntOffset(left.roundToInt(), top.roundToInt()) }
            .size(with(density) { width.toDp() }, with(density) { height.toDp() })
            .shadow(6.dp, RoundedCornerShape(12.dp))
            .background(colors.popup, RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(controller.displayLabel(geo.key), color = colors.text, fontSize = 30.sp)
    }
}

@Composable
private fun AlternatesView(popup: AlternatesPopup, controller: KeyboardController, colors: KeyboardColors) {
    val density = LocalDensity.current
    val rect = popup.rect
    Row(
        Modifier
            .offset { IntOffset(rect.left.roundToInt(), rect.top.roundToInt()) }
            .size(with(density) { rect.width.toDp() }, with(density) { rect.height.toDp() })
            .shadow(8.dp, RoundedCornerShape(12.dp))
            .background(colors.popup, RoundedCornerShape(12.dp))
            .padding(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        popup.options.forEachIndexed { i, option ->
            val selected = i == popup.selected
            Box(
                Modifier
                    .size(with(density) { (popup.cell - 6.dp.toPx() / popup.options.size).toDp() }, with(density) { (rect.height - 6.dp.toPx()).toDp() })
                    .background(if (selected) colors.popupSelected else Color.Transparent, RoundedCornerShape(9.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    controller.displayAlternate(option),
                    color = if (selected) colors.onAccent else colors.text,
                    fontSize = if (option.length > 2) 15.sp else 22.sp,
                )
            }
        }
    }
}
