package com.fernandogodinho.teclado.ime.layout

/** What a key does when activated. */
sealed interface KeyAction {
    data class Text(val text: String) : KeyAction
    data object Shift : KeyAction
    data object Backspace : KeyAction
    data object Enter : KeyAction
    data object Space : KeyAction
    data object ToSymbols : KeyAction
    data object ToMoreSymbols : KeyAction
    data object ToLetters : KeyAction
    data object Emoji : KeyAction
    data object Mic : KeyAction
}

enum class KeyStyle { CHARACTER, FUNCTION, ENTER, SPACE }

data class KeySpec(
    val label: String,
    val action: KeyAction,
    val width: Float = 1f,
    val alternates: List<String> = emptyList(),
    val hint: String? = null,
    val style: KeyStyle = KeyStyle.CHARACTER,
) {
    val isCharacter: Boolean get() = action is KeyAction.Text
}

data class KeyRow(val keys: List<KeySpec>, val padding: Float = 0f)

data class KeyboardLayout(val rows: List<KeyRow>, val units: Float = 10f)

enum class LayoutMode { LETTERS, SYMBOLS, MORE_SYMBOLS, NUMBERS, PHONE }

/** Field-dependent tweaks to the bottom row. */
enum class FieldKind { TEXT, EMAIL, URL, PASSWORD }

object Layouts {

    /** Long-press alternatives, most frequent in Portuguese first. */
    private val accents = mapOf(
        "a" to listOf("á", "ã", "â", "à", "ä", "å", "æ", "ª"),
        "e" to listOf("é", "ê", "è", "ë", "€"),
        "i" to listOf("í", "î", "ì", "ï"),
        "o" to listOf("ó", "õ", "ô", "ò", "ö", "º", "ø", "œ"),
        "u" to listOf("ú", "ü", "û", "ù"),
        "c" to listOf("ç", "ć", "č", "©"),
        "n" to listOf("ñ"),
        "s" to listOf("ß", "š", "$"),
        "y" to listOf("ý", "ÿ"),
        "z" to listOf("ž"),
        "l" to listOf("ł"),
        "d" to listOf("ð"),
    )

    private val topRowDigits = "1234567890"

    private fun letter(ch: Char, hint: String? = null): KeySpec {
        val s = ch.toString()
        val alts = accents[s].orEmpty()
        val withDigit = if (hint != null) alts + hint else alts
        return KeySpec(label = s, action = KeyAction.Text(s), alternates = withDigit, hint = hint)
    }

    private fun char(s: String, alternates: List<String> = emptyList(), width: Float = 1f) =
        KeySpec(label = s, action = KeyAction.Text(s), alternates = alternates, width = width)

    private val shift = KeySpec("shift", KeyAction.Shift, width = 1.5f, style = KeyStyle.FUNCTION)
    private val backspace = KeySpec("backspace", KeyAction.Backspace, width = 1.5f, style = KeyStyle.FUNCTION)
    private val enter = KeySpec("enter", KeyAction.Enter, width = 1.5f, style = KeyStyle.ENTER)
    private val toSymbols = KeySpec("?123", KeyAction.ToSymbols, width = 1.5f, style = KeyStyle.FUNCTION)
    private val toLetters = KeySpec("ABC", KeyAction.ToLetters, width = 1.5f, style = KeyStyle.FUNCTION)
    private val emoji = KeySpec("emoji", KeyAction.Emoji, style = KeyStyle.FUNCTION)

    private val periodAlternates = listOf("?", "!", ",", "…", ":", ";", "-", "'", "\"", "@", "/", "(", ")")
    private val commaAlternates = listOf(";", ":", "'", "\"", "-", "_")

    private fun space(width: Float) = KeySpec(" ", KeyAction.Space, width = width, style = KeyStyle.SPACE)

    private fun bottomRow(kind: FieldKind, left: KeySpec): KeyRow = when (kind) {
        FieldKind.EMAIL -> KeyRow(
            listOf(left, char("@", width = 1f), emoji, space(3.5f), char(".", listOf(".com", ".br", ".com.br", "-", "_")), enter),
        )
        FieldKind.URL -> KeyRow(
            listOf(left, char("/", width = 1f), emoji, space(3.5f), char(".", listOf(".com", ".br", ".com.br", "-", ":")), enter),
        )
        else -> KeyRow(listOf(left, char(",", commaAlternates), emoji, space(4f), char(".", periodAlternates), enter))
    }

    fun letters(numberRow: Boolean, kind: FieldKind): KeyboardLayout {
        val rows = mutableListOf<KeyRow>()
        if (numberRow) rows += KeyRow(topRowDigits.map { char(it.toString()) })
        rows += KeyRow("qwertyuiop".mapIndexed { i, c -> letter(c, if (numberRow) null else topRowDigits[i].toString()) })
        rows += KeyRow("asdfghjklç".map { letter(it) })
        rows += KeyRow(listOf(shift) + "zxcvbnm".map { letter(it) } + backspace)
        rows += bottomRow(kind, toSymbols.copy(width = 1.5f)).fitTo(10f)
        return KeyboardLayout(rows)
    }

    fun symbols(kind: FieldKind): KeyboardLayout = KeyboardLayout(
        listOf(
            KeyRow(topRowDigits.map { d -> char(d.toString(), fractionAlternates[d.toString()].orEmpty()) }),
            KeyRow(
                listOf(
                    char("@"), char("#"), char("R$", listOf("$", "€", "£", "¥", "¢")), char("_"), char("&"),
                    char("-", listOf("–", "—", "·")), char("+", listOf("±")), char("("), char(")"), char("/", listOf("\\")),
                ),
            ),
            KeyRow(
                listOf(KeySpec("=\\<", KeyAction.ToMoreSymbols, width = 1.5f, style = KeyStyle.FUNCTION)) +
                    listOf(
                        char("*", listOf("†", "‡", "★")), char("\"", listOf("“", "”", "«", "»")), char("'", listOf("‘", "’")),
                        char(":"), char(";"), char("!", listOf("¡")), char("?", listOf("¿")),
                    ) + backspace,
            ),
            bottomRow(kind, toLetters).fitTo(10f),
        ),
    )

    fun moreSymbols(kind: FieldKind): KeyboardLayout = KeyboardLayout(
        listOf(
            KeyRow(listOf("~", "`", "|", "•", "√", "π", "÷", "×", "§", "∆").map { char(it) }),
            KeyRow(listOf("£", "€", "¥", "^", "°", "=", "{", "}", "\\", "%").map { char(it, if (it == "%") listOf("‰") else emptyList()) }),
            KeyRow(
                listOf(KeySpec("?123", KeyAction.ToSymbols, width = 1.5f, style = KeyStyle.FUNCTION)) +
                    listOf("©", "®", "™", "✓", "[", "]", "<").map { char(it) } +
                    backspace,
            ),
            bottomRow(kind, toLetters).fitTo(10f),
        ),
    )

    fun numbers(): KeyboardLayout = KeyboardLayout(
        listOf(
            KeyRow(listOf(char("1"), char("2"), char("3"), char("-", width = 1f).copy(style = KeyStyle.FUNCTION))),
            KeyRow(listOf(char("4"), char("5"), char("6"), char(",", width = 1f).copy(style = KeyStyle.FUNCTION))),
            KeyRow(listOf(char("7"), char("8"), char("9"), backspace.copy(width = 1f))),
            KeyRow(listOf(char(".").copy(style = KeyStyle.FUNCTION), char("0"), space(1f).copy(label = "␣", style = KeyStyle.FUNCTION), enter.copy(width = 1f))),
        ),
        units = 4f,
    )

    fun phone(): KeyboardLayout = KeyboardLayout(
        listOf(
            KeyRow(listOf(char("1"), char("2"), char("3"), char("-").copy(style = KeyStyle.FUNCTION))),
            KeyRow(listOf(char("4"), char("5"), char("6"), char("+").copy(style = KeyStyle.FUNCTION))),
            KeyRow(listOf(char("7"), char("8"), char("9"), backspace.copy(width = 1f))),
            KeyRow(listOf(char("*", listOf("#", "(", ")", "/", "N", ",", ";")).copy(style = KeyStyle.FUNCTION), char("0", listOf("+")), char("#").copy(style = KeyStyle.FUNCTION), enter.copy(width = 1f))),
        ),
        units = 4f,
    )

    private val fractionAlternates = mapOf(
        "1" to listOf("¹", "½", "⅓", "¼"),
        "2" to listOf("²", "⅔"),
        "3" to listOf("³", "¾"),
        "0" to listOf("°", "ⁿ"),
    )

    /** Stretches the space bar so the row always spans the full width. */
    private fun KeyRow.fitTo(units: Float): KeyRow {
        val total = keys.sumOf { it.width.toDouble() }.toFloat()
        val diff = units - total
        if (kotlin.math.abs(diff) < 0.01f) return this
        return copy(keys = keys.map { if (it.action == KeyAction.Space) it.copy(width = (it.width + diff).coerceAtLeast(1f)) else it })
    }
}
