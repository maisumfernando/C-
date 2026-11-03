package app.escriba.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Height
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Spellcheck
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.escriba.BuildConfig
import app.escriba.core.DictationLanguage
import app.escriba.core.PolishStyle
import app.escriba.core.ThemeMode
import app.escriba.engine.NativeBridge
import app.escriba.escriba
import app.escriba.ui.components.EscribaTopBar
import app.escriba.ui.components.Gap
import app.escriba.ui.components.GroupDivider
import app.escriba.ui.components.IconBadge
import app.escriba.ui.components.RadioRow
import app.escriba.ui.components.SectionTitle
import app.escriba.ui.components.SettingsGroup
import app.escriba.ui.components.SwitchRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
private fun SettingsPage(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Scaffold(topBar = { EscribaTopBar(title, onBack) }, containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 18.dp),
        ) {
            content()
            Gap(32)
        }
    }
}

@Composable
fun DictationSettingsScreen(onBack: () -> Unit) {
    val app = LocalContext.current.escriba
    val s by app.settings.settings.collectAsState()
    val repo = app.settings
    SettingsPage("Ditado", onBack) {
        SectionTitle("Estilo da revisão")
        SettingsGroup {
            PolishStyle.entries.forEach { style ->
                RadioRow(style.label, style.description, selected = s.polishStyle == style) { repo.setPolishStyle(style) }
            }
        }
        StyleExample(s.polishStyle)

        SectionTitle("Idioma")
        SettingsGroup {
            DictationLanguage.entries.forEach { lang ->
                RadioRow(
                    lang.label,
                    if (lang == DictationLanguage.PT) "Melhor precisão para português; termos em inglês continuam reconhecidos" else null,
                    selected = s.language == lang,
                ) { repo.setLanguage(lang) }
            }
        }

        SectionTitle("Comportamento")
        SettingsGroup {
            SwitchRow(
                "Inserir automaticamente",
                "Ao concluir, o texto revisado entra direto no campo. Desligue para revisar antes de inserir.",
                s.autoInsert,
            ) { repo.setAutoInsert(it) }
            GroupDivider()
            SwitchRow(
                "Transcrever durante as pausas",
                "Adianta a transcrição enquanto você fala, para o texto ficar pronto quase na hora.",
                s.pauseToTranscribe,
            ) { repo.setPauseToTranscribe(it) }
            GroupDivider()
            SwitchRow(
                "Transcrição acelerada",
                "Processa só o áudio necessário em vez de janelas fixas de 30 s. Mais rápido; se algo parecer errado, o Escriba refaz automaticamente.",
                s.fastTranscription,
            ) { repo.setFastTranscription(it) }
        }

        SectionTitle("Memória")
        SettingsGroup {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(Icons.Outlined.Timer)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Manter modelos carregados", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Por ${s.keepModelsLoadedMinutes} min após o último uso. Mais tempo = respostas instantâneas; menos tempo = mais memória livre.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                var v by remember(s.keepModelsLoadedMinutes) { mutableFloatStateOf(s.keepModelsLoadedMinutes.toFloat()) }
                Slider(
                    value = v,
                    onValueChange = { v = it },
                    onValueChangeFinished = { repo.setKeepLoadedMinutes(v.toInt()) },
                    valueRange = 1f..30f,
                    steps = 28,
                )
            }
        }
    }
}

@Composable
private fun StyleExample(style: PolishStyle) {
    val scheme = MaterialTheme.colorScheme
    val input = "é então tipo amanhã eu vou chegar mais tarde né porque tenho médico às dez não pera às onze"
    val output = when (style) {
        PolishStyle.FORMAL -> "Amanhã chegarei mais tarde, pois tenho uma consulta médica às onze horas."
        PolishStyle.NATURAL -> "Amanhã eu vou chegar mais tarde, porque tenho médico às onze."
        PolishStyle.MESSAGE -> "Amanhã vou chegar mais tarde, porque tenho médico às 11h."
        PolishStyle.OFF -> "É então tipo amanhã eu vou chegar mais tarde porque tenho médico às dez não pera às onze."
    }
    Column(
        Modifier
            .padding(top = 10.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(scheme.surfaceContainer)
            .padding(14.dp),
    ) {
        Text("Você fala", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
        Text("“$input”", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
        Gap(10)
        Text("O Escriba escreve", style = MaterialTheme.typography.labelMedium, color = scheme.primary)
        Text(output, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
fun KeyboardSettingsScreen(onBack: () -> Unit) {
    val app = LocalContext.current.escriba
    val s by app.settings.settings.collectAsState()
    val repo = app.settings
    SettingsPage("Teclado", onBack) {
        SectionTitle("Aparência")
        SettingsGroup {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(Icons.Outlined.Palette)
                    Spacer(Modifier.width(14.dp))
                    Text("Tema", style = MaterialTheme.typography.bodyLarge)
                }
                Gap(12)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    ThemeMode.entries.forEachIndexed { i, mode ->
                        SegmentedButton(
                            selected = s.theme == mode,
                            onClick = { repo.setTheme(mode) },
                            shape = SegmentedButtonDefaults.itemShape(i, ThemeMode.entries.size),
                        ) { Text(mode.label) }
                    }
                }
            }
            GroupDivider()
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(Icons.Outlined.Height)
                    Spacer(Modifier.width(14.dp))
                    Text("Altura do teclado · ${(s.keyboardHeight * 100).toInt()}%", style = MaterialTheme.typography.bodyLarge)
                }
                var h by remember(s.keyboardHeight) { mutableFloatStateOf(s.keyboardHeight) }
                Slider(value = h, onValueChange = { h = it }, onValueChangeFinished = { repo.setKeyboardHeight(h) }, valueRange = 0.8f..1.3f)
            }
            GroupDivider()
            SwitchRow("Fileira de números", "Mostra os números acima das letras", s.numberRow) { repo.setNumberRow(it) }
            GroupDivider()
            SwitchRow("Prévia ao tocar", "Mostra a letra ampliada acima do dedo", s.keyPopup) { repo.setKeyPopup(it) }
        }

        SectionTitle("Sensação")
        SettingsGroup {
            SwitchRow("Vibração ao tocar", null, s.haptics) { repo.setHaptics(it) }
            GroupDivider()
            SwitchRow("Som ao tocar", null, s.sound) { repo.setSound(it) }
            GroupDivider()
            Column(Modifier.padding(16.dp)) {
                Text("Tempo do toque longo · ${s.longPressDelayMs} ms", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Segure uma tecla para ver acentos e símbolos (ex.: segure “a” para á, ã, â).",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                var d by remember(s.longPressDelayMs) { mutableFloatStateOf(s.longPressDelayMs.toFloat()) }
                Slider(value = d, onValueChange = { d = it }, onValueChangeFinished = { repo.setLongPressDelay(d.toInt()) }, valueRange = 150f..700f)
            }
        }

        SectionTitle("Correção e sugestões")
        SettingsGroup {
            SwitchRow("Sugestões de palavras", "Completa e sugere palavras enquanto você digita", s.suggestions, Icons.Outlined.Spellcheck) { repo.setSuggestions(it) }
            GroupDivider()
            SwitchRow("Correção automática", "Corrige erros de digitação e acentos ao tocar em espaço (“nao” → “não”). Toque em apagar para desfazer.", s.autoCorrect) { repo.setAutoCorrect(it) }
            GroupDivider()
            SwitchRow("Maiúsculas automáticas", "No início das frases", s.autoCapitalize) { repo.setAutoCapitalize(it) }
            GroupDivider()
            SwitchRow("Ponto com espaço duplo", "Dois toques no espaço inserem “. ”", s.doubleSpacePeriod) { repo.setDoubleSpacePeriod(it) }
        }

        SectionTitle("Área de transferência")
        SettingsGroup {
            SwitchRow(
                "Histórico de cópias",
                "Guarda os últimos textos copiados somente na memória, enquanto o teclado está ativo. Senhas são ignoradas.",
                s.clipboardHistory,
                Icons.Outlined.ContentPaste,
            ) { repo.setClipboardHistory(it) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VocabularyScreen(onBack: () -> Unit) {
    val app = LocalContext.current.escriba
    val s by app.settings.settings.collectAsState()
    val learned by app.learnedWords.words.collectAsState()
    var input by remember { mutableStateOf("") }
    val add = {
        input.split(',', ';', '\n').map { it.trim() }.filter { it.isNotEmpty() }.forEach { app.settings.addVocabulary(it) }
        input = ""
    }
    SettingsPage("Vocabulário pessoal", onBack) {
        Text(
            "Ensine ao Escriba nomes de pessoas, empresas, produtos e jargões. Eles orientam a transcrição, a revisão por IA e as sugestões do teclado.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp),
        )
        Gap(14)
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Ex.: Ana Luíza, Kubernetes, Itaú") },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { add() }),
            trailingIcon = { TextButton(onClick = add, enabled = input.isNotBlank()) { Text("Adicionar") } },
        )
        SectionTitle("Seus termos · ${s.vocabulary.size}")
        if (s.vocabulary.isEmpty()) {
            Text("Nenhum termo ainda.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 6.dp))
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                s.vocabulary.sorted().forEach { term ->
                    InputChip(
                        selected = false,
                        onClick = { app.settings.removeVocabulary(term) },
                        label = { Text(term) },
                        trailingIcon = { Icon(Icons.Outlined.Close, "Remover", modifier = Modifier.size(16.dp)) },
                    )
                }
            }
        }
        SectionTitle("Palavras aprendidas · ${learned.size}")
        Text(
            "Palavras que você digitou e manteve mesmo quando o teclado sugeria outra. Toque para esquecer.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 6.dp, bottom = 8.dp),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            learned.sorted().forEach { word ->
                AssistChip(onClick = { app.learnedWords.remove(word) }, label = { Text(word) })
            }
        }
    }
}

@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var info by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        info = withContext(Dispatchers.Default) {
            runCatching {
                NativeBridge.ensureInitialized(context)
                NativeBridge.systemInfo()
            }.getOrElse { "Indisponível: ${it.message}" }
        }
    }
    SettingsPage("Privacidade e sobre", onBack) {
        SectionTitle("Privacidade")
        SettingsGroup {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
                IconBadge(Icons.Outlined.Lock)
                Spacer(Modifier.width(14.dp))
                Text(
                    "O Escriba processa voz e texto inteiramente no seu aparelho. O áudio é mantido apenas na memória durante o ditado e descartado em seguida. " +
                        "A única conexão com a internet é o download dos modelos, feito diretamente do Hugging Face e verificado por SHA-256. " +
                        "Não há contas, anúncios, telemetria ou envio de dados.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        SectionTitle("Créditos")
        SettingsGroup {
            listOf(
                "whisper.cpp e llama.cpp" to "ggml-org · MIT",
                "Whisper" to "OpenAI · MIT",
                "Gemma 4" to "Google · Apache 2.0",
                "Qwen 3.5" to "Alibaba Qwen · Apache 2.0",
                "Silero VAD" to "Silero Team · MIT",
                "Lista de palavras" to "FrequencyWords (Hermit Dave) · CC BY-SA 4.0",
                "Emojis" to "Unicode · Unicode License",
            ).forEachIndexed { i, (name, detail) ->
                if (i > 0) GroupDivider()
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Text(name, style = MaterialTheme.typography.bodyLarge)
                    Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        SectionTitle("Sistema")
        SettingsGroup {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
                IconBadge(Icons.Outlined.Memory)
                Spacer(Modifier.width(14.dp))
                Column {
                    Text("Escriba ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyLarge)
                    Gap(4)
                    Text(
                        info.ifEmpty { "Carregando…" },
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Gap(12)
        TextButton(
            onClick = {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/maisumfernando/C-")))
            },
            modifier = Modifier.align(Alignment.CenterHorizontally),
        ) { Text("Código-fonte no GitHub") }
    }
}
