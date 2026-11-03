package com.fernandogodinho.teclado.ime.smart

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.text.Normalizer

/**
 * Offline unit conversion: "10 km em milhas", "30 °C para F", "2,5 kg em libras". Tables only;
 * no currency (exchange rates would need the internet).
 */
object UnitConverter {

    data class Match(val label: String, val insert: String)

    private enum class Dim { LENGTH, MASS, TEMPERATURE, SPEED, VOLUME, AREA, TIME, DATA, ENERGY }

    /** factor = how many base units one unit is worth (base: m, kg, m/s, L, m², s, byte, J). */
    private class Unit(val symbol: String, val dim: Dim, val factor: Double, val aliases: List<String>)

    private val units = listOf(
        Unit("mm", Dim.LENGTH, 0.001, listOf("mm", "milimetro", "milimetros")),
        Unit("cm", Dim.LENGTH, 0.01, listOf("cm", "centimetro", "centimetros")),
        Unit("m", Dim.LENGTH, 1.0, listOf("m", "metro", "metros")),
        Unit("km", Dim.LENGTH, 1000.0, listOf("km", "quilometro", "quilometros", "kilometro", "kilometros")),
        Unit("pol", Dim.LENGTH, 0.0254, listOf("pol", "in", "polegada", "polegadas", "inch", "inches", "\"")),
        Unit("pés", Dim.LENGTH, 0.3048, listOf("ft", "pe", "pes", "feet", "foot")),
        Unit("jardas", Dim.LENGTH, 0.9144, listOf("yd", "jarda", "jardas", "yard", "yards")),
        Unit("mi", Dim.LENGTH, 1609.344, listOf("mi", "milha", "milhas", "mile", "miles")),
        Unit("milhas náuticas", Dim.LENGTH, 1852.0, listOf("nmi", "milha nautica", "milhas nauticas")),
        Unit("mg", Dim.MASS, 1e-6, listOf("mg", "miligrama", "miligramas")),
        Unit("g", Dim.MASS, 0.001, listOf("g", "grama", "gramas")),
        Unit("kg", Dim.MASS, 1.0, listOf("kg", "quilo", "quilos", "quilograma", "quilogramas", "kilo", "kilos")),
        Unit("t", Dim.MASS, 1000.0, listOf("t", "tonelada", "toneladas")),
        Unit("oz", Dim.MASS, 0.028349523125, listOf("oz", "onca", "oncas", "ounce", "ounces")),
        Unit("lb", Dim.MASS, 0.45359237, listOf("lb", "lbs", "libra", "libras", "pound", "pounds")),
        Unit("°C", Dim.TEMPERATURE, 1.0, listOf("c", "°c", "ºc", "celsius", "graus celsius", "grau celsius")),
        Unit("°F", Dim.TEMPERATURE, 1.0, listOf("f", "°f", "ºf", "fahrenheit", "graus fahrenheit")),
        Unit("K", Dim.TEMPERATURE, 1.0, listOf("k", "kelvin", "kelvins")),
        Unit("km/h", Dim.SPEED, 1 / 3.6, listOf("km/h", "kmh", "kph", "quilometros por hora")),
        Unit("m/s", Dim.SPEED, 1.0, listOf("m/s", "metros por segundo")),
        Unit("mph", Dim.SPEED, 0.44704, listOf("mph", "milhas por hora")),
        Unit("nós", Dim.SPEED, 0.514444, listOf("no", "nos", "knot", "knots")),
        Unit("mL", Dim.VOLUME, 0.001, listOf("ml", "mililitro", "mililitros")),
        Unit("L", Dim.VOLUME, 1.0, listOf("l", "litro", "litros", "liter", "liters")),
        Unit("galões", Dim.VOLUME, 3.785411784, listOf("gal", "galao", "galoes", "gallon", "gallons")),
        Unit("m³", Dim.VOLUME, 1000.0, listOf("m3", "m³", "metro cubico", "metros cubicos")),
        Unit("xícaras", Dim.VOLUME, 0.24, listOf("xicara", "xicaras", "cup", "cups")),
        Unit("m²", Dim.AREA, 1.0, listOf("m2", "m²", "metro quadrado", "metros quadrados")),
        Unit("km²", Dim.AREA, 1e6, listOf("km2", "km²", "quilometro quadrado", "quilometros quadrados")),
        Unit("ha", Dim.AREA, 10_000.0, listOf("ha", "hectare", "hectares")),
        Unit("acres", Dim.AREA, 4046.8564224, listOf("acre", "acres")),
        Unit("pés²", Dim.AREA, 0.09290304, listOf("ft2", "ft²", "pe quadrado", "pes quadrados")),
        Unit("s", Dim.TIME, 1.0, listOf("s", "seg", "segundo", "segundos")),
        Unit("min", Dim.TIME, 60.0, listOf("min", "minuto", "minutos")),
        Unit("h", Dim.TIME, 3600.0, listOf("h", "hora", "horas")),
        Unit("dias", Dim.TIME, 86_400.0, listOf("dia", "dias", "day", "days")),
        Unit("semanas", Dim.TIME, 604_800.0, listOf("semana", "semanas", "week", "weeks")),
        Unit("KB", Dim.DATA, 1e3, listOf("kb", "kilobyte", "kilobytes")),
        Unit("MB", Dim.DATA, 1e6, listOf("mb", "megabyte", "megabytes", "mega", "megas")),
        Unit("GB", Dim.DATA, 1e9, listOf("gb", "gigabyte", "gigabytes", "giga", "gigas")),
        Unit("TB", Dim.DATA, 1e12, listOf("tb", "terabyte", "terabytes")),
        Unit("kcal", Dim.ENERGY, 4184.0, listOf("kcal", "caloria", "calorias", "quilocaloria", "quilocalorias")),
        Unit("kJ", Dim.ENERGY, 1000.0, listOf("kj", "quilojoule", "quilojoules")),
    )

    private val byAlias: Map<String, Unit> = units.flatMap { u -> u.aliases.map { strip(it) to u } }.toMap()

    private fun strip(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").trim()

    private val pattern = Regex(
        "(-?\\d[\\d.]*(?:,\\d+)?)\\s*([\\p{L}°º²³/\"]+(?:\\s(?:quadrados?|cubicos?|cúbicos?|celsius|fahrenheit|nauticas?|náuticas?|por hora|por segundo))?)" +
            "\\s+(?:em|para|pra|in|to|=)\\s+([\\p{L}°º²³/\"]+(?:\\s(?:quadrados?|cubicos?|cúbicos?|celsius|fahrenheit|nauticas?|náuticas?|por hora|por segundo))?)\\s*[=?]?\\s*$",
        RegexOption.IGNORE_CASE,
    )

    fun detect(before: String): Match? {
        val m = pattern.find(before.takeLast(80)) ?: return null
        val (numberText, fromText, toText) = m.destructured
        val from = byAlias[strip(fromText)] ?: return null
        val to = byAlias[strip(toText)] ?: return null
        if (from.dim != to.dim || from === to) return null
        val decimalComma = numberText.contains(',')
        val value = parse(numberText) ?: return null
        val result = convert(value, from, to)
        val shown = format(result, decimalComma || !numberText.contains('.'))
        return Match("$numberText ${from.symbol} = $shown ${to.symbol}", "$shown ${to.symbol}")
    }

    private fun parse(text: String): Double? {
        val t = if (text.contains(',')) text.replace(".", "").replace(',', '.')
        else if (Regex("-?\\d{1,3}(\\.\\d{3})+").matches(text)) text.replace(".", "") else text
        return t.toDoubleOrNull()
    }

    private fun convert(v: Double, from: Unit, to: Unit): Double {
        if (from.dim == Dim.TEMPERATURE) {
            val celsius = when (from.symbol) {
                "°F" -> (v - 32) * 5 / 9
                "K" -> v - 273.15
                else -> v
            }
            return when (to.symbol) {
                "°F" -> celsius * 9 / 5 + 32
                "K" -> celsius + 273.15
                else -> celsius
            }
        }
        return v * from.factor / to.factor
    }

    /** Up to 4 significant decimals, Brazilian format by default. */
    fun format(v: Double, decimalComma: Boolean): String {
        val bd = BigDecimal(v).round(MathContext(8)).setScale(if (kotlin.math.abs(v) >= 100) 1 else 4, RoundingMode.HALF_EVEN).stripTrailingZeros()
        val plain = if (bd.signum() == 0) "0" else bd.toPlainString()
        if (!decimalComma) return plain
        val negative = plain.startsWith("-")
        val digits = plain.removePrefix("-")
        val intPart = digits.substringBefore('.')
        val frac = digits.substringAfter('.', "")
        val grouped = intPart.reversed().chunked(3).joinToString(".").reversed()
        return (if (negative) "-" else "") + grouped + (if (frac.isNotEmpty()) ",$frac" else "")
    }
}
