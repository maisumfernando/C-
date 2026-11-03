package app.escriba.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.escriba.core.DeviceProfile
import app.escriba.escriba
import app.escriba.models.ModelCatalog
import app.escriba.models.formatBytes
import app.escriba.ui.MainActivity
import app.escriba.ui.SetupState
import app.escriba.ui.components.Gap
import app.escriba.ui.components.GroupDivider
import app.escriba.ui.components.NavRow
import app.escriba.ui.components.SectionTitle
import app.escriba.ui.components.SettingsGroup

@Composable
fun AppLogo(size: Int = 64) {
    Box(
        Modifier
            .size(size.dp)
            .clip(RoundedCornerShape((size * 0.3f).dp))
            .background(Brush.linearGradient(listOf(Color(0xFF4E6BFF), Color(0xFF2B3FB8)))),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Outlined.AutoAwesome, null, tint = Color.White, modifier = Modifier.size((size * 0.48f).dp))
    }
}

@Composable
fun OnboardingScreen(
    setup: SetupState,
    onEnable: () -> Unit,
    onSelect: () -> Unit,
    onMic: () -> Unit,
    onModels: () -> Unit,
    onFinish: () -> Unit,
) {
    val context = LocalContext.current
    val ram = DeviceProfile.totalRamGb(context)
    val (speech, text) = ModelCatalog.recommended(ram)
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 22.dp),
    ) {
        Gap(36)
        AppLogo(68)
        Gap(22)
        Text("Bem-vindo ao Escriba", style = MaterialTheme.typography.headlineMedium)
        Gap(8)
        Text(
            "Fale naturalmente e receba um texto revisado, em português impecável. Tudo acontece no seu celular: nenhum áudio sai do aparelho.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Gap(26)

        StepCard(1, "Ative o teclado", "Ligue o Escriba nas configurações de teclado do Android.", setup.enabled, "Ativar", onEnable)
        Gap(10)
        StepCard(2, "Escolha o Escriba", "Defina o Escriba como seu teclado atual.", setup.selected, "Selecionar", onSelect, enabled = setup.enabled)
        Gap(10)
        StepCard(3, "Permita o microfone", "Necessário para o ditado. O áudio nunca é gravado nem enviado.", setup.micGranted, "Permitir", onMic)
        Gap(10)
        StepCard(
            4,
            "Baixe os modelos de IA",
            "Recomendado para o seu aparelho: ${speech.name} + ${text.name} (${formatBytes(speech.sizeBytes + text.sizeBytes)}). O download é feito uma única vez.",
            setup.speechModelReady,
            "Escolher",
            onModels,
        )

        Gap(28)
        Button(
            onClick = onFinish,
            enabled = setup.complete,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 54.dp),
            shape = RoundedCornerShape(16.dp),
        ) {
            Text("Começar a usar")
        }
        TextButton(onClick = onFinish, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text("Configurar depois")
        }
        Gap(20)
    }
}

@Composable
private fun StepCard(
    number: Int,
    title: String,
    description: String,
    done: Boolean,
    action: String,
    onAction: () -> Unit,
    enabled: Boolean = true,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(scheme.surfaceContainerLow)
            .border(1.dp, if (done) scheme.primary.copy(alpha = 0.35f) else scheme.outlineVariant, RoundedCornerShape(20.dp))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(if (done) scheme.primary else scheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            if (done) {
                Icon(Icons.Filled.Check, null, tint = scheme.onPrimary, modifier = Modifier.size(18.dp))
            } else {
                Text("$number", style = MaterialTheme.typography.labelLarge, color = scheme.onSecondaryContainer)
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Gap(2)
            Text(description, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
        }
        if (!done) {
            Spacer(Modifier.width(10.dp))
            FilledTonalButton(onClick = onAction, enabled = enabled, shape = RoundedCornerShape(12.dp)) { Text(action) }
        }
    }
}

@Composable
fun HomeScreen(
    setup: SetupState,
    navigate: (String) -> Unit,
    onEnable: () -> Unit,
    onSelect: () -> Unit,
    onMic: () -> Unit,
) {
    val app = LocalContext.current.escriba
    val settings by app.settings.settings.collectAsState()
    val installed by app.models.installed.collectAsState()
    val speech = remember(installed, settings) { app.engines.selectedSpeechModel(settings) }
    val text = remember(installed, settings) { app.engines.selectedTextModel(settings) }
    var tryText by rememberSaveable { mutableStateOf("") }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 18.dp),
    ) {
        Gap(20)
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppLogo(44)
            Spacer(Modifier.width(14.dp))
            Column {
                Text("Escriba", style = MaterialTheme.typography.headlineSmall)
                Text(
                    if (setup.complete) "Pronto para ditar" else "Configuração pendente",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (setup.complete) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
                )
            }
        }

        AnimatedVisibility(!setup.complete) {
            Column {
                Gap(16)
                if (!setup.enabled) Warning("O teclado Escriba está desativado.", "Ativar", onEnable)
                else if (!setup.selected) Warning("O Escriba não é o teclado atual.", "Selecionar", onSelect)
                if (!setup.micGranted) { Gap(8); Warning("O microfone não foi permitido.", "Permitir", onMic) }
                if (!setup.speechModelReady) { Gap(8); Warning("Nenhum modelo de voz instalado.", "Baixar") { navigate(MainActivity.ROUTE_MODELS) } }
            }
        }

        SectionTitle("Experimente")
        OutlinedTextField(
            value = tryText,
            onValueChange = { tryText = it },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 120.dp),
            placeholder = {
                Text("Toque aqui e use o microfone do teclado. Fale como quiser: o Escriba entrega o texto revisado.")
            },
            shape = RoundedCornerShape(18.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
            ),
        )

        SectionTitle("Ajustes")
        SettingsGroup {
            NavRow(
                Icons.Outlined.Memory,
                "Modelos de IA",
                subtitle = listOfNotNull(speech?.name, text?.name).joinToString(" · ").ifEmpty { "Nenhum modelo instalado" },
            ) { navigate(MainActivity.ROUTE_MODELS) }
            GroupDivider()
            NavRow(
                Icons.Outlined.Mic,
                "Ditado",
                subtitle = "${settings.language.label} · ${settings.polishStyle.label}",
            ) { navigate(MainActivity.ROUTE_DICTATION) }
            GroupDivider()
            NavRow(Icons.Outlined.Keyboard, "Teclado", subtitle = "Aparência, vibração e autocorreção") { navigate(MainActivity.ROUTE_KEYBOARD) }
            GroupDivider()
            NavRow(
                Icons.Outlined.MenuBook,
                "Vocabulário pessoal",
                subtitle = "Nomes e termos que você usa",
                trailing = settings.vocabulary.size.takeIf { it > 0 }?.toString(),
            ) { navigate(MainActivity.ROUTE_VOCABULARY) }
            GroupDivider()
            NavRow(Icons.Outlined.Info, "Privacidade e sobre") { navigate(MainActivity.ROUTE_ABOUT) }
        }

        SectionTitle("Como funciona")
        SettingsGroup {
            HowRow(Icons.Outlined.Mic, "Fale", "Toque no microfone (ou segure para falar). As pausas são transcritas enquanto você continua falando.")
            GroupDivider()
            HowRow(Icons.Outlined.AutoAwesome, "Revisão por IA", "Vícios de linguagem, repetições e autocorreções somem. O texto sai na norma-padrão, preservando o que você disse.")
            GroupDivider()
            HowRow(Icons.Outlined.Lock, "Privado de verdade", "Whisper e o modelo de texto rodam no processador do seu celular. Sem nuvem, sem conta.")
            GroupDivider()
            HowRow(Icons.Outlined.Bolt, "Também para texto digitado", "Use o botão ✦ da barra do teclado para revisar um texto selecionado.")
        }
        Gap(28)
    }
}

@Composable
private fun HowRow(icon: ImageVector, title: String, text: String) {
    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
        app.escriba.ui.components.IconBadge(icon)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Warning(text: String, action: String, onAction: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(scheme.error.copy(alpha = 0.08f))
            .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.WarningAmber, null, tint = scheme.error, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        TextButton(onClick = onAction) { Text(action) }
    }
}
