package com.fernandogodinho.teclado.ime.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AlternateEmail
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Calculate
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Straighten
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fernandogodinho.teclado.engine.ToolMode
import com.fernandogodinho.teclado.engine.WritingTool
import com.fernandogodinho.teclado.ime.KeyboardController
import com.fernandogodinho.teclado.ime.Panel
import com.fernandogodinho.teclado.ime.SmartChip
import com.fernandogodinho.teclado.ime.SmartKind
import com.fernandogodinho.teclado.tecladoApp
import com.fernandogodinho.teclado.ui.MainActivity

/** Calculator results, conversions, saved replies, reminders and e-mail domains, one tap each. */
@Composable
fun SmartChipRow(chips: List<SmartChip>) {
    val colors = LocalKeyboardColors.current
    Row(
        Modifier.fillMaxSize().horizontalScroll(rememberScrollState()).padding(start = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        chips.forEach { chip ->
            Row(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(colors.chip)
                    .clickable { chip.action() }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val icon = when (chip.kind) {
                    SmartKind.CALCULATOR -> Icons.Outlined.Calculate
                    SmartKind.CONVERSION -> Icons.Outlined.Straighten
                    SmartKind.SNIPPET -> Icons.Outlined.BookmarkBorder
                    SmartKind.REMINDER -> Icons.Outlined.Schedule
                    SmartKind.EMAIL_DOMAIN -> Icons.Outlined.AlternateEmail
                }
                Icon(icon, null, tint = colors.accent, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    chip.label.removePrefix("⏰ ").removePrefix("↳ ").removePrefix("🔒 "),
                    color = colors.text,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 260.dp),
                )
                if (chip.label.startsWith("🔒")) {
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Outlined.Lock, "Protegido", tint = colors.textMuted, modifier = Modifier.size(13.dp))
                }
            }
        }
    }
}

/** Saved replies inside the keyboard; they are created and edited in the app. */
@Composable
fun SnippetsPanel(controller: KeyboardController) {
    val colors = LocalKeyboardColors.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val items by context.tecladoApp.snippets.items.collectAsState()
    var category by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Respostas salvas", color = colors.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text("Toque para inserir · digite / e o atalho em qualquer campo", color = colors.textMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            PanelButton("Editar") { controller.openApp(MainActivity.ROUTE_SNIPPETS) }
            PanelButton("ABC") { controller.showPanel(Panel.KEYBOARD) }
        }
        val categories = items.map { it.category }.filter { it.isNotBlank() }.distinct()
        if (categories.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (listOf<String?>(null) + categories).forEach { c ->
                    val active = c == category
                    Text(
                        c ?: "Todas",
                        color = if (active) colors.onAccent else colors.text,
                        fontSize = 13.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(if (active) colors.accent else colors.chip)
                            .clickable { category = c }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
        }
        if (items.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "Guarde aqui assinaturas, endereços e respostas que você usa sempre. Crie a primeira no app.",
                    color = colors.textMuted,
                    fontSize = 14.sp,
                )
                Spacer(Modifier.height(10.dp))
                PanelButton("Criar resposta") { controller.openApp(MainActivity.ROUTE_SNIPPETS) }
            }
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(items.filter { category == null || it.category == category }, key = { it.id }) { s ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.key)
                        .clickable { controller.insertSnippetFromPanel(s) }
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (s.sensitive) {
                            Icon(Icons.Outlined.Lock, "Protegido", tint = colors.accent, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(s.title, color = colors.text, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (s.shortcut.isNotEmpty()) Text("/${s.shortcut}", color = colors.textMuted, fontSize = 12.sp)
                    }
                    Text(
                        if (s.sensitive) "••••••••  (toque duas vezes para inserir)" else s.text.replace('\n', ' '),
                        color = colors.textMuted,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** Shows the AI result next to the decision buttons; nothing changes in the field before a tap. */
@Composable
fun ToolPreviewPanel(controller: KeyboardController) {
    val colors = LocalKeyboardColors.current
    val r = controller.toolResult
    if (r == null) {
        // Nothing to show (e.g. the field changed): go back to the keys outside composition.
        androidx.compose.runtime.LaunchedEffect(Unit) { controller.showPanel(Panel.KEYBOARD) }
        return
    }
    var showOriginal by remember(r) { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(horizontal = 10.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.AutoAwesome, null, tint = colors.accent, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(r.tool.label, color = colors.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (r.tool.mode == ToolMode.REWRITE && r.original.isNotBlank()) {
                PanelButton(if (showOriginal) "Proposta" else "Original") { showOriginal = !showOriginal }
            }
        }
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(vertical = 6.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(colors.surface)
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
        ) {
            if (r.tool == WritingTool.SYNONYMS && !showOriginal) {
                val options = r.output.lines().map { it.trim().removePrefix("-").trim() }.filter { it.isNotBlank() }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    options.forEach { line ->
                        val word = line.substringBefore(" (").trim()
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(colors.chip)
                                .clickable(enabled = r.replaceSelection) { controller.replaceWith(word) }.padding(horizontal = 10.dp, vertical = 7.dp),
                        ) {
                            Text(word, color = colors.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            val nuance = line.substringAfter(" (", "").removeSuffix(")")
                            if (nuance.isNotBlank()) Text("  $nuance", color = colors.textMuted, fontSize = 13.sp)
                        }
                    }
                }
            } else {
                Text(if (showOriginal) r.original else r.output, color = colors.text, fontSize = 15.sp, lineHeight = 21.sp)
            }
        }
        Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            when (r.tool.mode) {
                ToolMode.REWRITE -> {
                    ActionPill("Substituir", primary = true, modifier = Modifier.weight(1f)) { controller.applyToolResult() }
                    ActionPill("Inserir abaixo", modifier = Modifier.weight(1f)) { controller.insertToolResult() }
                }
                ToolMode.NEW_TEXT -> ActionPill("Inserir", primary = true, modifier = Modifier.weight(1f)) { controller.insertToolResult() }
                ToolMode.ANALYSIS -> Unit
            }
            ActionPill("Copiar", modifier = Modifier.weight(1f)) { controller.copyToolResult() }
            ActionPill("Descartar", modifier = Modifier.weight(1f)) { controller.closeToolResult() }
        }
    }
}

@Composable
private fun ActionPill(label: String, primary: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = LocalKeyboardColors.current
    Box(
        modifier
            .height(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (primary) colors.accent else colors.key)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (primary) colors.onAccent else colors.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}
