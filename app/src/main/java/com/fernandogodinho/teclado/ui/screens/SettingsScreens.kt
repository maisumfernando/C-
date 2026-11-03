package com.fernandogodinho.teclado.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import com.fernandogodinho.teclado.ime.ui.KeyboardThemeId
import com.fernandogodinho.teclado.ime.ui.KeyboardPalette
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.fernandogodinho.teclado.BuildConfig
import com.fernandogodinho.teclado.R
import com.fernandogodinho.teclado.core.DictationLanguage
import com.fernandogodinho.teclado.core.MIN_KEYBOARD_HEIGHT
import com.fernandogodinho.teclado.core.PolishStyle
import com.fernandogodinho.teclado.core.OneHandedMode
import com.fernandogodinho.teclado.core.ThemeMode
import com.fernandogodinho.teclado.engine.NativeBridge
import com.fernandogodinho.teclado.tecladoApp
import com.fernandogodinho.teclado.ui.components.AppTopBar
import com.fernandogodinho.teclado.ui.components.Gap
import com.fernandogodinho.teclado.ui.components.GroupDivider
import com.fernandogodinho.teclado.ui.components.IconBadge
import com.fernandogodinho.teclado.ui.components.RadioRow
import com.fernandogodinho.teclado.ui.components.SectionTitle
import com.fernandogodinho.teclado.ui.components.SettingsGroup
import com.fernandogodinho.teclado.ui.components.SwitchRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@Composable
internal fun SettingsPage(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Scaffold(topBar = { AppTopBar(title, onBack) }, containerColor = MaterialTheme.colorScheme.background) { padding ->
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
    val app = LocalContext.current.tecladoApp
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
                "Adaptar o tom ao aplicativo",
                "Mensagens (WhatsApp, Telegram…) ficam leves e diretas; e-mails e documentos, formais.",
                s.adaptToneToApp,
            ) { repo.setAdaptToneToApp(it) }
            GroupDivider()
            SwitchRow(
                "Transcrever durante as pausas",
                "Adianta a transcrição enquanto você fala, para o texto ficar pronto quase na hora.",
                s.pauseToTranscribe,
            ) { repo.setPauseToTranscribe(it) }
            GroupDivider()
            SwitchRow(
                "Transcrição acelerada",
                "Processa só o áudio necessário em vez de janelas fixas de 30 s. Mais rápido; se algo parecer errado, a transcrição é refeita automaticamente.",
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
        Text("O teclado escreve", style = MaterialTheme.typography.labelMedium, color = scheme.primary)
        Text(output, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
fun KeyboardSettingsScreen(onBack: () -> Unit) {
    val app = LocalContext.current.tecladoApp
    val s by app.settings.settings.collectAsState()
    val repo = app.settings
    SettingsPage("Teclado", onBack) {
        SectionTitle("Aparência")
        SettingsGroup {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(Icons.Outlined.Palette)
                    Spacer(Modifier.width(14.dp))
                    Text("Claro ou escuro", style = MaterialTheme.typography.bodyLarge)
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
                Text("Tema do teclado", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "O Clássico acompanha o claro/escuro acima; os outros têm cores próprias. Todos foram conferidos para manter o texto legível.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Gap(12)
                val dark = when (s.theme) {
                    ThemeMode.SYSTEM -> androidx.compose.foundation.isSystemInDarkTheme()
                    ThemeMode.LIGHT -> false
                    ThemeMode.DARK -> true
                }
                KeyboardThemePicker(selected = s.keyboardTheme, dark = dark) { repo.setKeyboardTheme(it) }
            }
            GroupDivider()
            Column(Modifier.padding(16.dp)) {
                var h by remember(s.keyboardHeight) { mutableFloatStateOf(s.keyboardHeight) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(Icons.Outlined.Height)
                    Spacer(Modifier.width(14.dp))
                    Text("Altura do teclado · ${(h * 100).roundToInt()}%", style = MaterialTheme.typography.bodyLarge)
                }
                Slider(
                    value = h,
                    onValueChange = { h = it },
                    onValueChangeFinished = { repo.setKeyboardHeight(h) },
                    valueRange = MIN_KEYBOARD_HEIGHT..1f,
                    steps = 5,
                )
                Text(
                    "100% é o tamanho completo, ajustado à tela do seu celular. Diminua para um teclado mais compacto.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            GroupDivider()
            SwitchRow(
                "Barra de espaço como trackpad",
                "Toque e segure o espaço: o teclado inteiro vira um trackpad para mover o cursor pelo texto. Deslizar o espaço para os lados também move o cursor. Para trocar de teclado, segure o botão de emoji.",
                s.spaceTrackpad,
            ) { repo.setSpaceTrackpad(it) }
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

        SectionTitle("Uma mão")
        SettingsGroup {
            Column(Modifier.padding(16.dp)) {
                Text("Modo uma mão", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Deixa o teclado mais estreito e encostado em um lado da tela. Também pode ser ligado pelo ícone de mão na barra do teclado.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Gap(12)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    OneHandedMode.entries.forEachIndexed { i, mode ->
                        SegmentedButton(
                            selected = s.oneHanded == mode,
                            onClick = { repo.setOneHanded(mode) },
                            shape = SegmentedButtonDefaults.itemShape(i, OneHandedMode.entries.size),
                        ) { Text(mode.label) }
                    }
                }
            }
        }

        SectionTitle("Privacidade ao digitar")
        SettingsGroup {
            SwitchRow(
                "Aprender palavras novas",
                "Guarda no aparelho as palavras que você mantém. Nunca em campos de senha ou abas anônimas.",
                s.learnWords,
            ) { repo.setLearnWords(it) }
            GroupDivider()
            SwitchRow(
                "Sugerir colar o que acabou de copiar",
                "Por 3 minutos, mostra um atalho para colar. Em campos de código, oferece só o código (ex.: SMS ou e-mail de verificação).",
                s.smartPaste,
            ) { repo.setSmartPaste(it) }
        }

        SectionTitle("Área de transferência")
        SettingsGroup {
            SwitchRow(
                "Histórico de cópias",
                "Guarda os últimos textos copiados só na memória, por até 1 hora. Senhas e campos anônimos são ignorados.",
                s.clipboardHistory,
                Icons.Outlined.ContentPaste,
            ) { repo.setClipboardHistory(it) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VocabularyScreen(onBack: () -> Unit) {
    val app = LocalContext.current.tecladoApp
    val s by app.settings.settings.collectAsState()
    val learned by app.learnedWords.words.collectAsState()
    var input by remember { mutableStateOf("") }
    val add = {
        input.split(',', ';', '\n').map { it.trim() }.filter { it.isNotEmpty() }.forEach { app.settings.addVocabulary(it) }
        input = ""
    }
    SettingsPage("Vocabulário pessoal", onBack) {
        Text(
            "Ensine ao teclado nomes de pessoas, empresas, produtos e jargões. Eles orientam a transcrição, a revisão por IA e as sugestões do teclado.",
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
                    "O teclado processa voz e texto inteiramente no seu aparelho. O áudio é mantido apenas na memória durante o ditado e descartado em seguida. " +
                        "A única conexão com a internet é o download dos modelos, feito diretamente do Hugging Face e verificado por SHA-256. " +
                        "Se você usar o Teclado na TV, só o texto escrito na tela “Digitar na TV” vai para a sua TV, direto pela rede Wi-Fi da casa. " +
                        "Não há contas, anúncios, telemetria ou envio de dados.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        SectionTitle("Atualizações")
        val app = context.tecladoApp
        val st by app.settings.settings.collectAsState()
        SettingsGroup {
            SwitchRow(
                "Procurar atualizações",
                "A cada poucos dias, consulta o site oficial por novos modelos e versões. Nada é baixado nem instalado sem você tocar.",
                st.checkUpdates,
            ) { app.settings.setCheckUpdates(it) }
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
                    Text("${stringResource(R.string.app_name)} ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyLarge)
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


/** Grid of small keyboard previews, one per theme. */
@Composable
fun KeyboardThemePicker(selected: KeyboardThemeId, dark: Boolean, onSelect: (KeyboardThemeId) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        KeyboardThemeId.entries.forEach { id ->
            val c = KeyboardPalette.of(id, dark)
            val isSelected = id == selected
            Column(
                Modifier
                    .width(96.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { onSelect(id) },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .border(
                            if (isSelected) 2.5.dp else 1.dp,
                            if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                            RoundedCornerShape(14.dp),
                        )
                        .background(c.background)
                        .padding(7.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Box(Modifier.size(14.dp).clip(CircleShape).background(c.mic))
                    }
                    repeat(2) { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            repeat(5) { i ->
                                Box(
                                    Modifier.weight(1f).height(13.dp).clip(RoundedCornerShape(3.dp)).background(c.key),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (row == 0 && i < 3) {
                                        Text("abc"[i].toString(), color = c.text, fontSize = 8.sp, lineHeight = 9.sp)
                                    }
                                }
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        Box(Modifier.weight(1f).height(13.dp).clip(RoundedCornerShape(3.dp)).background(c.functionKey))
                        Box(Modifier.weight(3f).height(13.dp).clip(RoundedCornerShape(3.dp)).background(c.key))
                        Box(Modifier.weight(1f).height(13.dp).clip(RoundedCornerShape(3.dp)).background(c.accent))
                    }
                }
                Gap(6)
                Text(
                    id.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}
