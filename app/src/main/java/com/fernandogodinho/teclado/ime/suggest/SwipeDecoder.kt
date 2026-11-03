package com.fernandogodinho.teclado.ime.suggest

import java.text.Normalizer
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Swipe ("gesture") typing: turns the path drawn over the letters into words.
 *
 * Every candidate word has an ideal path through the centres of its keys. The drawn path and the
 * ideal path are resampled to the same number of points and compared twice: where they are
 * (location) and what they look like once moved and scaled (shape). The word's frequency breaks
 * ties. Candidates are limited to words whose first and last keys are close to where the finger
 * started and ended, so decoding takes a few milliseconds. Everything runs on the phone.
 */
class SwipeDecoder(entries: Sequence<Pair<String, Float>>) {

    class Entry(val word: String, val keys: String, val score: Float)

    private val index = HashMap<Int, MutableList<Entry>>()

    init {
        for ((word, score) in entries) add(word, score)
    }

    private fun add(word: String, score: Float) {
        val keys = keySequence(word) ?: return
        if (keys.length < 2) return
        index.getOrPut(pair(keys.first(), keys.last())) { mutableListOf() }.add(Entry(word, keys, score))
    }

    @Volatile private var extra: List<Entry> = emptyList()

    /** Words from the user's vocabulary, always considered (they get a strong prior). */
    fun setUserWords(words: Collection<String>, score: Float) {
        extra = words.mapNotNull { w -> keySequence(w)?.takeIf { it.length >= 2 }?.let { Entry(w, it, score) } }
    }

    /**
     * Decodes a path given in pixels. [centers] maps each letter key ('a'..'z', 'ç') to its centre;
     * [keyWidth] is the width of a letter key. Returns up to [limit] words, best first.
     */
    fun decode(xs: FloatArray, ys: FloatArray, centers: Map<Char, FloatArray>, keyWidth: Float, limit: Int = 4): List<String> {
        if (xs.size < 2 || centers.isEmpty() || keyWidth <= 0f) return emptyList()
        val user = resample(xs, ys)
        val userLength = length(xs, ys)
        if (userLength < keyWidth * 0.6f) return emptyList()
        val userShape = normalized(user, keyWidth)

        val starts = nearestKeys(xs.first(), ys.first(), centers, keyWidth)
        val ends = nearestKeys(xs.last(), ys.last(), centers, keyWidth)
        val scored = ArrayList<Pair<Entry, Float>>()
        val seen = HashSet<String>()
        val pools = ArrayList<List<Entry>>()
        for (s in starts) for (e in ends) index[pair(s, e)]?.let(pools::add)
        pools += extra.filter { it.keys.first() in starts && it.keys.last() in ends }

        val ideal = FloatArray(N * 2)
        for (pool in pools) {
            for (entry in pool) {
                if (!seen.add(entry.word)) continue
                if (!idealPath(entry.keys, centers, ideal)) continue
                val idealLength = polylineLength(entry.keys, centers)
                if (idealLength > keyWidth && (userLength < idealLength * 0.45f || userLength > idealLength * 2.6f + keyWidth * 1.5f)) continue
                if (!covers(entry.keys, centers, user, keyWidth * 1.35f)) continue
                val location = meanDistance(user, ideal) / keyWidth
                val shape = meanDistance(userShape, normalized(ideal, keyWidth))
                val cost = location + shape * 2.2f - entry.score * PRIOR_WEIGHT
                scored += entry to cost
            }
        }
        scored.sortBy { it.second }
        return scored.asSequence().map { it.first.word }.distinct().take(limit).toList()
    }

    private fun nearestKeys(x: Float, y: Float, centers: Map<Char, FloatArray>, keyWidth: Float): Set<Char> {
        val sorted = centers.entries.map { it.key to hypot(it.value[0] - x, it.value[1] - y) }.sortedBy { it.second }
        val near = sorted.filter { it.second <= keyWidth * 1.15f }.take(4).map { it.first }
        return (near.ifEmpty { sorted.take(1).map { it.first } }).toSet()
    }

    /** Every key of the word must be close to some point of the drawn path. */
    private fun covers(keys: String, centers: Map<Char, FloatArray>, path: FloatArray, radius: Float): Boolean {
        for (k in keys) {
            val c = centers[k] ?: return false
            var best = Float.MAX_VALUE
            var i = 0
            while (i < path.size) {
                val d = hypot(path[i] - c[0], path[i + 1] - c[1])
                if (d < best) best = d
                i += 2
            }
            if (best > radius) return false
        }
        return true
    }

    private fun idealPath(keys: String, centers: Map<Char, FloatArray>, out: FloatArray): Boolean {
        val xs = FloatArray(keys.length)
        val ys = FloatArray(keys.length)
        for (i in keys.indices) {
            val c = centers[keys[i]] ?: return false
            xs[i] = c[0]
            ys[i] = c[1]
        }
        resample(xs, ys, out)
        return true
    }

    private fun polylineLength(keys: String, centers: Map<Char, FloatArray>): Float {
        var total = 0f
        for (i in 1 until keys.length) {
            val a = centers[keys[i - 1]] ?: return 0f
            val b = centers[keys[i]] ?: return 0f
            total += hypot(a[0] - b[0], a[1] - b[1])
        }
        return total
    }

    companion object {
        /** Points each path is resampled to. */
        const val N = 48
        private const val PRIOR_WEIGHT = 0.09f

        private fun pair(a: Char, b: Char) = a.code * 65_536 + b.code

        /** Keys a word goes through: accents removed (but ç kept), repeated letters collapsed. */
        fun keySequence(word: String): String? {
            val sb = StringBuilder()
            for (ch in word.lowercase()) {
                val k = when {
                    ch == 'ç' -> 'ç'
                    ch in 'a'..'z' -> ch
                    ch == '-' || ch == '\'' -> continue
                    else -> Normalizer.normalize(ch.toString(), Normalizer.Form.NFD).firstOrNull()?.takeIf { it in 'a'..'z' } ?: return null
                }
                if (sb.isEmpty() || sb.last() != k) sb.append(k)
            }
            return sb.toString().ifEmpty { null }
        }

        fun length(xs: FloatArray, ys: FloatArray): Float {
            var total = 0f
            for (i in 1 until xs.size) total += hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1])
            return total
        }

        /** Resamples a polyline to [N] evenly spaced points, as [x0, y0, x1, y1, …]. */
        fun resample(xs: FloatArray, ys: FloatArray, out: FloatArray = FloatArray(N * 2)): FloatArray {
            val total = length(xs, ys)
            if (xs.size == 1 || total == 0f) {
                for (i in 0 until N) { out[i * 2] = xs[0]; out[i * 2 + 1] = ys[0] }
                return out
            }
            val step = total / (N - 1)
            var seg = 1
            var segStart = 0f
            var segLen = hypot(xs[1] - xs[0], ys[1] - ys[0])
            for (i in 0 until N) {
                val target = step * i
                while (seg < xs.size - 1 && segStart + segLen < target) {
                    segStart += segLen
                    seg++
                    segLen = hypot(xs[seg] - xs[seg - 1], ys[seg] - ys[seg - 1])
                }
                val t = if (segLen == 0f) 0f else ((target - segStart) / segLen).coerceIn(0f, 1f)
                out[i * 2] = xs[seg - 1] + (xs[seg] - xs[seg - 1]) * t
                out[i * 2 + 1] = ys[seg - 1] + (ys[seg] - ys[seg - 1]) * t
            }
            return out
        }

        /** Moves the path to its centroid and scales it to unit size (the "shape"). */
        fun normalized(p: FloatArray, keyWidth: Float): FloatArray {
            var cx = 0f
            var cy = 0f
            var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
            for (i in 0 until N) {
                val x = p[i * 2]; val y = p[i * 2 + 1]
                cx += x; cy += y
                if (x < minX) minX = x; if (x > maxX) maxX = x
                if (y < minY) minY = y; if (y > maxY) maxY = y
            }
            cx /= N; cy /= N
            val scale = max(max(maxX - minX, maxY - minY), keyWidth * 0.5f)
            return FloatArray(N * 2) { i -> if (i % 2 == 0) (p[i] - cx) / scale else (p[i] - cy) / scale }
        }

        fun meanDistance(a: FloatArray, b: FloatArray): Float {
            var sum = 0f
            for (i in 0 until N) {
                val dx = a[i * 2] - b[i * 2]
                val dy = a[i * 2 + 1] - b[i * 2 + 1]
                sum += sqrt(dx * dx + dy * dy)
            }
            return sum / N
        }
    }
}
