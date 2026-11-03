package com.fernandogodinho.teclado.ime.smart

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * Calculator inside the keyboard: type "12,5 × 3 + 4 =" and the keyboard offers the result.
 * A small recursive-descent parser (no scripting engine), Brazilian number format aware.
 */
object Calculator {

    data class Match(val expression: String, val result: String)

    private val MC = MathContext(20, RoundingMode.HALF_EVEN)

    /** Looks at the text before the cursor; only expressions ending in "=" are evaluated. */
    fun detect(before: String): Match? {
        val trimmed = before.trimEnd()
        if (!trimmed.endsWith("=")) return null
        val body = trimmed.dropLast(1)
        // Longest run of calculator characters right before the "=".
        var start = body.length
        while (start > 0 && body[start - 1] in ALLOWED) start--
        // Text such as "max 2 x 3 =" leaves a dangling operator at the start: drop it.
        val expr = body.substring(start).trim().trimStart { it in "*×x/÷^%+ " }.trim()
        if (expr.length < 3 || expr.none { it.isDigit() } || expr.none { it in OPERATORS }) return null
        // A leading "-" alone is not a calculation ("-5 =").
        if (expr.drop(1).none { it in OPERATORS }) return null
        val decimalComma = expr.contains(',')
        val value = evaluate(expr) ?: return null
        return Match(expr, format(value, decimalComma))
    }

    fun evaluate(expression: String): BigDecimal? = runCatching {
        val p = Parser(normalize(expression))
        val v = p.expression()
        if (!p.atEnd()) null else v
    }.getOrNull()

    fun format(value: BigDecimal, decimalComma: Boolean): String {
        val rounded = value.setScale(10, RoundingMode.HALF_EVEN).stripTrailingZeros()
        val plain = if (rounded.compareTo(BigDecimal.ZERO) == 0) "0" else rounded.toPlainString()
        if (!decimalComma) return plain
        val negative = plain.startsWith("-")
        val digits = plain.removePrefix("-")
        val intPart = digits.substringBefore('.')
        val frac = digits.substringAfter('.', "")
        val grouped = intPart.reversed().chunked(3).joinToString(".").reversed()
        return (if (negative) "-" else "") + grouped + (if (frac.isNotEmpty()) ",$frac" else "")
    }

    private const val OPERATORS = "+-−*×x/÷^%"
    private val ALLOWED = (OPERATORS + "0123456789.,() ").toSet()

    /** Converts Brazilian numbers ("1.250,50") and symbols to a plain internal form. */
    private fun normalize(expr: String): String {
        val decimalComma = expr.contains(',')
        val sb = StringBuilder()
        val number = Regex("[0-9][0-9.,]*")
        var i = 0
        while (i < expr.length) {
            val m = number.find(expr, i)
            if (m != null && m.range.first == i) {
                var n = m.value
                n = if (decimalComma) n.replace(".", "").replace(',', '.') else if (Regex("\\d{1,3}(\\.\\d{3})+").matches(n)) n.replace(".", "") else n
                sb.append(n)
                i = m.range.last + 1
                continue
            }
            val c = expr[i]
            sb.append(
                when (c) {
                    '×', 'x' -> '*'
                    '÷' -> '/'
                    '−' -> '-'
                    else -> c
                },
            )
            i++
        }
        return sb.toString().replace(" ", "")
    }

    private class Parser(val s: String) {
        var pos = 0
        fun atEnd() = pos >= s.length
        private fun peek() = if (pos < s.length) s[pos] else '\u0000'

        fun expression(): BigDecimal {
            var v = term()
            while (true) {
                v = when (peek()) {
                    '+' -> { pos++; v.add(term(), MC) }
                    '-' -> { pos++; v.subtract(term(), MC) }
                    else -> return v
                }
            }
        }

        fun term(): BigDecimal {
            var v = power()
            while (true) {
                v = when (peek()) {
                    '*' -> { pos++; v.multiply(power(), MC) }
                    '/' -> {
                        pos++
                        val d = power()
                        require(d.signum() != 0)
                        v.divide(d, MC)
                    }
                    else -> return v
                }
            }
        }

        fun power(): BigDecimal {
            val base = unary()
            if (peek() == '^') {
                pos++
                val exp = power()
                val e = exp.toInt()
                require(exp.compareTo(BigDecimal(e)) == 0 && e in -50..50)
                return if (e >= 0) base.pow(e, MC) else BigDecimal.ONE.divide(base.pow(-e, MC), MC)
            }
            return base
        }

        fun unary(): BigDecimal = when (peek()) {
            '-' -> { pos++; unary().negate() }
            '+' -> { pos++; unary() }
            else -> percent()
        }

        fun percent(): BigDecimal {
            var v = primary()
            while (peek() == '%') {
                pos++
                v = v.divide(BigDecimal(100), MC)
            }
            return v
        }

        fun primary(): BigDecimal {
            if (peek() == '(') {
                pos++
                val v = expression()
                require(peek() == ')')
                pos++
                return v
            }
            val start = pos
            while (pos < s.length && (s[pos].isDigit() || s[pos] == '.')) pos++
            require(pos > start)
            return BigDecimal(s.substring(start, pos))
        }
    }
}
