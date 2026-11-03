package com.fernandogodinho.teclado.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.fernandogodinho.teclado.tecladoApp
import com.fernandogodinho.teclado.ui.theme.TecladoTheme

/**
 * "Escrever com calma": a roomy editor for long messages. The keyboard (with dictation and the
 * AI tools) works here as usual; "Inserir" sends the text back to the app it came from. The
 * draft only lives in memory and is discarded after use.
 */
class ComposerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val app = tecladoApp
        val initial = app.composer.draft
        setContent {
            val settings by app.settings.settings.collectAsState()
            TecladoTheme(settings.theme) {
                var field by rememberSaveable(stateSaver = TextFieldValue.Saver) {
                    mutableStateOf(TextFieldValue(initial, TextRange(initial.length)))
                }
                val focus = remember { FocusRequester() }
                LaunchedEffect(Unit) { focus.requestFocus() }
                BackHandler {
                    app.composer.discard()
                    finish()
                }
                Column(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .statusBarsPadding()
                        .navigationBarsPadding()
                        .imePadding()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { app.composer.discard(); finish() }) { Icon(Icons.Outlined.Close, "Descartar") }
                        Column(Modifier.weight(1f)) {
                            Text("Escrever com calma", style = MaterialTheme.typography.titleMedium)
                            val words = field.text.split(Regex("\\s+")).count { it.isNotBlank() }
                            Text(
                                "$words palavras · ${field.text.length} caracteres",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Button(
                            onClick = {
                                app.composer.finish(field.text)
                                finish()
                            },
                            enabled = field.text.isNotBlank(),
                            shape = RoundedCornerShape(14.dp),
                        ) { Text("Inserir") }
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        value = field,
                        onValueChange = { field = it },
                        modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 8.dp).focusRequester(focus),
                        placeholder = { Text("Escreva ou dite a mensagem com calma. Use as ferramentas de IA do teclado para revisar. Ao terminar, toque em Inserir.") },
                        shape = RoundedCornerShape(18.dp),
                    )
                    Text(
                        "O rascunho fica só na memória e é apagado ao inserir ou descartar.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
    }
}
