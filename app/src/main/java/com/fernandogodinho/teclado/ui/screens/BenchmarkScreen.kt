package com.fernandogodinho.teclado.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fernandogodinho.teclado.core.DeviceProfile
import com.fernandogodinho.teclado.core.PolishStyle
import com.fernandogodinho.teclado.engine.BenchmarkMath
import com.fernandogodinho.teclado.engine.BenchmarkReport
import com.fernandogodinho.teclado.engine.BenchmarkSamples
import com.fernandogodinho.teclado.engine.BenchmarkState
import com.fernandogodinho.teclado.engine.ComboEstimate
import com.fernandogodinho.teclado.engine.SpeechBench
import com.fernandogodinho.teclado.engine.TextBench
import com.fernandogodinho.teclado.models.ModelCatalog
import com.fernandogodinho.teclado.models.ModelSpec
import com.fernandogodinho.teclado.tecladoApp
import com.fernandogodinho.teclado.ui.components.AppTopBar
import com.fernandogodinho.teclado.ui.components.Gap
import com.fernandogodinho.teclado.ui.components.MeterBars
import com.fernandogodinho.teclado.ui.components.Pill
import com.fernandogodinho.teclado.ui.components.SectionTitle
import com.fernandogodinho.teclado.ui.components.SettingsGroup
import com.fernandogodinho.teclado.ui.components.SwitchRow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** Speed test: measures the installed models on this phone and compares every combination. */
@Composable
fun BenchmarkScreen(onBack: () -> Unit, onModels: () -> Unit) {
    val context = LocalContext.current
    val app = context.tecladoApp
    val runner = app.benchmark
    val state by runner.state.collectAsState()
    val settings by app.settings.settings.collectAsState()
    val installed by app.models.installed.collectAsState()
    val ram = remember { DeviceProfile.totalRamGb(context) }
    var showAll by rememberSaveable { mutableStateOf(false) }

    Scaffold(topBar = { AppTopBar("Teste de velocidade", onBack) }, containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 18.dp),
        ) {
            // After a test, show the phone the numbers were measured on.
            val measured = state.report
            IntroCard(
                device = measured?.device ?: deviceLabel(),
                ram = measured?.ramGb ?: ram,
                threads = measured?.threads ?: app.engines.threads,
            )
            Gap(14)

            val plan = remember(installed, state.running) { if (state.running) state.plan else runner.installedPlan() }
            when {
                state.running -> RunningCard(state, onCancel = { runner.cancel() })
                plan.isEmpty() -> {
                    Text(
                        "Nenhum modelo baixado ainda. Baixe pelo menos um modelo de voz para medir o seu celular.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Gap(10)
                    Button(onClick = onModels, shape = RoundedCornerShape(14.dp)) { Text("Escolher modelos") }
                }
                else -> {
                    Button(
                        onClick = { runner.start() },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Icon(Icons.Outlined.Speed, null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (state.report == null) "Iniciar teste" else "Refazer teste")
                    }
                    Gap(6)
                    Text(
                        "Serão testados ${plan.size} ${if (plan.size == 1) "modelo" else "modelos"}: ${plan.joinToString { it.name }}. " +
                            "Leva de 1 a 4 minutos; deixe o app aberto.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 6.dp),
                    )
                }
            }
            state.error?.let {
                Gap(10)
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }

            val report = state.report
            if (report != null && !state.running) {
                Results(
                    report = report,
                    showAll = showAll,
                    onShowAll = { showAll = it },
                    activeSpeech = app.engines.selectedSpeechModel(settings)?.id,
                    activeText = app.engines.selectedTextModel(settings)?.id?.takeIf { settings.polishStyle != PolishStyle.OFF },
                    installed = installed,
                    transcribeDuringPauses = settings.pauseToTranscribe,
                    onUse = { combo ->
                        app.settings.setSpeechModel(combo.speech.id)
                        combo.text?.let { app.settings.setTextModel(it.id) }
                    },
                    onModels = onModels,
                )
            }
            Gap(28)
        }
    }
}

@Composable
private fun IntroCard(device: String, ram: Double, threads: Int) {
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(Brush.linearGradient(listOf(scheme.primaryContainer, scheme.surfaceContainerLow)))
            .padding(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(scheme.primary),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.Speed, null, tint = scheme.onPrimary, modifier = Modifier.size(22.dp)) }
            Spacer(Modifier.width(12.dp))
            Text("Qual combinação é a melhor para você?", style = MaterialTheme.typography.titleMedium, color = scheme.onPrimaryContainer)
        }
        Gap(10)
        Text(
            "O teste roda cada modelo instalado neste celular com o mesmo áudio (15 s de fala) e o mesmo ditado, " +
                "e calcula quanto tempo você esperaria pelo texto pronto em cada combinação de voz + revisão.",
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onPrimaryContainer.copy(alpha = 0.85f),
        )
        Gap(10)
        Text(
            "$device · ${ram.roundToInt()} GB de memória · $threads núcleos rápidos",
            style = MaterialTheme.typography.labelMedium,
            color = scheme.primary,
        )
    }
}

private fun deviceLabel(): String {
    val maker = android.os.Build.MANUFACTURER.orEmpty().replaceFirstChar { it.uppercase() }
    val model = android.os.Build.MODEL.orEmpty()
    return if (model.startsWith(maker, ignoreCase = true)) model else "$maker $model".trim()
}

@Composable
private fun RunningCard(state: BenchmarkState, onCancel: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    SettingsGroup {
        Column(Modifier.padding(16.dp)) {
            val total = state.plan.size.coerceAtLeast(1)
            val progress = ((state.current.coerceAtLeast(0) + 0.5f) / total).coerceIn(0f, 1f)
            Text("Testando ${state.current + 1} de $total", style = MaterialTheme.typography.titleSmall)
            Gap(4)
            Text(state.step, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
            Gap(12)
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape))
            Gap(12)
            state.plan.forEachIndexed { i, spec ->
                Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    when {
                        i < state.current -> Icon(Icons.Filled.CheckCircle, null, tint = scheme.tertiary, modifier = Modifier.size(18.dp))
                        i == state.current -> CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                        else -> Icon(Icons.Outlined.HourglassEmpty, null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(spec.name, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Gap(8)
            OutlinedButton(onClick = onCancel, shape = RoundedCornerShape(12.dp)) { Text("Cancelar") }
        }
    }
}

@Composable
private fun Results(
    report: BenchmarkReport,
    showAll: Boolean,
    onShowAll: (Boolean) -> Unit,
    activeSpeech: String?,
    activeText: String?,
    installed: Set<String>,
    transcribeDuringPauses: Boolean,
    onUse: (ComboEstimate) -> Unit,
    onModels: () -> Unit,
) {
    val speechModels = ModelCatalog.speechModels.filter { showAll || it.id in installed || report.speechFor(it.id) != null }
    val textModels = ModelCatalog.textModels.filter { showAll || it.id in installed || report.textFor(it.id) != null }
    val combos = remember(report, showAll, installed, transcribeDuringPauses) {
        BenchmarkMath.combinations(report, speechModels, textModels, transcribeDuringPauses, includeWithoutRevision = false)
    }
    val badges = remember(combos) { BenchmarkMath.badges(combos) }
    val slowest = combos.maxOfOrNull { it.waitMs[1] }?.coerceAtLeast(1.0) ?: 1.0

    SectionTitle("Tempo de espera por combinação")
    Text(
        "Quanto tempo leva entre você parar de falar e o texto revisado aparecer. Parte da transcrição já acontece enquanto você fala.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 6.dp, end = 6.dp, bottom = 10.dp),
    )
    SettingsGroup {
        SwitchRow(
            "Incluir modelos não baixados",
            "Estimativa calculada a partir dos modelos medidos neste celular",
            showAll,
        ) { onShowAll(it) }
    }
    Gap(10)
    if (combos.isEmpty()) {
        Text(
            if (report.text.isEmpty()) "Baixe também um modelo de revisão (LLM) e refaça o teste para comparar combinações."
            else "Nenhuma combinação disponível.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 6.dp),
        )
    }
    combos.forEach { combo ->
        val usable = combo.speech.id in installed && (combo.text == null || combo.text.id in installed)
        ComboCard(
            combo = combo,
            badge = badges[combo],
            active = combo.speech.id == activeSpeech && combo.text?.id == activeText,
            usable = usable,
            fraction = (combo.waitMs[1] / slowest).toFloat(),
            onUse = { onUse(combo) },
            onDownload = onModels,
        )
        Gap(10)
    }

    if (report.speech.isNotEmpty()) {
        SectionTitle("Modelos de voz (Whisper)")
        report.speech.forEach { b ->
            ModelCatalog.byId(b.modelId)?.let { SpeechResult(it, b) }
            Gap(10)
        }
    }
    if (report.text.isNotEmpty()) {
        SectionTitle("Modelos de revisão (LLM)")
        report.text.forEach { b ->
            ModelCatalog.byId(b.modelId)?.let { TextResult(it, b) }
            Gap(10)
        }
    }
    if (report.failed.isNotEmpty()) {
        Text(
            "Não foi possível testar: ${report.failed.mapNotNull { ModelCatalog.byId(it)?.name }.joinToString()}. " +
                "Pode faltar memória para esse modelo neste celular.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 6.dp),
        )
        Gap(8)
    }
    val date = remember(report.createdAt) { SimpleDateFormat("dd/MM/yyyy 'às' HH:mm", Locale("pt", "BR")).format(Date(report.createdAt)) }
    Text(
        "Medido em $date no ${report.device}, versão ${report.appVersion}. Os tempos variam com a temperatura, a bateria e " +
            "outros apps abertos. Áudio de teste: ${BenchmarkSamples.AUDIO_CREDIT}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp),
    )
}

@Composable
private fun ComboCard(
    combo: ComboEstimate,
    badge: BenchmarkMath.Badge?,
    active: Boolean,
    usable: Boolean,
    fraction: Float,
    onUse: () -> Unit,
    onDownload: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(scheme.surfaceContainerLow)
            .border(if (active) 2.dp else 1.dp, if (active) scheme.primary else scheme.outlineVariant, RoundedCornerShape(20.dp))
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(combo.speech.name, style = MaterialTheme.typography.titleSmall)
                Text("+ ${combo.text?.name ?: "sem revisão por IA"}", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
            }
            Column(horizontalAlignment = Alignment.End) {
                if (active) Pill("Em uso")
                badge?.let {
                    if (active) Gap(4)
                    Pill(it.label, container = scheme.tertiary.copy(alpha = 0.16f), content = scheme.tertiary)
                }
            }
        }
        Gap(12)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            BenchmarkSamples.LENGTHS.forEachIndexed { i, words ->
                Column(horizontalAlignment = if (i == 0) Alignment.Start else if (i == 2) Alignment.End else Alignment.CenterHorizontally) {
                    Text(
                        BenchmarkMath.formatSeconds(combo.waitMs[i]),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = if (i == 1) scheme.primary else scheme.onSurface,
                    )
                    Text(
                        when (words) {
                            15 -> "mensagem (15 palavras)"
                            50 -> "parágrafo (50)"
                            else -> "texto longo (150)"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }
        }
        Gap(10)
        Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(scheme.outlineVariant)) {
            Box(
                Modifier
                    .fillMaxWidth(fraction.coerceIn(0.04f, 1f))
                    .height(6.dp)
                    .clip(CircleShape)
                    .background(Brush.horizontalGradient(listOf(Color(0xFF5CC98B), scheme.primary))),
            )
        }
        Gap(12)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val accuracy = combo.speechAccuracy
            if (accuracy != null) {
                Text(
                    "Voz: ${(accuracy * 100).roundToInt()}% de acerto",
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurfaceVariant,
                )
            } else {
                MeterBars("Voz", combo.speech.quality)
            }
            MeterBars("Revisão", combo.text?.quality ?: 1)
            if (!combo.measured) Pill("estimativa", container = scheme.secondaryContainer, content = scheme.onSecondaryContainer)
        }
        if (!active) {
            Gap(4)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (usable) {
                    TextButton(onClick = onUse) { Text("Usar esta combinação") }
                } else {
                    TextButton(onClick = onDownload) { Text("Baixar modelos") }
                }
            }
        }
    }
}

@Composable
private fun SpeechResult(spec: ModelSpec, bench: SpeechBench) {
    var open by rememberSaveable(spec.id) { mutableStateOf(false) }
    ResultCard(
        title = spec.name,
        lines = listOf(
            "1 minuto de fala é processado em ${BenchmarkMath.formatSeconds(bench.rtf * 60_000)}",
            "Acerto no áudio de teste: ${(bench.accuracy * 100).roundToInt()}%",
            "Carregamento do modelo: ${BenchmarkMath.formatSeconds(bench.loadMs.toDouble())}",
        ),
        detailTitle = "O que o modelo ouviu",
        detail = bench.transcript,
        reference = BenchmarkSamples.AUDIO_REFERENCE,
        open = open,
        onToggle = { open = !open },
    )
}

@Composable
private fun TextResult(spec: ModelSpec, bench: TextBench) {
    var open by rememberSaveable(spec.id) { mutableStateOf(false) }
    val wordsPerSecond = bench.generationRate / (bench.outputTokens.toDouble() / BenchmarkMath.words(bench.output).size.coerceAtLeast(1))
    ResultCard(
        title = spec.name,
        lines = listOf(
            "Revisa 50 palavras em ${BenchmarkMath.formatSeconds(BenchmarkMath.polishMs(50, bench))}",
            "Escreve cerca de ${wordsPerSecond.roundToInt().coerceAtLeast(1)} palavras por segundo",
            "Carregamento: ${BenchmarkMath.formatSeconds(bench.loadMs.toDouble())} · leitura das instruções: " +
                "${BenchmarkMath.formatSeconds(bench.warmupMs.toDouble())} (feita enquanto você fala)",
        ),
        detailTitle = "Como revisou o ditado de teste",
        detail = bench.output,
        reference = BenchmarkSamples.DICTATION,
        open = open,
        onToggle = { open = !open },
    )
}

@Composable
private fun ResultCard(
    title: String,
    lines: List<String>,
    detailTitle: String,
    detail: String,
    reference: String,
    open: Boolean,
    onToggle: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(scheme.surfaceContainerLow)
            .border(1.dp, scheme.outlineVariant, RoundedCornerShape(20.dp))
            .clickable(onClick = onToggle)
            .animateContentSize()
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Icon(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, tint = scheme.onSurfaceVariant)
        }
        Gap(6)
        lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant) }
        AnimatedVisibility(open) {
            Column {
                Gap(12)
                Text("Entrada", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                Text("“$reference”", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                Gap(10)
                Text(detailTitle, style = MaterialTheme.typography.labelMedium, color = scheme.primary)
                Text(detail, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}
