package com.fernandogodinho.teclado.ime.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.FormatListBulleted
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BusinessCenter
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.ShortText
import androidx.compose.material.icons.outlined.SentimentSatisfied
import androidx.compose.material.icons.outlined.Spellcheck
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.Abc
import androidx.compose.material.icons.outlined.Rule
import androidx.compose.material.icons.outlined.Help
import androidx.compose.material.icons.outlined.QuestionAnswer
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.outlined.Summarize
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.material.icons.outlined.SupportAgent
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fernandogodinho.teclado.engine.WritingTool
import com.fernandogodinho.teclado.ime.KeyboardController
import com.fernandogodinho.teclado.ime.Panel

private val toolIcons: Map<WritingTool, ImageVector> = mapOf(
    WritingTool.REVISE to Icons.Outlined.AutoAwesome,
    WritingTool.FIX to Icons.Outlined.Spellcheck,
    WritingTool.CLEARER to Icons.Outlined.Lightbulb,
    WritingTool.SIMPLER to Icons.Outlined.ShortText,
    WritingTool.FORMAL to Icons.Outlined.BusinessCenter,
    WritingTool.FRIENDLY to Icons.Outlined.SentimentSatisfied,
    WritingTool.CASUAL to Icons.Outlined.EmojiEmotions,
    WritingTool.ASSERTIVE to Icons.Outlined.RecordVoiceOver,
    WritingTool.PROFESSIONAL to Icons.Outlined.SupportAgent,
    WritingTool.SHORTER to Icons.Outlined.ShortText,
    WritingTool.EXPAND to Icons.Outlined.OpenInFull,
    WritingTool.BULLETS to Icons.AutoMirrored.Outlined.FormatListBulleted,
    WritingTool.EMAIL to Icons.Outlined.Email,
    WritingTool.SUMMARY to Icons.Outlined.Summarize,
    WritingTool.REPLY to Icons.AutoMirrored.Outlined.Reply,
    WritingTool.PENDING to Icons.Outlined.QuestionAnswer,
    WritingTool.AMBIGUITY to Icons.Outlined.Help,
    WritingTool.COHERENCE to Icons.Outlined.Rule,
    WritingTool.SYNONYMS to Icons.Outlined.Abc,
    WritingTool.TO_ENGLISH to Icons.Outlined.Translate,
    WritingTool.TO_SPANISH to Icons.Outlined.Translate,
    WritingTool.TO_PORTUGUESE to Icons.Outlined.Translate,
)

/** Tools grouped the way people think about them. */
private val toolGroups: List<Pair<String, List<WritingTool>>> = listOf(
    "Corrigir e reescrever" to listOf(WritingTool.REVISE, WritingTool.FIX, WritingTool.CLEARER, WritingTool.SIMPLER, WritingTool.SHORTER, WritingTool.EXPAND, WritingTool.BULLETS, WritingTool.EMAIL),
    "Tom" to listOf(WritingTool.FORMAL, WritingTool.FRIENDLY, WritingTool.CASUAL, WritingTool.ASSERTIVE, WritingTool.PROFESSIONAL),
    "Responder (use a mensagem copiada)" to listOf(WritingTool.REPLY, WritingTool.PENDING),
    "Analisar" to listOf(WritingTool.SUMMARY, WritingTool.AMBIGUITY, WritingTool.COHERENCE, WritingTool.SYNONYMS),
    "Traduzir" to listOf(WritingTool.TO_ENGLISH, WritingTool.TO_SPANISH, WritingTool.TO_PORTUGUESE),
)

/** "Ferramentas de IA": rewrite, fix, change tone, summarise or translate — all on the phone. */
@Composable
fun WritingToolsPanel(controller: KeyboardController, modifier: Modifier = Modifier) {
    val colors = LocalKeyboardColors.current
    val target = remember { controller.toolTarget() }
    Column(modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 6.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Ferramentas de IA", color = colors.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text("$target · processado no aparelho", color = colors.textMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            PanelButton("ABC") { controller.showPanel(Panel.KEYBOARD) }
        }
        Spacer(Modifier.height(6.dp))
        // Voice instruction: the most flexible tool, so it gets the most prominent spot.
        Row(
            Modifier
                .padding(horizontal = 10.dp)
                .fillMaxWidth()
                .height(44.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(colors.accent)
                .clickable { controller.startVoiceInstruction() }
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Mic, null, tint = colors.onAccent, modifier = Modifier.size(19.dp))
            Spacer(Modifier.width(10.dp))
            Text("Diga o que fazer com o texto", color = colors.onAccent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            toolGroups.forEach { (title, tools) ->
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(title.uppercase(), color = colors.textMuted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp, modifier = Modifier.padding(start = 4.dp, top = 6.dp))
                }
                toolItems(tools, colors, controller)
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.grid.LazyGridScope.toolItems(tools: List<WritingTool>, colors: KeyboardColors, controller: KeyboardController) {
            items(tools) { tool ->
                Row(
                    Modifier
                        .height(42.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.key)
                        .clickable { controller.runTool(tool) }
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                        Icon(toolIcons[tool] ?: Icons.Outlined.AutoAwesome, null, tint = colors.accent, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(tool.label, color = colors.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
}
