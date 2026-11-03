package com.fernandogodinho.teclado.tv

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.fernandogodinho.teclado.tecladoApp
import com.fernandogodinho.teclado.ui.MainActivity
import com.fernandogodinho.teclado.ui.theme.TecladoTheme
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * "Digitar na TV": a text box on the phone mirrored to the TV's focused field. Opens over the
 * home screen (from the notification, the quick settings tile or the app). Whatever keyboard is
 * active works here, including this one with dictation.
 */
class TvKeyboardActivity : ComponentActivity() {

    private var tvName by mutableStateOf<String?>(null)
    private var initialText by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        readIntent(intent)
        val app = tecladoApp
        getSystemService(android.app.NotificationManager::class.java).cancel(TvLinkService.REQUEST_ID)
        setContent {
            val settings by app.settings.settings.collectAsState()
            TecladoTheme(settings.theme) {
                TvKeyboardScreen(
                    requested = tvName,
                    initial = initialText,
                    onPairNew = {
                        startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_ROUTE, MainActivity.ROUTE_TV))
                        finish()
                    },
                    onClose = { finish() },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readIntent(intent)
    }

    private fun readIntent(intent: Intent?) {
        tvName = intent?.getStringExtra(EXTRA_TV)
        initialText = intent?.getStringExtra(EXTRA_TEXT).orEmpty().take(500)
    }

    override fun onStart() {
        super.onStart()
        visible = true
    }

    override fun onStop() {
        visible = false
        super.onStop()
    }

    companion object {
        private const val EXTRA_TV = "tv"
        private const val EXTRA_TEXT = "text"

        /** True while the screen is open, so the service does not notify about the same field. */
        @Volatile var visible = false

        fun intent(context: Context, tv: String?, text: String? = null): Intent =
            Intent(context, TvKeyboardActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .apply {
                    if (tv != null) putExtra(EXTRA_TV, tv)
                    if (text != null) putExtra(EXTRA_TEXT, text)
                }
    }
}

@OptIn(FlowPreview::class)
@Composable
private fun TvKeyboardScreen(requested: String?, initial: String, onPairNew: () -> Unit, onClose: () -> Unit) {
    val app = androidx.compose.ui.platform.LocalContext.current.tecladoApp
    val tv = app.tv
    val paired by tv.paired.collectAsState()
    val status by tv.status.collectAsState()
    var chosen by rememberSaveable(requested) { mutableStateOf(requested ?: paired.singleOrNull()?.name) }
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 18.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.Tv, null, tint = MaterialTheme.colorScheme.primary) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Digitar na TV", style = MaterialTheme.typography.titleMedium)
                Text(
                    chosen?.let { name ->
                        when (status[name]) {
                            TvLinkStatus.CONNECTED -> "Conectado a $name"
                            TvLinkStatus.CONNECTING -> "Conectando a $name…"
                            TvLinkStatus.NEEDS_PAIRING -> "$name precisa ser pareada de novo"
                            TvLinkStatus.UNREACHABLE -> "$name não respondeu. Ela está ligada e no mesmo Wi-Fi?"
                            else -> name
                        }
                    } ?: "Escolha a TV",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, "Fechar") }
        }

        val name = chosen
        when {
            paired.isEmpty() -> {
                Spacer(Modifier.heightIn(min = 24.dp))
                Text(
                    "Nenhuma TV pareada ainda. Pareie a sua TV uma vez (leva meio minuto) e depois é só tocar na notificação.",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(Modifier.heightIn(min = 16.dp))
                Button(onClick = onPairNew, shape = RoundedCornerShape(14.dp)) { Text("Parear uma TV") }
            }
            name == null -> {
                Spacer(Modifier.heightIn(min = 16.dp))
                paired.forEach { p ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { chosen = p.name }
                            .background(MaterialTheme.colorScheme.surfaceContainerLow)
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.Tv, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Text(p.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    }
                    Spacer(Modifier.heightIn(min = 8.dp))
                }
            }
            else -> {
                LaunchedEffect(name) { tv.awaitConnected(name) }
                var field by remember(name) { mutableStateOf(TextFieldValue(initial, TextRange(initial.length))) }
                val focus = remember { FocusRequester() }
                LaunchedEffect(name) {
                    focus.requestFocus()
                    keyboard?.show()
                }
                // Mirror the text to the TV as the user types (or dictates).
                LaunchedEffect(name) {
                    snapshotFlow { field.text }
                        .distinctUntilChanged()
                        .debounce(120)
                        .collect { text -> if (text != initial || text.isNotEmpty()) tv.setText(name, text) }
                }
                Spacer(Modifier.heightIn(min = 16.dp))
                OutlinedTextField(
                    value = field,
                    onValueChange = { field = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp).focusRequester(focus),
                    placeholder = { Text("O que você digitar aqui aparece na TV") },
                    shape = RoundedCornerShape(18.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { scope.launch { tv.key(name, TvKey.ENTER) } }),
                )
                Spacer(Modifier.heightIn(min = 12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { scope.launch { tv.key(name, TvKey.ENTER) } }, shape = RoundedCornerShape(14.dp)) {
                        Icon(Icons.AutoMirrored.Outlined.Send, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Enviar")
                    }
                    if (tv.platformOf(name) != TvPlatform.LG) {
                        FilledTonalButton(onClick = { scope.launch { tv.key(name, TvKey.BACK) } }, shape = RoundedCornerShape(14.dp)) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Voltar")
                        }
                    }
                    TextButton(onClick = {
                        field = TextFieldValue("")
                        scope.launch { tv.setText(name, "") }
                    }) { Text("Limpar") }
                }
                if (status[name] == TvLinkStatus.CONNECTING) {
                    Spacer(Modifier.heightIn(min = 12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(10.dp))
                        Text("Conectando…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (status[name] == TvLinkStatus.NEEDS_PAIRING) {
                    Spacer(Modifier.heightIn(min = 12.dp))
                    TextButton(onClick = onPairNew) { Text("Parear de novo") }
                }
                if (status[name] == TvLinkStatus.UNREACHABLE || status[name] == TvLinkStatus.DISCONNECTED) {
                    Spacer(Modifier.heightIn(min = 12.dp))
                    TextButton(onClick = { scope.launch { tv.awaitConnected(name) } }) { Text("Tentar conectar de novo") }
                }
                if (paired.size > 1) {
                    TextButton(onClick = { chosen = null }) { Text("Trocar de TV") }
                }
                Spacer(Modifier.heightIn(min = 8.dp))
                Text(
                    "Só o texto desta caixa vai para a TV, pela sua rede Wi-Fi. Nada passa pela internet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Quick settings tile: "Digitar na TV" from anywhere, even with no app open. */
class TvTileService : android.service.quicksettings.TileService() {
    override fun onClick() {
        super.onClick()
        val intent = TvKeyboardActivity.intent(this, null)
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                android.app.PendingIntent.getActivity(this, 5, intent, android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT),
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
