package com.fernandogodinho.teclado.ime.suggest

import android.content.Context
import java.text.Normalizer
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * In-memory word list with accent-insensitive lookup, prefix completion and typo correction.
 * Portuguese words dominate; English words are included with a lower weight because
 * Brazilians routinely mix English terms into their writing.
 */
class Dictionary private constructor(
    private val words: Array<String>,
    private val keys: Array<String>,
    private val scores: FloatArray,
) {
    private val sortedByKey: IntArray = words.indices.sortedWith(compareBy({ keys[it] }, { -scores[it] })).toIntArray()
    private val exact = HashMap<String, Int>(words.size * 2).apply {
        for (i in words.indices) if (!containsKey(words[i])) put(words[i], i)
    }
    private val byKey = HashMap<String, MutableList<Int>>(words.size * 2).apply {
        for (i in words.indices) getOrPut(this@Dictionary.keys[i]) { mutableListOf() }.add(i)
    }
    /** Unaccented spellings such as "nao" that only exist as common misspellings of "não". */
    private val misspelling = BooleanArray(words.size).also { flags ->
        for (group in byKey.values) {
            if (group.size < 2) continue
            val best = group.maxOf { scores[it] }
            for (i in group) if (scores[i] + ACCENT_MARGIN <= best) flags[i] = true
        }
    }

    private val byLength: Array<IntArray> = run {
        val maxLen = keys.maxOfOrNull { it.length } ?: 0
        val buckets = Array(maxLen + 1) { mutableListOf<Int>() }
        for (i in keys.indices) buckets[keys[i].length].add(i)
        Array(maxLen + 1) { buckets[it].toIntArray() }
    }

    private val extra = HashMap<String, Float>() // user words: lowercase word -> score

    val size: Int get() = words.size

    fun addUserWords(list: Collection<String>) {
        synchronized(extra) {
            extra.clear()
            for (w in list) {
                val clean = w.trim()
                if (clean.isNotEmpty() && !clean.contains(' ')) extra[clean.lowercase()] = USER_SCORE
            }
        }
        userOriginalCase = list.associateBy { it.trim().lowercase() }
    }

    @Volatile
    private var userOriginalCase: Map<String, String> = emptyMap()

    fun isWord(word: String): Boolean {
        val lower = word.lowercase()
        return exact.containsKey(lower) || synchronized(extra) { extra.containsKey(lower) }
    }

    fun score(word: String): Float {
        val lower = word.lowercase()
        exact[lower]?.let { return scores[it] }
        return synchronized(extra) { extra[lower] } ?: 0f
    }

    data class Candidate(val word: String, val score: Float, val distance: Float, val kind: Kind) {
        enum class Kind { ACCENT, COMPLETION, CORRECTION, USER }
    }

    /** Returns ranked candidates for the word being typed. */
    fun candidates(typed: String, limit: Int = 3): List<Candidate> {
        val lower = typed.lowercase()
        val key = normalize(lower)
        if (key.isEmpty()) return emptyList()
        val out = LinkedHashMap<String, Candidate>()

        // 1. Same letters, different accents ("voce" -> "você").
        byKey[key]?.forEach { i ->
            if (words[i] != lower && !misspelling[i]) {
                out.putIfAbsent(words[i], Candidate(words[i], scores[i] + 2f, 0f, Candidate.Kind.ACCENT))
            }
        }
        // 2. User vocabulary.
        synchronized(extra) {
            for ((w, s) in extra) {
                val k = normalize(w)
                if (k.startsWith(key) && w != lower) out.putIfAbsent(w, Candidate(w, s, 0f, Candidate.Kind.USER))
            }
        }
        // 3. Completions.
        var lo = lowerBound(key)
        var scanned = 0
        val completions = ArrayList<Candidate>()
        while (lo < sortedByKey.size && scanned < 4000) {
            val i = sortedByKey[lo]
            if (!keys[i].startsWith(key)) break
            if (words[i] != lower && keys[i].length > key.length && !misspelling[i]) {
                // Prefer shorter completions slightly: they are more likely to be the intended word.
                val lengthPenalty = 0.15f * (keys[i].length - key.length)
                completions += Candidate(words[i], scores[i] - lengthPenalty, 0f, Candidate.Kind.COMPLETION)
            }
            lo++; scanned++
        }
        completions.sortByDescending { it.score }
        completions.take(limit * 2).forEach { out.putIfAbsent(it.word, it) }

        // 4. Corrections for likely typos.
        if (key.length >= 2) {
            corrections(key, lower).take(limit * 2).forEach { out.putIfAbsent(it.word, it) }
        }

        return out.values
            .sortedByDescending { rank(it, key.length) }
            .take(limit)
            .map { it.copy(word = userOriginalCase[it.word] ?: it.word) }
    }

    /** Best automatic correction for a finished word, or null when the word should be left alone. */
    fun autoCorrection(typed: String): String? {
        val lower = typed.lowercase()
        if (lower.length < 2 && lower != "e" && lower != "a") return null
        val key = normalize(lower)
        if (synchronized(extra) { extra.containsKey(lower) }) return null
        // Missing accents ("nao" -> "não"): the accented spelling must be far more common
        // than what was typed, so that valid pairs like "esta"/"está" are left alone.
        val typedScore = exact[lower]?.let { scores[it] } ?: Float.NEGATIVE_INFINITY
        val variant = byKey[key].orEmpty().filter { words[it] != lower }.maxByOrNull { scores[it] }
        if (variant != null && scores[variant] >= typedScore + ACCENT_MARGIN) return words[variant]
        if (isWord(lower)) return null
        if (key.length < 3) return null
        val best = corrections(key, lower).firstOrNull() ?: return null
        val threshold = if (key.length <= 4) 0.7f else 1.0f
        return if (best.distance <= threshold && best.score >= MIN_CORRECTION_SCORE) best.word else null
    }

    private fun rank(c: Candidate, typedLength: Int): Float = when (c.kind) {
        Candidate.Kind.ACCENT -> c.score + 6f
        Candidate.Kind.USER -> c.score + 3f
        Candidate.Kind.COMPLETION -> c.score + if (typedLength >= 3) 1f else 0f
        Candidate.Kind.CORRECTION -> c.score - c.distance * 4f
    }

    private fun corrections(key: String, lower: String): List<Candidate> {
        val maxDist = if (key.length >= 6) 2f else 1f
        val result = ArrayList<Candidate>()
        val from = max(1, key.length - maxDist.toInt())
        val to = min(byLength.size - 1, key.length + maxDist.toInt())
        for (len in from..to) {
            for (i in byLength[len]) {
                val k = keys[i]
                // Typos rarely affect the first letter; requiring it keeps this fast and precise.
                if (k[0] != key[0] && !Keyboard.adjacent(k[0], key[0])) continue
                val d = Keyboard.weightedDistance(key, k, maxDist)
                if (d <= maxDist && words[i] != lower && !misspelling[i]) {
                    result += Candidate(words[i], scores[i], d, Candidate.Kind.CORRECTION)
                }
            }
        }
        result.sortByDescending { it.score - it.distance * 4f }
        return result
    }

    private fun lowerBound(key: String): Int {
        var lo = 0
        var hi = sortedByKey.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (keys[sortedByKey[mid]] < key) lo = mid + 1 else hi = mid
        }
        return lo
    }

    companion object {
        private const val USER_SCORE = 14f
        private const val MIN_CORRECTION_SCORE = 6f
        private const val ACCENT_MARGIN = 3f // ~20x more frequent
        private const val ENGLISH_WEIGHT = 0.55f

        private val combining = Regex("\\p{Mn}+")

        fun normalize(s: String): String =
            Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(combining, "")

        fun load(context: Context): Dictionary = build(
            context.assets.open("dict/pt_br.txt").bufferedReader().readLines(),
            context.assets.open("dict/en.txt").bufferedReader().readLines(),
        )

        /** Builds a dictionary from "word<TAB>count" lines (Portuguese first, then English). */
        fun build(portuguese: List<String>, english: List<String>): Dictionary {
            val words = ArrayList<String>(80_000)
            val scores = ArrayList<Float>(80_000)
            val seen = HashSet<String>(160_000)
            fun read(lines: List<String>, weight: Float) {
                for (line in lines) {
                    if (line.startsWith("#")) continue
                    val tab = line.indexOf('\t')
                    if (tab <= 0) continue
                    val w = line.substring(0, tab)
                    if (!seen.add(w)) continue
                    val count = line.substring(tab + 1).trim().toLongOrNull() ?: continue
                    words += w
                    scores += ln(count.toDouble() + 1).toFloat() * weight
                }
            }
            read(portuguese, 1f)
            read(english, ENGLISH_WEIGHT)
            val arr = words.toTypedArray()
            return Dictionary(arr, Array(arr.size) { normalize(arr[it]) }, scores.toFloatArray())
        }
    }
}

/** QWERTY geometry used to make typo correction aware of neighbouring keys. */
object Keyboard {
    private val rows = listOf("qwertyuiop", "asdfghjklç", "zxcvbnm")
    private val pos = HashMap<Char, Pair<Int, Float>>().apply {
        rows.forEachIndexed { r, row -> row.forEachIndexed { c, ch -> put(ch, r to (c + r * 0.5f)) } }
    }

    fun adjacent(a: Char, b: Char): Boolean {
        val pa = pos[a] ?: return false
        val pb = pos[b] ?: return false
        return kotlin.math.abs(pa.first - pb.first) <= 1 && kotlin.math.abs(pa.second - pb.second) <= 1.01f
    }

    /** Damerau-Levenshtein distance where hitting a neighbouring key costs less. */
    fun weightedDistance(a: String, b: String, cutoff: Float): Float {
        val n = a.length
        val m = b.length
        if (kotlin.math.abs(n - m) > cutoff) return Float.MAX_VALUE
        var prev2 = FloatArray(m + 1)
        var prev = FloatArray(m + 1) { it.toFloat() }
        var cur = FloatArray(m + 1)
        for (i in 1..n) {
            cur[0] = i.toFloat()
            var rowMin = cur[0]
            for (j in 1..m) {
                val ca = a[i - 1]
                val cb = b[j - 1]
                val sub = when {
                    ca == cb -> 0f
                    adjacent(ca, cb) -> 0.6f
                    else -> 1f
                }
                var v = minOf(prev[j] + 1f, cur[j - 1] + 1f, prev[j - 1] + sub)
                if (i > 1 && j > 1 && ca == b[j - 2] && a[i - 2] == cb) {
                    v = min(v, prev2[j - 2] + 0.7f)
                }
                cur[j] = v
                if (v < rowMin) rowMin = v
            }
            if (rowMin > cutoff) return Float.MAX_VALUE
            val t = prev2
            prev2 = prev
            prev = cur
            cur = t
        }
        return prev[m]
    }
}
