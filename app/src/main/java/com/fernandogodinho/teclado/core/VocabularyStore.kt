package com.fernandogodinho.teclado.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** Words the keyboard learned from the user's typing (kept only on this device). */
class VocabularyStore(context: Context) {
    private val file = File(context.filesDir, "learned_words.txt")
    private val _words = MutableStateFlow(load())
    val words: StateFlow<Set<String>> = _words.asStateFlow()

    private fun load(): Set<String> = runCatching {
        if (file.isFile) file.readLines().map { it.trim() }.filter { it.isNotEmpty() }.toSet() else emptySet()
    }.getOrDefault(emptySet())

    @Synchronized
    fun add(word: String) {
        val w = word.trim()
        if (w.length < 2 || w.length > 40 || w in _words.value) return
        _words.value = _words.value + w
        persist()
    }

    @Synchronized
    fun remove(word: String) {
        _words.value = _words.value - word
        persist()
    }

    @Synchronized
    fun clear() {
        _words.value = emptySet()
        persist()
    }

    private fun persist() {
        runCatching { file.writeText(_words.value.sorted().joinToString("\n")) }
    }
}
