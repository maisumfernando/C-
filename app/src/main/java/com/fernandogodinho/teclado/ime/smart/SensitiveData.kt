package com.fernandogodinho.teclado.ime.smart

/**
 * Local rules that spot personal data about to be sent (card numbers, CPF, CNPJ), so the keyboard
 * can show a gentle warning. Nothing is stored: the text is checked and forgotten.
 */
object SensitiveData {

    enum class Kind(val warning: String) {
        CARD("Isto parece um número de cartão. Confira antes de enviar."),
        CPF("Isto parece um CPF. Só compartilhe com quem precisa."),
        CNPJ("Isto parece um CNPJ. Confira o destinatário antes de enviar."),
    }

    private val digitRun = Regex("(?<![\\d])(\\d[\\d .\\-/]{9,22}\\d)(?![\\d])")

    /** The first kind of sensitive data found in [text], or null. */
    fun detect(text: String): Kind? {
        for (m in digitRun.findAll(text)) {
            val digits = m.value.filter { it.isDigit() }
            when (digits.length) {
                11 -> if (isCpf(digits)) return Kind.CPF
                14 -> if (isCnpj(digits)) return Kind.CNPJ
            }
            if (digits.length in 13..19 && luhn(digits) && m.value.none { it == '/' || it == '.' }) return Kind.CARD
        }
        return null
    }

    fun luhn(digits: String): Boolean {
        if (digits.all { it == digits[0] }) return false
        var sum = 0
        digits.reversed().forEachIndexed { i, c ->
            var d = c - '0'
            if (i % 2 == 1) {
                d *= 2
                if (d > 9) d -= 9
            }
            sum += d
        }
        return sum % 10 == 0
    }

    fun isCpf(d: String): Boolean {
        if (d.length != 11 || d.all { it == d[0] }) return false
        fun check(n: Int): Int {
            val sum = (0 until n).sumOf { (d[it] - '0') * (n + 1 - it) }
            val r = sum * 10 % 11
            return if (r == 10) 0 else r
        }
        return check(9) == d[9] - '0' && check(10) == d[10] - '0'
    }

    fun isCnpj(d: String): Boolean {
        if (d.length != 14 || d.all { it == d[0] }) return false
        fun check(n: Int): Int {
            val weights = if (n == 12) intArrayOf(5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2) else intArrayOf(6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2)
            val sum = (0 until n).sumOf { (d[it] - '0') * weights[it] }
            val r = sum % 11
            return if (r < 2) 0 else 11 - r
        }
        return check(12) == d[12] - '0' && check(13) == d[13] - '0'
    }
}
