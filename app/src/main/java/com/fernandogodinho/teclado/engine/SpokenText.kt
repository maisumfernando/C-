package com.fernandogodinho.teclado.engine

import java.text.Normalizer

/**
 * Converts dictated speech into exact characters for fields where prose makes no sense:
 * verification codes, passwords, e-mail addresses and links. Pure functions, unit-tested.
 */
object SpokenText {

    private val digitWords = mapOf(
        // Portuguese ("meia" is how Brazilians say six when reading numbers aloud)
        "zero" to '0', "um" to '1', "uma" to '1', "hum" to '1', "dois" to '2', "duas" to '2', "tres" to '3',
        "quatro" to '4', "cinco" to '5', "seis" to '6', "meia" to '6', "sete" to '7', "oito" to '8', "nove" to '9',
        // English
        "oh" to '0', "one" to '1', "two" to '2', "three" to '3', "four" to '4', "five" to '5', "six" to '6',
        "seven" to '7', "eight" to '8', "nine" to '9',
        // Spanish
        "uno" to '1', "dos" to '2', "cuatro" to '4', "siete" to '7', "ocho" to '8', "nueve" to '9',
    )

    private val letterNames = mapOf(
        "a" to 'a', "be" to 'b', "bê" to 'b', "ce" to 'c', "cê" to 'c', "de" to 'd', "dê" to 'd', "e" to 'e', "é" to 'e',
        "efe" to 'f', "ge" to 'g', "gê" to 'g', "aga" to 'h', "agá" to 'h', "i" to 'i', "jota" to 'j', "ka" to 'k',
        "ká" to 'k', "ca" to 'k', "cá" to 'k', "capa" to 'k', "ele" to 'l', "eme" to 'm', "ene" to 'n', "o" to 'o',
        "pe" to 'p', "pê" to 'p', "que" to 'q', "quê" to 'q', "erre" to 'r', "esse" to 's', "te" to 't', "tê" to 't',
        "u" to 'u', "ve" to 'v', "vê" to 'v', "dablio" to 'w', "dáblio" to 'w', "dabliu" to 'w', "xis" to 'x',
        "ipsilon" to 'y', "ípsilon" to 'y', "ze" to 'z', "zê" to 'z',
    )

    /** Multi-word symbol names are matched before single words. */
    private val symbols: List<Pair<List<String>, String>> = listOf(
        listOf("ponto", "de", "exclamacao") to "!",
        listOf("ponto", "de", "interrogacao") to "?",
        listOf("ponto", "e", "virgula") to ";",
        listOf("jogo", "da", "velha") to "#",
        listOf("e", "comercial") to "&",
        listOf("por", "cento") to "%",
        listOf("dois", "pontos") to ":",
        listOf("barra", "invertida") to "\\",
        listOf("abre", "parenteses") to "(",
        listOf("fecha", "parenteses") to ")",
        listOf("ponto", "com") to ".com",
        listOf("ponto", "br") to ".br",
        listOf("arroba") to "@",
        listOf("cerquilha") to "#",
        listOf("hashtag") to "#",
        listOf("sustenido") to "#",
        listOf("cifrao") to "$",
        listOf("porcento") to "%",
        listOf("asterisco") to "*",
        listOf("ponto") to ".",
        listOf("virgula") to ",",
        listOf("hifen") to "-",
        listOf("traco") to "-",
        listOf("menos") to "-",
        listOf("underline") to "_",
        listOf("underscore") to "_",
        listOf("sublinhado") to "_",
        listOf("exclamacao") to "!",
        listOf("interrogacao") to "?",
        listOf("barra") to "/",
        listOf("contrabarra") to "\\",
        listOf("mais") to "+",
        listOf("igual") to "=",
        listOf("til") to "~",
        listOf("circunflexo") to "^",
        listOf("aspas") to "\"",
        listOf("apostrofo") to "'",
        listOf("espaco") to " ",
    )

    private val upperWords = setOf("maiusculo", "maiuscula", "maiusculas", "maiusculos", "capital", "uppercase")
    private val lowerWords = setOf("minusculo", "minuscula", "minusculas", "minusculos", "lowercase")
    private val fillerWords = setOf("letra", "numero", "simbolo", "depois", "entao", "tudo", "junto")

    private fun fold(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

    /**
     * Verification codes and numbers: "quatro oito dois, nove um três" -> "482913".
     * Letters are kept only when [allowLetters] (alphanumeric codes such as "G-58A2").
     */
    fun code(spoken: String, allowLetters: Boolean): String {
        val out = StringBuilder()
        for (raw in spoken.split(Regex("[\\s,;.\\-–—]+"))) {
            if (raw.isBlank()) continue
            val token = raw.trim().trim('"', '\'', '(', ')', ':')
            val word = fold(token)
            when {
                token.all { it.isDigit() } -> out.append(token)
                digitWords[word] != null -> out.append(digitWords.getValue(word))
                allowLetters && token.length == 1 && token[0].isLetter() -> out.append(token.uppercase())
                allowLetters && token.all { it.isLetterOrDigit() } && token.any { it.isDigit() } -> out.append(token.uppercase())
                else -> token.filter { it.isDigit() }.let { out.append(it) }
            }
        }
        return out.toString()
    }

    /**
     * Passwords, spelled out: "A maiúsculo, b, c, um, dois, arroba" -> "Abc12@".
     * Letter names ("bê", "efe"), digits, symbol names and "maiúsculo"/"minúsculo" are understood;
     * any other word is typed as-is, without spaces.
     */
    fun secret(spoken: String): String {
        val tokens = spoken.split(Regex("[\\s,;]+")).map { it.trim().trimEnd('.', '!', '?') }.filter { it.isNotEmpty() }
        val folded = tokens.map { fold(it) }
        val out = StringBuilder()
        var upperNext = false
        var lastLetterIndex = -1
        var i = 0
        while (i < tokens.size) {
            val token = tokens[i]
            val word = folded[i]
            // Symbols (possibly multi-word)
            val symbol = symbols.firstOrNull { (words, _) ->
                i + words.size <= folded.size && words.indices.all { folded[i + it] == words[it] }
            }
            when {
                word in upperWords -> {
                    if (lastLetterIndex == out.length - 1 && lastLetterIndex >= 0) {
                        out.setCharAt(lastLetterIndex, out[lastLetterIndex].uppercaseChar())
                    } else {
                        upperNext = true
                    }
                    i++
                }
                word in lowerWords -> i++
                symbol != null -> {
                    out.append(symbol.second)
                    i += symbol.first.size
                }
                token.all { it.isDigit() } -> { out.append(token); i++ }
                digitWords[word] != null -> { out.append(digitWords.getValue(word)); i++ }
                token.length == 1 && token[0].isLetter() -> {
                    out.append(if (upperNext) token.uppercase() else token.lowercase())
                    lastLetterIndex = out.length - 1
                    upperNext = false
                    i++
                }
                letterNames[token.lowercase()] != null || letterNames[word] != null -> {
                    val c = letterNames[token.lowercase()] ?: letterNames.getValue(word)
                    out.append(if (upperNext) c.uppercaseChar() else c)
                    lastLetterIndex = out.length - 1
                    upperNext = false
                    i++
                }
                word in fillerWords -> i++
                else -> {
                    // Tokens with digits or symbols were clearly typed out by Whisper: keep them verbatim.
                    // Plain words are lowercased (Whisper capitalises sentence starts) unless "maiúsculo".
                    val literal = when {
                        token.any { !it.isLetter() } -> token
                        upperNext -> token.lowercase().replaceFirstChar { it.uppercaseChar() }
                        else -> token.lowercase()
                    }
                    out.append(literal)
                    upperNext = false
                    i++
                }
            }
        }
        return out.toString()
    }

    /**
     * E-mail addresses and links: "fernando ponto godinho arroba gmail ponto com" ->
     * "fernando.godinho@gmail.com".
     */
    fun address(spoken: String): String {
        val tokens = spoken.split(Regex("\\s+")).map { it.trim().trimEnd(',', '!', '?') }.filter { it.isNotEmpty() }
        val folded = tokens.map { fold(it).trimEnd('.') }
        val out = StringBuilder()
        var i = 0
        while (i < tokens.size) {
            val symbol = symbols.firstOrNull { (words, _) ->
                i + words.size <= folded.size && words.indices.all { folded[i + it] == words[it] }
            }
            if (symbol != null && symbol.second != " ") {
                out.append(symbol.second)
                i += symbol.first.size
            } else {
                out.append(fold(tokens[i]).filter { it.isLetterOrDigit() || it in "@._-/:~+" })
                i++
            }
        }
        return out.toString().trimEnd('.')
    }
}
