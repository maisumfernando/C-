package app.escriba.engine

import android.util.Log
import app.escriba.core.PolishStyle

data class PolishResult(
    val text: String,
    /** True when the language model produced the text; false when the rule-based fallback was used. */
    val usedModel: Boolean,
)

/**
 * Turns a raw transcript into polished text with the local LLM, guarding against the typical
 * failure modes of small models (answering instead of rewriting, truncating, rambling).
 */
class TextPolisher(private val engines: EngineManager) {

    suspend fun polish(
        raw: String,
        style: PolishStyle,
        vocabulary: Collection<String>,
        onPartial: (String) -> Unit,
    ): PolishResult {
        val source = raw.trim()
        if (source.isEmpty()) return PolishResult("", usedModel = false)
        if (style == PolishStyle.OFF) return PolishResult(TranscriptCleaner.basicPolish(source), usedModel = false)

        val engine = engines.text() ?: return PolishResult(TranscriptCleaner.basicPolish(source), usedModel = false)
        val vocab = vocabulary.filter { it.isNotBlank() }.take(MAX_VOCAB).joinToString(", ").ifBlank { null }

        val chunks = chunk(source)
        val done = StringBuilder()
        var usedModel = false
        for ((index, chunk) in chunks.withIndex()) {
            val separator = if (index == 0) "" else "\n\n"
            val streamed = StringBuilder()
            val inputTokens = engine.countTokens(chunk).takeIf { it > 0 } ?: (chunk.length / 3)
            val maxTokens = (inputTokens * 2 + 64).coerceAtMost(MAX_OUTPUT_TOKENS)
            val output = try {
                engine.generate(Prompts.conversation(style, chunk, vocab), maxTokens) { piece ->
                    streamed.append(piece)
                    onPartial(done.toString() + separator + TranscriptCleaner.sanitizeModelOutput(streamed.toString()))
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "generation failed", e)
                null
            }
            val cleaned = output?.let { TranscriptCleaner.sanitizeModelOutput(it) }
            val accepted = cleaned?.takeIf { isPlausibleRewrite(chunk, it) }
            if (accepted != null) usedModel = true
            done.append(separator).append(accepted ?: TranscriptCleaner.basicPolish(chunk))
            onPartial(done.toString())
        }
        return PolishResult(done.toString().trim(), usedModel)
    }

    /** Rejects outputs that are clearly not a faithful rewrite of [source]. */
    private fun isPlausibleRewrite(source: String, output: String): Boolean {
        if (output.isBlank()) return false
        val ratio = output.length.toDouble() / source.length
        if (source.length > 60 && ratio < 0.35) return false // summarised or truncated
        if (ratio > 2.2 && output.length > 80) return false   // answered or rambled
        if (TranscriptCleaner.looksDegenerate(output, 0.0)) return false
        return true
    }

    /** Splits long dictations at sentence boundaries so each request fits comfortably in context. */
    private fun chunk(text: String): List<String> {
        val words = text.split(Regex("\\s+"))
        if (words.size <= CHUNK_WORDS) return listOf(text)
        val sentences = text.split(Regex("(?<=[.!?…])\\s+"))
        val chunks = mutableListOf<String>()
        val current = StringBuilder()
        var count = 0
        for (s in sentences) {
            val n = s.split(Regex("\\s+")).size
            if (count > 0 && count + n > CHUNK_WORDS) {
                chunks += current.toString().trim()
                current.clear()
                count = 0
            }
            current.append(s).append(' ')
            count += n
        }
        if (current.isNotBlank()) chunks += current.toString().trim()
        return chunks
    }

    companion object {
        private const val TAG = "TextPolisher"
        private const val CHUNK_WORDS = 220
        private const val MAX_OUTPUT_TOKENS = 1400
        private const val MAX_VOCAB = 40
    }
}
