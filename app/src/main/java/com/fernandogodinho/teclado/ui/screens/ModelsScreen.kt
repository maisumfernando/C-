package com.fernandogodinho.teclado.ui.screens

import androidx.compose.animation.animateContentSize
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
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fernandogodinho.teclado.core.DeviceProfile
import com.fernandogodinho.teclado.core.OemTips
import com.fernandogodinho.teclado.tecladoApp
import com.fernandogodinho.teclado.models.DownloadState
import com.fernandogodinho.teclado.models.ModelCatalog
import com.fernandogodinho.teclado.models.ModelKind
import com.fernandogodinho.teclado.models.ModelSpec
import com.fernandogodinho.teclado.models.formatBytes
import com.fernandogodinho.teclado.ui.components.AppTopBar
import com.fernandogodinho.teclado.ui.components.Gap
import com.fernandogodinho.teclado.ui.components.MeterBars
import com.fernandogodinho.teclado.ui.components.Pill
import com.fernandogodinho.teclado.ui.components.SectionTitle

@Composable
fun ModelsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.tecladoApp
    val settings by app.settings.settings.collectAsState()
    val installed by app.models.installed.collectAsState()
    val downloads by app.models.downloads.collectAsState(initial = emptyMap())
    val ram = remember { DeviceProfile.totalRamGb(context) }
    val (recSpeech, recText) = remember(ram) { ModelCatalog.recommended(ram) }
    val free = remember(installed, downloads.size) { DeviceProfile.freeStorageBytes(app.models.modelsDir) }
    var confirmDelete by remember { mutableStateOf<ModelSpec?>(null) }
    val catalogRevision by app.models.catalogRevision.collectAsState()
    val updates by app.updates.status.collectAsState()
    LaunchedEffect(Unit) {
        // Opening this screen is a good moment to look for newer models (validated catalog).
        if (settings.checkUpdates) runCatching { app.updates.check() }
    }

    val activeSpeech = app.engines.selectedSpeechModel(settings)?.id
    val activeText = app.engines.selectedTextModel(settings)?.id

    Scaffold(topBar = { AppTopBar("Modelos de IA", onBack) }, containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 18.dp),
        ) {
            if (updates.modelUpdates.isNotEmpty()) {
                UpdatesCard(updates.modelUpdates, downloads) { newer -> app.models.download(newer) }
                Gap(14)
            }
            if (downloads.values.any { it.running } && OemTips.aggressiveBackgroundLimits) {
                OemDownloadTip()
                Gap(14)
            }
            // Re-read the lists whenever the online catalog adds models.
            val knownSpeech = remember(catalogRevision) { ModelCatalog.speechModels }
            val knownText = remember(catalogRevision) { ModelCatalog.textModels }
            val needsPack = installed.none { id -> ModelCatalog.byId(id)?.kind == ModelKind.SPEECH } ||
                installed.none { id -> ModelCatalog.byId(id)?.kind == ModelKind.TEXT }
            if (needsPack) {
                RecommendedPack(recSpeech, recText, ram, installed, downloads) {
                    if (recSpeech.id !in installed) app.models.download(recSpeech)
                    if (recText.id !in installed) app.models.download(recText)
                }
            }

            SectionTitle("Voz → texto (Whisper)")
            Text(
                "Transcreve a sua fala. Modelos maiores erram menos, sobretudo com nomes e termos em inglês.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 6.dp, bottom = 10.dp),
            )
            knownSpeech.forEach { spec ->
                ModelCard(
                    spec = spec,
                    installed = spec.id in installed,
                    active = spec.id == activeSpeech,
                    recommended = spec.id == recSpeech.id,
                    download = downloads[spec.id],
                    tooBig = ram + 0.5 < spec.minRamGb,
                    onSelect = { app.settings.setSpeechModel(spec.id) },
                    onDownload = { app.models.download(spec) },
                    onCancel = { app.models.cancel(spec) },
                    onDelete = { confirmDelete = spec },
                )
                Gap(10)
            }

            SectionTitle("Revisão do texto (LLM)")
            Text(
                "Reescreve a transcrição em português impecável: tira vícios de linguagem, corrige gramática e pontuação e respeita o que você disse.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 6.dp, bottom = 10.dp),
            )
            knownText.forEach { spec ->
                ModelCard(
                    spec = spec,
                    installed = spec.id in installed,
                    active = spec.id == activeText,
                    recommended = spec.id == recText.id,
                    download = downloads[spec.id],
                    tooBig = ram + 0.5 < spec.minRamGb,
                    onSelect = { app.settings.setTextModel(spec.id) },
                    onDownload = { app.models.download(spec) },
                    onCancel = { app.models.cancel(spec) },
                    onDelete = { confirmDelete = spec },
                )
                Gap(10)
            }

            Gap(6)
            Row(Modifier.padding(horizontal = 6.dp), verticalAlignment = Alignment.Top) {
                Icon(Icons.Outlined.VerifiedUser, null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    "Os arquivos vêm do Hugging Face, fixados em uma versão exata e conferidos por SHA-256 antes do uso. " +
                        "Espaço livre: ${formatBytes(free)} · Memória do aparelho: ${"%.0f".format(ram)} GB.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Gap(28)
        }
    }

    confirmDelete?.let { spec ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Remover ${spec.name}?") },
            text = { Text("O arquivo de ${formatBytes(spec.sizeBytes)} será apagado. Você pode baixá-lo de novo quando quiser.") },
            confirmButton = {
                TextButton(onClick = {
                    app.engines.releaseAll()
                    app.models.delete(spec)
                    confirmDelete = null
                }) { Text("Remover") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun RecommendedPack(
    speech: ModelSpec,
    text: ModelSpec,
    ram: Double,
    installed: Set<String>,
    downloads: Map<String, DownloadState>,
    onDownload: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val pending = listOf(speech, text).filter { it.id !in installed }
    val running = pending.any { downloads[it.id]?.running == true }
    val total = pending.sumOf { it.sizeBytes }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(scheme.primaryContainer)
            .padding(18.dp),
    ) {
        Text("Pacote recomendado", style = MaterialTheme.typography.titleMedium, color = scheme.onPrimaryContainer)
        Gap(4)
        Text(
            "Escolhido para o seu aparelho (${"%.0f".format(ram)} GB de RAM): ${speech.name} para ouvir e ${text.name} para revisar.",
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onPrimaryContainer.copy(alpha = 0.85f),
        )
        Gap(14)
        Button(onClick = onDownload, enabled = !running && pending.isNotEmpty(), shape = RoundedCornerShape(14.dp)) {
            Icon(Icons.Outlined.CloudDownload, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (running) "Baixando…" else "Baixar tudo · ${formatBytes(total)}")
        }
        Gap(6)
        Text(
            "Use Wi-Fi: o download continua em segundo plano.",
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onPrimaryContainer.copy(alpha = 0.7f),
        )
    }
}

@Composable
private fun ModelCard(
    spec: ModelSpec,
    installed: Boolean,
    active: Boolean,
    recommended: Boolean,
    download: DownloadState?,
    tooBig: Boolean,
    onSelect: () -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val border = if (active) scheme.primary else scheme.outlineVariant
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(scheme.surfaceContainerLow)
            .border(if (active) 1.5.dp else 1.dp, border, RoundedCornerShape(20.dp))
            .clickable(enabled = installed && !active, onClick = onSelect)
            .padding(16.dp)
            .animateContentSize(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(spec.name, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.width(8.dp))
                    Pill(spec.badge)
                }
                Gap(2)
                Text(
                    buildString {
                        append(formatBytes(spec.sizeBytes))
                        append(" · ").append(spec.license)
                        if (recommended) append(" · recomendado")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            }
            if (active) {
                Icon(Icons.Filled.CheckCircle, "Em uso", tint = scheme.primary, modifier = Modifier.size(24.dp))
            }
        }
        Gap(8)
        Text(spec.description, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
        Gap(10)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            MeterBars("Qualidade", spec.quality)
            MeterBars("Velocidade", spec.speed)
        }
        if (tooBig && !installed) {
            Gap(8)
            Text(
                "Pode ficar lento neste aparelho (recomendado ${spec.minRamGb} GB de RAM).",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.error,
            )
        }
        Gap(12)
        when {
            download != null && download.running -> {
                val label = when (download.phase) {
                    DownloadState.Phase.QUEUED -> "Aguardando conexão…"
                    DownloadState.Phase.VERIFYING -> "Verificando integridade…"
                    else -> "${(download.fraction * 100).toInt()}% · ${formatBytes(download.bytes)} de ${formatBytes(download.total)}"
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        if (download.phase == DownloadState.Phase.VERIFYING || download.phase == DownloadState.Phase.QUEUED) {
                            LinearProgressIndicator(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape))
                        } else {
                            LinearProgressIndicator(
                                progress = { download.fraction },
                                modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                            )
                        }
                        Gap(6)
                        Text(label, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = onCancel) { Text("Cancelar") }
                }
            }
            installed -> Row(verticalAlignment = Alignment.CenterVertically) {
                if (active) {
                    Text("Em uso", style = MaterialTheme.typography.labelLarge, color = scheme.primary, fontWeight = FontWeight.SemiBold)
                } else {
                    FilledTonalButton(onClick = onSelect, shape = RoundedCornerShape(12.dp)) { Text("Usar este") }
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onDelete) { Icon(Icons.Outlined.Delete, "Remover", tint = scheme.onSurfaceVariant) }
            }
            else -> Column {
                if (download?.phase == DownloadState.Phase.FAILED) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.ErrorOutline, null, tint = scheme.error, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(download.error ?: "O download falhou", style = MaterialTheme.typography.bodySmall, color = scheme.error)
                    }
                    Gap(8)
                }
                Box {
                    OutlinedButton(onClick = onDownload, shape = RoundedCornerShape(12.dp)) {
                        Icon(Icons.Outlined.CloudDownload, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if ((download?.bytes ?: 0L) > 0L) "Continuar download" else "Baixar · ${formatBytes(spec.sizeBytes)}",
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun UpdatesCard(
    updates: List<Pair<ModelSpec, ModelSpec>>,
    downloads: Map<String, DownloadState>,
    onUpdate: (ModelSpec) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(scheme.tertiary.copy(alpha = 0.10f))
            .padding(18.dp),
    ) {
        Text("Atualizações de modelos", style = MaterialTheme.typography.titleMedium)
        Gap(4)
        Text(
            "Versões mais novas dos seus modelos estão disponíveis. Elas são conferidas por SHA-256 antes de serem usadas.",
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant,
        )
        updates.forEach { (old, newer) ->
            Gap(12)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(newer.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    Text("substitui ${old.name} · ${formatBytes(newer.sizeBytes)}", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                }
                val running = downloads[newer.id]?.running == true
                FilledTonalButton(onClick = { onUpdate(newer) }, enabled = !running, shape = RoundedCornerShape(12.dp)) {
                    Text(if (running) "Baixando…" else "Atualizar")
                }
            }
        }
    }
}

@Composable
private fun OemDownloadTip() {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(scheme.surfaceContainer)
            .padding(16.dp),
    ) {
        Text("Dica para ${OemTips.brandName}", style = MaterialTheme.typography.titleSmall)
        Gap(4)
        Text(
            "Este celular costuma pausar downloads em segundo plano. Mantenha esta tela aberta até terminar " +
                "ou permita que o app rode em segundo plano nas configurações de bateria.",
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant,
        )
        TextButton(onClick = { OemTips.openBatterySettings(context) }) { Text("Abrir configurações de bateria") }
    }
}
