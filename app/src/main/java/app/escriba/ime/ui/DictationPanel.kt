package app.escriba.ime.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Notes
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.escriba.dictation.DictationPhase
import app.escriba.dictation.DictationSession
import app.escriba.dictation.DictationUiState
import app.escriba.ime.KeyboardController
import kotlinx.coroutines.flow.StateFlow

@Composable
fun DictationPanel(session: DictationSession, controller: KeyboardController, modifier: Modifier = Modifier) {
    val colors = LocalKeyboardColors.current
    val state by session.state.collectAsState()

    Column(modifier.fillMaxSize().padding(top = 2.dp)) {
        PreviewCard(
            state = state,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 10.dp),
        )

        Spacer(Modifier.height(6.dp))
        Waveform(
            levelsFlow = session.levels,
            active = state.phase == DictationPhase.LISTENING,
            speaking = state.speaking,
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp)
                .padding(horizontal = 28.dp),
        )
        Spacer(Modifier.height(4.dp))

        Controls(state, session, controller)
    }
}

/** Status shown in the toolbar while dictating (the mic button stays put for hold-to-talk). */
@Composable
fun DictationToolbarContent(session: DictationSession, onClose: () -> Unit) {
    val colors = LocalKeyboardColors.current
    val state by session.state.collectAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusPill(state)
        Spacer(Modifier.weight(1f))
        if (state.phase == DictationPhase.LISTENING || state.phase == DictationPhase.STARTING) {
            Text(formatElapsed(state.elapsedMs), color = colors.textMuted, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.width(6.dp))
        }
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .clickable(onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Close, "Cancelar ditado", tint = colors.textMuted, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun StatusPill(state: DictationUiState) {
    val colors = LocalKeyboardColors.current
    val (label, dotColor) = when (state.phase) {
        DictationPhase.STARTING -> "Preparando…" to colors.textMuted
        DictationPhase.LISTENING -> (if (state.modelLoading) "Ouvindo · carregando modelo" else "Ouvindo") to colors.recording
        DictationPhase.FINISHING -> "Transcrevendo" to colors.accent
        DictationPhase.POLISHING -> "Revisando com IA" to colors.accent
        DictationPhase.DONE -> "Pronto" to Color(0xFF2EA043)
        DictationPhase.ERROR -> "Algo deu errado" to colors.recording
        DictationPhase.CANCELLED -> "Cancelado" to colors.textMuted
    }
    val pulse = rememberInfiniteTransition(label = "pulse")
    val alpha by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(tween(700, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "dot",
    )
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(colors.chip)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val animated = state.phase == DictationPhase.LISTENING || state.phase == DictationPhase.FINISHING || state.phase == DictationPhase.POLISHING
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(dotColor.copy(alpha = if (animated) alpha else 1f)),
        )
        Spacer(Modifier.width(7.dp))
        AnimatedContent(targetState = label, transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(120)) }, label = "status") { text ->
            Text(text, color = colors.text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun PreviewCard(state: DictationUiState, modifier: Modifier) {
    val colors = LocalKeyboardColors.current
    val scroll = rememberScrollState()
    val showPolished = state.polished.isNotBlank()
    val body = if (showPolished) state.polished else state.transcript
    LaunchedEffect(body, state.transcribing) { scroll.animateScrollTo(scroll.maxValue) }

    val caret = rememberInfiniteTransition(label = "caret")
    val caretAlpha by caret.animateFloat(
        initialValue = 1f, targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(520, easing = LinearEasing), RepeatMode.Reverse), label = "caretAlpha",
    )

    Box(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(colors.surface)
            .border(1.dp, colors.divider, RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        if (state.phase == DictationPhase.ERROR) {
            Text(state.error ?: "Não foi possível concluir o ditado.", color = colors.recording, fontSize = 15.sp, lineHeight = 21.sp)
            return@Box
        }
        if (body.isBlank()) {
            val hint = when {
                state.transcribing || state.phase == DictationPhase.FINISHING -> "Transcrevendo o que você disse…"
                else -> "Fale naturalmente. Pausas, repetições e “né” ficam por minha conta: o texto sai revisado e pronto para enviar."
            }
            Text(hint, color = colors.textMuted, fontSize = 15.sp, lineHeight = 21.sp)
            return@Box
        }
        Column(Modifier.verticalScroll(scroll)) {
            if (showPolished) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
                    Icon(Icons.Outlined.AutoAwesome, null, tint = colors.accent, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(5.dp))
                    Text(
                        if (state.phase == DictationPhase.POLISHING) "Revisando…" else if (state.usedModel) "Revisado por IA no aparelho" else "Texto limpo",
                        color = colors.accent,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
            val text: AnnotatedString = buildAnnotatedString {
                withStyle(SpanStyle(color = if (showPolished) colors.text else colors.text.copy(alpha = 0.78f))) {
                    append(body)
                }
                val writing = state.phase == DictationPhase.POLISHING || state.transcribing
                if (writing) {
                    withStyle(SpanStyle(color = colors.accent.copy(alpha = caretAlpha))) { append(" ▍") }
                }
            }
            Text(text, fontSize = 16.sp, lineHeight = 23.sp)
        }
    }
}

@Composable
private fun Waveform(levelsFlow: StateFlow<FloatArray>, active: Boolean, speaking: Boolean, modifier: Modifier) {
    val colors = LocalKeyboardColors.current
    // Collected here so only the waveform redraws at audio rate, not the whole panel.
    val levels by levelsFlow.collectAsState()
    val barColor = if (active && speaking) colors.accent else colors.textMuted.copy(alpha = 0.45f)
    val idle = rememberInfiniteTransition(label = "idle")
    val phase by idle.animateFloat(
        initialValue = 0f, targetValue = 6.283f,
        animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing)), label = "phase",
    )
    Canvas(modifier) {
        val n = levels.size
        val gap = 3.dp.toPx()
        val barW = ((size.width - gap * (n - 1)) / n).coerceAtLeast(1.5f)
        val mid = size.height / 2
        for (i in 0 until n) {
            val base = if (active) levels[i] else 0f
            val idleWave = if (!active) (0.08f + 0.06f * kotlin.math.sin(phase + i * 0.35f)) else 0.06f
            val v = (base * 0.95f + idleWave).coerceIn(0.06f, 1f)
            val h = v * size.height
            val x = i * (barW + gap)
            drawRoundRect(
                color = barColor.copy(alpha = barColor.alpha * (0.35f + 0.65f * (i + 1f) / n)),
                topLeft = Offset(x, mid - h / 2),
                size = Size(barW, h),
                cornerRadius = CornerRadius(barW / 2),
            )
        }
    }
}

@Composable
private fun Controls(state: DictationUiState, session: DictationSession, controller: KeyboardController) {
    val colors = LocalKeyboardColors.current
    val clipboard = LocalClipboardManager.current
    Row(
        Modifier
            .fillMaxWidth()
            .height(64.dp)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        when (state.phase) {
            DictationPhase.DONE -> {
                RoundAction(Icons.Outlined.Delete, "Descartar") { controller.cancelDictation() }
                RoundAction(Icons.Outlined.ContentCopy, "Copiar") {
                    clipboard.setText(AnnotatedString(state.finalText))
                }
                RoundAction(Icons.Outlined.Notes, "Original") { controller.insertDictation(state, original = true) }
                PrimaryButton("Inserir", Icons.Filled.Check) { controller.insertDictation(state) }
            }
            DictationPhase.ERROR, DictationPhase.CANCELLED -> {
                Spacer(Modifier.width(72.dp))
                PrimaryButton("Fechar", Icons.Filled.Close) { controller.cancelDictation() }
                Spacer(Modifier.width(72.dp))
            }
            else -> {
                RoundAction(Icons.Outlined.Delete, "Cancelar") { controller.cancelDictation() }
                RecordButton(state, onStop = { controller.finishDictation() })
                if (state.phase == DictationPhase.POLISHING) {
                    RoundAction(Icons.Outlined.Notes, "Pular revisão") { session.skipPolish() }
                } else {
                    Spacer(Modifier.width(72.dp))
                }
            }
        }
    }
}

@Composable
private fun RecordButton(state: DictationUiState, onStop: () -> Unit) {
    val colors = LocalKeyboardColors.current
    val listening = state.phase == DictationPhase.LISTENING || state.phase == DictationPhase.STARTING
    val busy = state.phase == DictationPhase.FINISHING || state.phase == DictationPhase.POLISHING
    val ring = rememberInfiniteTransition(label = "ring")
    val ringScale by ring.animateFloat(
        initialValue = 1f, targetValue = 1.18f,
        animationSpec = infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "ringScale",
    )
    val speakingBoost by animateFloatAsState(if (state.speaking) 1.08f else 1f, label = "boost")
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(64.dp)) {
        if (listening) {
            Box(
                Modifier
                    .size(58.dp)
                    .scale(ringScale * speakingBoost)
                    .clip(CircleShape)
                    .background(colors.recording.copy(alpha = 0.16f)),
            )
        }
        Box(
            Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(if (busy) colors.chip else colors.recording)
                .clickable(enabled = listening, onClick = onStop),
            contentAlignment = Alignment.Center,
        ) {
            when {
                busy -> CircularProgressIndicator(color = colors.accent, strokeWidth = 2.5.dp, modifier = Modifier.size(26.dp))
                listening -> Icon(Icons.Filled.Stop, "Concluir", tint = Color.White, modifier = Modifier.size(28.dp))
                else -> Icon(Icons.Filled.Mic, null, tint = Color.White, modifier = Modifier.size(28.dp))
            }
        }
    }
}

@Composable
private fun RoundAction(icon: ImageVector, description: String, onClick: () -> Unit) {
    val colors = LocalKeyboardColors.current
    Column(
        Modifier
            .width(72.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(colors.functionKey),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = colors.text, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.height(3.dp))
        Text(description, color = colors.textMuted, fontSize = 11.sp, maxLines = 1)
    }
}

@Composable
private fun PrimaryButton(label: String, icon: ImageVector, onClick: () -> Unit) {
    val colors = LocalKeyboardColors.current
    Row(
        Modifier
            .height(48.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(colors.accent)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = colors.onAccent, modifier = Modifier.size(19.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, color = colors.onAccent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

private fun formatElapsed(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}
