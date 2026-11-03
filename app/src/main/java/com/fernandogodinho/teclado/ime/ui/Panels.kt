package com.fernandogodinho.teclado.ime.ui

import android.content.Context
import android.graphics.Paint
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.EmojiFlags
import androidx.compose.material.icons.outlined.EmojiFoodBeverage
import androidx.compose.material.icons.outlined.EmojiNature
import androidx.compose.material.icons.outlined.EmojiObjects
import androidx.compose.material.icons.outlined.EmojiPeople
import androidx.compose.material.icons.outlined.EmojiSymbols
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.SportsSoccer
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fernandogodinho.teclado.ime.KeyboardController
import com.fernandogodinho.teclado.ime.Panel
import com.fernandogodinho.teclado.ime.layout.KeyAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class EmojiCategory(val id: String, val icon: ImageVector, val emojis: List<String>)

private object EmojiData {
    @Volatile
    var categories: List<Pair<String, List<String>>>? = null

    fun load(context: Context): List<Pair<String, List<String>>> {
        categories?.let { return it }
        val paint = Paint()
        val result = context.assets.open("emoji.txt").bufferedReader().readLines()
            .filter { !it.startsWith("#") && it.contains('\t') }
            .map { line ->
                val (id, list) = line.split('\t', limit = 2)
                // Only show emoji the system font can actually draw.
                id to list.split(' ').filter { it.isNotEmpty() && paint.hasGlyph(it) }
            }
        categories = result
        return result
    }
}

private object RecentEmoji {
    fun get(context: Context): List<String> =
        context.getSharedPreferences("emoji", Context.MODE_PRIVATE).getString("recent", "")!!
            .split(' ').filter { it.isNotEmpty() }

    fun add(context: Context, emoji: String) {
        val list = (listOf(emoji) + get(context).filter { it != emoji }).take(32)
        context.getSharedPreferences("emoji", Context.MODE_PRIVATE).edit().putString("recent", list.joinToString(" ")).apply()
    }
}

private val categoryIcons = mapOf(
    "recent" to Icons.Outlined.AccessTime,
    "smileys" to Icons.Outlined.EmojiEmotions,
    "people" to Icons.Outlined.EmojiPeople,
    "nature" to Icons.Outlined.EmojiNature,
    "food" to Icons.Outlined.EmojiFoodBeverage,
    "travel" to Icons.Outlined.DirectionsCar,
    "activities" to Icons.Outlined.SportsSoccer,
    "objects" to Icons.Outlined.EmojiObjects,
    "symbols" to Icons.Outlined.EmojiSymbols,
    "flags" to Icons.Outlined.EmojiFlags,
)

@Composable
fun EmojiPanel(controller: KeyboardController, modifier: Modifier = Modifier) {
    val colors = LocalKeyboardColors.current
    val context = LocalContext.current
    var categories by remember { mutableStateOf<List<EmojiCategory>>(emptyList()) }
    var selected by remember { mutableIntStateOf(1) }
    var recents by remember { mutableStateOf(RecentEmoji.get(context)) }

    LaunchedEffect(Unit) {
        val data = withContext(Dispatchers.Default) { EmojiData.load(context) }
        categories = data.map { (id, list) -> EmojiCategory(id, categoryIcons[id] ?: Icons.Outlined.EmojiEmotions, list) }
        if (recents.isNotEmpty()) selected = 0
    }

    val all = listOf(EmojiCategory("recent", Icons.Outlined.AccessTime, recents)) + categories
    val current = all.getOrNull(selected)?.emojis.orEmpty()
    val gridState = rememberLazyGridState()
    LaunchedEffect(selected) { gridState.scrollToItem(0) }

    Column(modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(44.dp),
            state = gridState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
        ) {
            items(current, key = { it }) { emoji ->
                Box(
                    Modifier
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable {
                            controller.commitText(emoji)
                            if (!controller.privateField) {
                                RecentEmoji.add(context, emoji)
                                recents = RecentEmoji.get(context)
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(emoji, fontSize = 27.sp)
                }
            }
            if (current.isEmpty()) {
                item {
                    Text("Seus emojis recentes aparecem aqui", color = colors.textMuted, fontSize = 13.sp, modifier = Modifier.padding(12.dp))
                }
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .height(46.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PanelButton(label = "ABC", onClick = { controller.showPanel(Panel.KEYBOARD) })
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceEvenly) {
                all.forEachIndexed { i, cat ->
                    val active = i == selected
                    Box(
                        Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(if (active) colors.chip else colors.background)
                            .clickable { selected = i },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(cat.icon, cat.id, tint = if (active) colors.text else colors.textMuted, modifier = Modifier.size(19.dp))
                    }
                }
            }
            Box(
                Modifier
                    .size(44.dp, 36.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .clickable { controller.onAction(KeyAction.Backspace) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.AutoMirrored.Outlined.Backspace, "Apagar", tint = colors.text, modifier = Modifier.size(21.dp))
            }
        }
    }
}

@Composable
fun PanelButton(label: String, onClick: () -> Unit) {
    val colors = LocalKeyboardColors.current
    Box(
        Modifier
            .size(52.dp, 36.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(colors.functionKey)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = colors.text, fontSize = 14.sp)
    }
}

@Composable
fun ClipboardPanel(controller: KeyboardController, modifier: Modifier = Modifier) {
    val colors = LocalKeyboardColors.current
    val items = controller.clipboard.items
    Column(modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.ContentPaste, null, tint = colors.textMuted, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Área de transferência", color = colors.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
            if (items.any { !it.pinned }) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { controller.clipboard.clear() }
                        .padding(8.dp),
                ) {
                    Icon(Icons.Outlined.DeleteSweep, "Limpar", tint = colors.textMuted, modifier = Modifier.size(20.dp))
                }
            }
            PanelButton("ABC") { controller.showPanel(Panel.KEYBOARD) }
        }
        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    "Os textos que você copiar aparecerão aqui.\nNada sai do seu aparelho.",
                    color = colors.textMuted,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                )
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(items, key = { it.time.toString() + it.text.hashCode() }) { item ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(colors.key)
                            .clickable { controller.commitText(item.text) }
                            .padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            item.text,
                            color = colors.text,
                            fontSize = 14.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Box(
                            Modifier
                                .clip(CircleShape)
                                .clickable { controller.clipboard.togglePin(item) }
                                .padding(8.dp),
                        ) {
                            Icon(
                                if (item.pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                                "Fixar",
                                tint = if (item.pinned) colors.accent else colors.textMuted,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
