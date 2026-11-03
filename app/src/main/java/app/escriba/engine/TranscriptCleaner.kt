package app.escriba.engine

import java.text.Normalizer

/** Deterministic text clean-up used around the neural models. */
object TranscriptCleaner {

    /**
     * Phrases Whisper is known to "hear" in silence or noise (artefacts of subtitle training data).
     * Matched against whole sentences only.
     */
    private val hallucinations = listOf(
        "legendas pela comunidade amara.org",
        "legendas pela comunidade amara",
        "legenda pela comunidade amara.org",
        "subtitulos realizados por la comunidad de amara.org",
        "subtitulos por la comunidad de amara.org",
        "amara.org",
        "obrigado por assistir",
        "obrigada por assistir",
        "obrigado por assistirem",
        "obrigado pela atencao",
        "inscreva-se no canal",
        "inscreva-se",
        "ativem o sininho",
        "nao se esqueca de se inscrever no canal",
        "legendas por",
        "legenda adriana zanotto",
        "transcricao e legendas",
        "traducao e legendas",
        "thank you for watching",
        "thanks for watching",
        "please subscribe",
        "subtitles by the amara.org community",
        "gracias por ver el video",
    )

    /** Short words that are only noise when they make up the entire segment. */
    // Silence never reaches Whisper (VAD gates it), so this list stays deliberately tiny:
    // a user may genuinely dictate just "Obrigado!" or "Tchau.".
    private val loneNoise = setOf("musica", "you", "legendas")

    private val bracketNoise = Regex("""[\[(（](?:m[uú]sica|music|aplausos|applause|risos|laughs?|sil[eê]ncio|silence|ru[ií]do|noise|inaud[ií]vel|blank_audio|tosse|suspiro)[^\])）]*[\])）]""", RegexOption.IGNORE_CASE)
    private val musicNotes = Regex("[♪♫♬]+")
    private val spaces = Regex("[ \\t]+")
    private val spaceBeforePunct = Regex("\\s+([,.;:!?…])")

    fun normalizeKey(s: String): String = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .replace(Regex("[^a-z0-9.\\- ]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    /** Removes annotations and hallucinated sentences from a raw Whisper segment. */
    fun cleanSegment(text: String): String {
        val t = text.replace(bracketNoise, " ").replace(musicNotes, " ")
        val kept = splitSentences(t).filter { s ->
            val key = normalizeKey(s).trimEnd('.', ' ', '!')
            key.isNotEmpty() && hallucinations.none { h -> key == h || (h.length > 12 && key.contains(h)) }
        }
        val joined = tidy(kept.joinToString(" "))
        // Short words such as "Tchau." are only discarded when they are the whole segment,
        // which is what Whisper produces for a pause filled with noise.
        return if (normalizeKey(joined).trimEnd('.', ' ', '!') in loneNoise) "" else joined
    }

    fun tidy(text: String): String = text
        .replace(spaces, " ")
        .replace(spaceBeforePunct, "$1")
        .lines().joinToString("\n") { it.trim() }
        .trim()

    private fun splitSentences(text: String): List<String> =
        text.split(Regex("(?<=[.!?…])\\s+")).map { it.trim() }.filter { it.isNotEmpty() }

    /** Detects outputs typical of a failed decode: loops, absurd character rates, or nothing at all. */
    fun looksDegenerate(text: String, seconds: Double): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return seconds > 2.5
        if (seconds > 0 && t.length / seconds > 32) return true
        val words = t.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.size >= 8) {
            // Same word 4+ times in a row, or a short phrase repeated 3+ times consecutively.
            var run = 1
            for (i in 1 until words.size) {
                run = if (words[i] == words[i - 1]) run + 1 else 1
                if (run >= 4) return true
            }
            for (n in 2..5) {
                if (words.size < n * 3) break
                for (i in 0..words.size - n * 3) {
                    val a = words.subList(i, i + n)
                    if (a == words.subList(i + n, i + 2 * n) && a == words.subList(i + 2 * n, i + 3 * n)) return true
                }
            }
        }
        return false
    }

    // -------------------------------------------------------------------------------------
    // Rule-based polishing (used when no language model is available or as a safety net)
    // -------------------------------------------------------------------------------------

    private val fillerPatterns = listOf(
        Regex("(?i)(^|[\\s,])(?:é{2,}|ã+h+|ah+n+|hum+|hm+|uhm+|ehm+|eh+|uh+)(?=[\\s,.!?]|$)[,]?"),
        Regex("(?i),\\s*né\\s*(?=[,.!?]|$)"),
        Regex("(?i)\\s+né\\s*\\?"),
        Regex("(?i)(^|[.!?]\\s+)(?:tipo assim|tipo|então|aí|daí)\\s*,\\s*"),
    )
    private val repeatedWord = Regex("(?i)\\b(\\p{L}+)(\\s+\\1\\b)+")

    fun basicPolish(text: String): String {
        var t = tidy(text)
        for (p in fillerPatterns) t = t.replace(p) { m -> if (m.groupValues.size > 1) m.groupValues[1] else "" }
        t = t.replace(repeatedWord, "$1")
        t = t.replace(Regex("\\bpra\\b"), "para").replace(Regex("\\bPra\\b"), "Para")
        t = tidy(t).trim(',', ' ')
        t = capitalizeSentences(t)
        if (t.isNotEmpty() && t.last().isLetterOrDigit()) t += "."
        return t
    }

    fun capitalizeSentences(text: String): String {
        val sb = StringBuilder(text)
        var capitalizeNext = true
        for (i in sb.indices) {
            val c = sb[i]
            if (capitalizeNext && c.isLetter()) {
                sb.setCharAt(i, c.uppercaseChar())
                capitalizeNext = false
            } else if (c == '.' || c == '!' || c == '?' || c == '\n') {
                capitalizeNext = true
            } else if (!c.isWhitespace() && c != '"' && c != '\'' && c != '(' && c != '¿' && c != '¡') {
                capitalizeNext = false
            }
        }
        return sb.toString()
    }

    // -------------------------------------------------------------------------------------
    // LLM output sanitising
    // -------------------------------------------------------------------------------------

    private val thinkBlock = Regex("(?s)<think>.*?</think>")
    private val preambles = Regex(
        "(?i)^(?:texto revisado|texto corrigido|revis[ãa]o|vers[ãa]o revisada|aqui est[áa][^:\\n]*|segue[^:\\n]*|resposta|revised text|here is[^:\\n]*)\\s*:\\s*\\n?",
    )

    /** Strips reasoning blocks, echoed tags and preambles from a (possibly partial) model output. */
    fun sanitizeModelOutput(raw: String): String {
        var t = raw.replace(thinkBlock, "")
        val openThink = t.indexOf("<think>")
        if (openThink >= 0) t = t.substring(0, openThink)
        t = t.replace(Prompts.OPEN, "").replace(Prompts.CLOSE, "")
        t = t.trimStart()
        t = preambles.replaceFirst(t, "")
        t = t.trim()
        if (t.length >= 2 && ((t.first() == '"' && t.last() == '"') || (t.first() == '“' && t.last() == '”'))) {
            t = t.substring(1, t.length - 1).trim()
        }
        return t
    }
}
