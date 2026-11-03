package com.fernandogodinho.teclado.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.fernandogodinho.teclado.tecladoApp
import com.fernandogodinho.teclado.tv.TvKeyboardActivity
import com.fernandogodinho.teclado.tv.TvLinkService
import com.fernandogodinho.teclado.tv.TvLinkStatus
import com.fernandogodinho.teclado.tv.TvPairingState
import com.fernandogodinho.teclado.ui.components.AppTopBar
import com.fernandogodinho.teclado.ui.components.Gap
import com.fernandogodinho.teclado.ui.components.GroupDivider
import com.fernandogodinho.teclado.ui.components.SectionTitle
import com.fernandogodinho.teclado.ui.components.SettingsGroup
import com.fernandogodinho.teclado.ui.components.SwitchRow

/** Pair TVs and choose whether the phone offers to type when a TV opens a text field. */
@Composable
fun TvScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.tecladoApp
    val tv = app.tv
    val settings by app.settings.settings.collectAsState()
    val paired by tv.paired.collectAsState()
    val found by tv.found.collectAsState()
    val scanning by tv.scanning.collectAsState()
    val status by tv.status.collectAsState()
    val pairing by tv.pairing.collectAsState()

    DisposableEffect(Unit) {
        tv.startScan()
        onDispose { tv.stopScan() }
    }

    Scaffold(topBar = { AppTopBar("Teclado na TV", onBack) }, containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 18.dp),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f))
                    .padding(18.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Tv, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("Digite e dite na TV pelo celular", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Gap(8)
                Text(
                    "Quando a TV abrir uma busca (YouTube, Netflix, Globoplay…), toque na notificação e escreva ou fale no celular: o texto aparece na TV na hora. " +
                        "A conexão é direta pela sua rede Wi-Fi, sem internet e sem servidor.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }

            SectionTitle("Suas TVs")
            if (paired.isEmpty()) {
                Text(
                    "Nenhuma TV pareada. Escolha a sua TV na lista abaixo; na primeira vez, ela pede para você permitir a conexão ou mostra um código.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 6.dp),
                )
            } else {
                SettingsGroup {
                    paired.forEachIndexed { i, p ->
                        if (i > 0) GroupDivider()
                        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(p.name, style = MaterialTheme.typography.bodyLarge)
                                Text(p.platform.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    when (status[p.name]) {
                                        TvLinkStatus.CONNECTED -> "Conectada"
                                        TvLinkStatus.CONNECTING -> "Conectando…"
                                        TvLinkStatus.NEEDS_PAIRING -> "Precisa parear de novo"
                                        TvLinkStatus.UNREACHABLE -> "Fora do alcance agora"
                                        else -> "Pareada"
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (status[p.name] == TvLinkStatus.CONNECTED) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            FilledTonalButton(onClick = { context.startActivity(TvKeyboardActivity.intent(context, p.name)) }, shape = RoundedCornerShape(12.dp)) {
                                Text("Digitar")
                            }
                            TextButton(onClick = { tv.forget(p.name) }) { Text("Esquecer") }
                        }
                    }
                }
                Gap(12)
                SettingsGroup {
                    SwitchRow(
                        "Avisar quando a TV pedir texto",
                        "Mantém a conexão com a TV enquanto o celular estiver no mesmo Wi-Fi e mostra uma notificação quando ela abrir uma caixa de texto. Usa um pouco de bateria.",
                        settings.tvListen,
                    ) { on ->
                        app.settings.setTvListen(on)
                        if (on) TvLinkService.start(context) else TvLinkService.stop(context)
                    }
                }
            }

            SectionTitle("TVs na rede")
            SettingsGroup {
                val notPaired = found.filter { f -> paired.none { it.name == f.name && it.platform == f.platform } }
                if (notPaired.isEmpty()) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (scanning) {
                            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(12.dp))
                        }
                        Text(
                            if (scanning) "Procurando TVs no seu Wi-Fi…" else "Nenhuma TV nova encontrada.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                notPaired.forEachIndexed { i, f ->
                    if (i > 0) GroupDivider()
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Tv, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(f.name, style = MaterialTheme.typography.bodyLarge)
                            Text(f.platform.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Button(onClick = { tv.startPairing(f) }, shape = RoundedCornerShape(12.dp)) {
                            Text(if (f.platform == com.fernandogodinho.teclado.tv.TvPlatform.ROKU) "Adicionar" else "Parear")
                        }
                    }
                }
            }
            Gap(14)
            Text(
                "Funciona com Samsung (Tizen, 2016 em diante), LG (webOS), TVs com Google TV ou Android TV (TCL, Sony, Philips, Philco, Multilaser, Sharp, Hisense e outras) " +
                    "e TVs Roku (TCL, Semp, AOC, Philco Roku TV). Nas Roku, a TV não avisa quando abre uma busca: abra “Digitar na TV” pelo atalho ou pelo app. " +
                    "TVs Hisense com sistema VIDAA e Panasonic ainda não são compatíveis. " +
                    "Dica: adicione o atalho “Digitar na TV” às Configurações rápidas do celular.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 6.dp),
            )
            Gap(28)
        }
    }

    pairing?.let { PairingDialog(it, onSubmit = { code -> tv.submitCode(code) }, onDismiss = { tv.cancelPairing() }) }
}

@Composable
private fun PairingDialog(state: TvPairingState, onSubmit: (String) -> Unit, onDismiss: () -> Unit) {
    var code by remember(state.tv) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Tv, null) },
        title = { Text("Parear com ${state.tv.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when (state.step) {
                    TvPairingState.Step.CONNECTING, TvPairingState.Step.CHECKING -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(
                            when {
                                state.step == TvPairingState.Step.CHECKING && state.tv.platform == com.fernandogodinho.teclado.tv.TvPlatform.ROKU -> "Conferindo se a TV aceita comandos…"
                                state.step == TvPairingState.Step.CONNECTING -> "Conectando à TV…"
                                else -> "Conferindo o código…"
                            },
                        )
                    }
                    TvPairingState.Step.WAIT_TV -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(12.dp))
                            Text("Olhe para a TV")
                        }
                        Text(state.tv.platform.pairingHint)
                    }
                    TvPairingState.Step.ENTER_CODE -> {
                        Text("Digite o código de 6 letras e números que apareceu na tela da TV.")
                        OutlinedTextField(
                            value = code,
                            onValueChange = { v -> code = v.uppercase().filter { it in "0123456789ABCDEF" }.take(6) },
                            singleLine = true,
                            label = { Text("Código da TV") },
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                        )
                    }
                    TvPairingState.Step.DONE -> Text("Pronto! A TV está pareada com este celular.")
                    TvPairingState.Step.FAILED -> Unit
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            when (state.step) {
                TvPairingState.Step.ENTER_CODE -> TextButton(onClick = { onSubmit(code) }, enabled = code.length == 6) { Text("Parear") }
                TvPairingState.Step.DONE, TvPairingState.Step.FAILED -> TextButton(onClick = onDismiss) { Text("OK") }
                else -> Unit
            }
        },
        dismissButton = {
            if (state.step != TvPairingState.Step.DONE) TextButton(onClick = onDismiss) { Text("Cancelar") }
        },
    )
}
