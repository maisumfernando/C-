package com.fernandogodinho.teclado.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.Normalizer
import java.util.UUID

/**
 * A saved reply or text shortcut, created by the user. Typing "/" + [shortcut] in any field
 * offers to insert [text]. [sensitive] items (address, documents…) are masked and need a second
 * tap to insert.
 */
data class Snippet(
    val id: String = UUID.randomUUID().toString(),
    val shortcut: String,
    val title: String,
    val text: String,
    val category: String = "",
    val sensitive: Boolean = false,
)

/** Saved replies, kept only in the app's private storage (never backed up or synced). */
class SnippetStore(context: Context) {
    private val file = File(context.filesDir, "snippets.json")
    private val _items = MutableStateFlow(load())
    val items: StateFlow<List<Snippet>> = _items.asStateFlow()

    private fun load(): List<Snippet> = runCatching { if (file.isFile) fromJson(file.readText()) else emptyList() }.getOrDefault(emptyList())

    @Synchronized
    fun upsert(s: Snippet) {
        val clean = s.copy(shortcut = normalizeShortcut(s.shortcut), title = s.title.trim().take(80), text = s.text.take(MAX_TEXT), category = s.category.trim().take(40))
        val list = _items.value.filterNot { it.id == clean.id } + clean
        save(list.sortedWith(compareBy({ it.category.lowercase() }, { it.title.lowercase() })))
    }

    @Synchronized
    fun delete(id: String) = save(_items.value.filterNot { it.id == id })

    @Synchronized
    fun deleteAll() = save(emptyList())

    private fun save(list: List<Snippet>) {
        _items.value = list
        val tmp = File(file.parentFile, "snippets.json.tmp")
        tmp.writeText(toJson(list))
        tmp.renameTo(file)
    }

    /** Shortcut matches for what was typed after "/" (prefix, accent-insensitive). */
    fun matching(typed: String): List<Snippet> {
        val key = normalizeShortcut(typed)
        if (key.isEmpty()) return emptyList()
        return _items.value.filter { it.shortcut.isNotEmpty() && it.shortcut.startsWith(key) }
            .sortedBy { it.shortcut.length }
            .take(3)
    }

    /** Accent- and case-insensitive search in titles, shortcuts, categories and text. */
    fun search(query: String): List<Snippet> {
        val q = fold(query)
        if (q.isBlank()) return _items.value
        val terms = q.split(Regex("\\s+")).filter { it.isNotBlank() }
        return _items.value.filter { s ->
            val hay = fold("${s.title} ${s.shortcut} ${s.category} ${if (s.sensitive) "" else s.text}")
            terms.all { hay.contains(it) }
        }
    }

    companion object {
        const val MAX_TEXT = 4000

        fun normalizeShortcut(s: String) = fold(s).filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(24)

        fun fold(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

        fun toJson(list: List<Snippet>): String = JSONArray().apply {
            list.forEach {
                put(
                    JSONObject().put("id", it.id).put("shortcut", it.shortcut).put("title", it.title).put("text", it.text)
                        .put("category", it.category).put("sensitive", it.sensitive),
                )
            }
        }.toString(2)

        fun fromJson(json: String): List<Snippet> {
            val a = JSONArray(json)
            return (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                Snippet(
                    id = o.optString("id").ifEmpty { UUID.randomUUID().toString() },
                    shortcut = normalizeShortcut(o.optString("shortcut")),
                    title = o.optString("title"),
                    text = o.optString("text").take(MAX_TEXT),
                    category = o.optString("category"),
                    sensitive = o.optBoolean("sensitive"),
                )
            }
        }
    }
}
