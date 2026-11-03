package com.fernandogodinho.teclado.ui.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.fernandogodinho.teclado.core.Snippet
import com.fernandogodinho.teclado.core.SnippetStore
import com.fernandogodinho.teclado.tecladoApp
import com.fernandogodinho.teclado.ui.components.Gap
import com.fernandogodinho.teclado.ui.components.GroupDivider
import com.fernandogodinho.teclado.ui.components.SectionTitle
import com.fernandogodinho.teclado.ui.components.SettingsGroup

/** Saved replies and "/atalho" shortcuts: create, edit, search, delete and export. */
@Composable
fun SnippetsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = context.tecladoApp.snippets
    val items by store.items.collectAsState()
    var query by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<Snippet?>(null) }
    var confirmDeleteAll by remember { mutableStateOf(false) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { it.write(SnippetStore.toJson(items).toByteArray()) }
            }.onSuccess { Toast.makeText(context, "Respostas exportadas", Toast.LENGTH_SHORT).show() }
        }
    }

    SettingsPage("Respostas e atalhos", onBack) {
        Text(
            "Guarde textos que você usa sempre: assinatura, endereço, chave Pix, respostas de atendimento. " +
                "No teclado, toque no ícone de marcador ou digite / seguido do atalho (por exemplo, /assinatura). " +
                "Tudo fica só neste celular.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { editing = Snippet(shortcut = "", title = "", text = "") }, shape = RoundedCornerShape(14.dp)) {
                Icon(Icons.Outlined.Add, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Nova resposta")
            }
            if (items.isNotEmpty()) {
                OutlinedButton(onClick = { export.launch("respostas-teclado.json") }, shape = RoundedCornerShape(14.dp)) { Text("Exportar") }
            }
        }
        if (items.size > 4) {
            Gap(12)
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Buscar") },
                shape = RoundedCornerShape(14.dp),
            )
        }
        val shown = store.search(query).let { found -> items.filter { s -> found.any { it.id == s.id } } }
        shown.groupBy { it.category.ifBlank { "Sem categoria" } }.forEach { (category, list) ->
            SectionTitle(category)
            SettingsGroup {
                list.forEachIndexed { i, s ->
                    if (i > 0) GroupDivider()
                    Column(Modifier.fillMaxWidth().clickable { editing = s }.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (s.sensitive) {
                                Icon(Icons.Outlined.Lock, "Protegida", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                            }
                            Text(s.title.ifBlank { "Sem título" }, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            if (s.shortcut.isNotEmpty()) Text("/${s.shortcut}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        }
                        Text(
                            if (s.sensitive) "Conteúdo protegido" else s.text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                        )
                    }
                }
            }
        }
        if (items.isNotEmpty()) {
            Gap(16)
            TextButton(onClick = { confirmDeleteAll = true }) { Text("Apagar todas", color = MaterialTheme.colorScheme.error) }
        }
        Gap(28)
    }

    editing?.let { s ->
        SnippetEditor(
            initial = s,
            isNew = items.none { it.id == s.id },
            onSave = { store.upsert(it); editing = null },
            onDelete = { store.delete(s.id); editing = null },
            onDismiss = { editing = null },
        )
    }
    if (confirmDeleteAll) {
        AlertDialog(
            onDismissRequest = { confirmDeleteAll = false },
            title = { Text("Apagar todas as respostas?") },
            text = { Text("Elas serão removidas deste celular. Exporte antes se quiser guardar uma cópia.") },
            confirmButton = { TextButton(onClick = { store.deleteAll(); confirmDeleteAll = false }) { Text("Apagar") } },
            dismissButton = { TextButton(onClick = { confirmDeleteAll = false }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun SnippetEditor(initial: Snippet, isNew: Boolean, onSave: (Snippet) -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    var title by remember { mutableStateOf(initial.title) }
    var shortcut by remember { mutableStateOf(initial.shortcut) }
    var text by remember { mutableStateOf(initial.text) }
    var category by remember { mutableStateOf(initial.category) }
    var sensitive by remember { mutableStateOf(initial.sensitive) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "Nova resposta" else "Editar resposta") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("Título") }, singleLine = true)
                OutlinedTextField(
                    shortcut,
                    { shortcut = SnippetStore.normalizeShortcut(it) },
                    label = { Text("Atalho (opcional)") },
                    prefix = { Text("/") },
                    singleLine = true,
                )
                OutlinedTextField(text, { text = it.take(SnippetStore.MAX_TEXT) }, label = { Text("Texto") }, modifier = Modifier.heightIn(min = 110.dp))
                OutlinedTextField(category, { category = it }, label = { Text("Categoria (opcional)") }, singleLine = true)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { sensitive = !sensitive }) {
                    Checkbox(checked = sensitive, onCheckedChange = { sensitive = it })
                    Text("Protegida (documentos, endereço): fica oculta e pede dois toques para inserir", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(initial.copy(title = title.ifBlank { text.take(30) }, shortcut = shortcut, text = text, category = category, sensitive = sensitive)) },
                enabled = text.isNotBlank(),
            ) { Text("Salvar") }
        },
        dismissButton = {
            Row {
                if (!isNew) TextButton(onClick = onDelete) { Text("Excluir", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("Cancelar") }
            }
        },
    )
}
