package com.fernandogodinho.teclado.ime.smart

import java.text.Normalizer
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/**
 * Turns "lembrete amanhã às 8h ligar para o banco" or "timer 10 minutos" into a reminder or a
 * timer, with deterministic rules (no AI). The keyboard always shows what it understood and
 * waits for the user to confirm before opening the calendar or clock app.
 */
object Reminders {

    sealed class Request {
        abstract val summary: String

        data class Event(val at: LocalDateTime, val title: String) : Request() {
            override val summary: String
                get() = "Lembrete ${describe(at)}" + if (title.isNotBlank()) ": $title" else ""
        }

        data class Timer(val seconds: Int, val title: String) : Request() {
            override val summary: String
                get() = "Timer de ${durationLabel(seconds)}" + if (title.isNotBlank()) ": $title" else ""
        }
    }

    private val triggers = Regex("^(?:/?lembrete|/?lembrar|me lembr[ea]|lembre-me|/?timer|/?temporizador|/?alarme)\\b", RegexOption.IGNORE_CASE)

    private fun fold(s: String) = Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

    /** Whether [text] looks like a reminder command at all (cheap check). */
    fun looksLikeCommand(text: String) = triggers.containsMatchIn(text.trim())

    fun parse(text: String, now: LocalDateTime = LocalDateTime.now()): Request? {
        val original = text.trim()
        if (!looksLikeCommand(original)) return null
        var rest = fold(original).replace(Regex("^(?:/?lembrete|/?lembrar|me lembr[ea]|lembre-me|/?timer|/?temporizador|/?alarme)\\b(?:\\s+de)?"), "").trim()
        val isTimerWord = fold(original).let { it.startsWith("timer") || it.startsWith("/timer") || it.startsWith("temporizador") || it.startsWith("/temporizador") }

        // "em 20 minutos", "daqui a 2 horas", "timer 10 min"
        val duration = Regex("(?:(?:em|daqui a|daqui|por|de)\\s+)?(\\d+)\\s*(segundos?|seg|s|minutos?|min|m|horas?|h)\\b").find(rest)
        if (duration != null && (isTimerWord || rest.contains("daqui") || Regex("\\bem\\s+\\d").containsMatchIn(rest))) {
            val n = duration.groupValues[1].toInt()
            val unit = duration.groupValues[2]
            val seconds = when {
                unit.startsWith("h") -> n * 3600
                unit.startsWith("m") -> n * 60
                else -> n
            }
            if (seconds in 1..86_400) {
                val title = cleanTitle(original, removeFrom = duration.value, rest = rest)
                return Request.Timer(seconds, title)
            }
        }

        var date: LocalDate? = null
        when {
            Regex("\\bdepois de amanha\\b").containsMatchIn(rest) -> { date = now.toLocalDate().plusDays(2); rest = rest.replace(Regex("\\bdepois de amanha\\b"), " ") }
            Regex("\\bamanha\\b").containsMatchIn(rest) -> { date = now.toLocalDate().plusDays(1); rest = rest.replace(Regex("\\bamanha\\b"), " ") }
            Regex("\\bhoje\\b").containsMatchIn(rest) -> { date = now.toLocalDate(); rest = rest.replace(Regex("\\bhoje\\b"), " ") }
        }
        if (date == null) {
            val weekdays = listOf(
                "segunda" to DayOfWeek.MONDAY, "terca" to DayOfWeek.TUESDAY, "quarta" to DayOfWeek.WEDNESDAY,
                "quinta" to DayOfWeek.THURSDAY, "sexta" to DayOfWeek.FRIDAY, "sabado" to DayOfWeek.SATURDAY, "domingo" to DayOfWeek.SUNDAY,
            )
            for ((name, dow) in weekdays) {
                val r = Regex("\\b(?:na |no |nesta |neste |proxima |proximo )?$name(?:-feira)?\\b")
                if (r.containsMatchIn(rest)) {
                    date = now.toLocalDate().with(TemporalAdjusters.next(dow))
                    rest = rest.replace(r, " ")
                    break
                }
            }
        }
        if (date == null) {
            Regex("\\b(?:dia\\s+)?(\\d{1,2})/(\\d{1,2})(?:/(\\d{2,4}))?\\b").find(rest)?.let { m ->
                val day = m.groupValues[1].toInt()
                val month = m.groupValues[2].toInt()
                val year = m.groupValues[3].takeIf { it.isNotEmpty() }?.toInt()?.let { if (it < 100) 2000 + it else it } ?: now.year
                date = runCatching { LocalDate.of(year, month, day) }.getOrNull()?.let { if (it.isBefore(now.toLocalDate()) && m.groupValues[3].isEmpty()) it.plusYears(1) else it }
                rest = rest.replace(m.value, " ")
            }
        }

        var time: LocalTime? = null
        val timeMatch = Regex("\\b(?:as|a|ao|ao meio-dia|meio-dia|para as)?\\s*(\\d{1,2})(?:\\s*[h:]\\s*(\\d{2})?|\\s*horas?|(?=\\s*(?:da|de)\\s*(?:manha|tarde|noite|madrugada)))(?:\\s*(?:da|de)\\s*(manha|tarde|noite|madrugada))?").find(rest)
        if (timeMatch != null) {
            var hour = timeMatch.groupValues[1].toInt()
            val minute = timeMatch.groupValues[2].takeIf { it.isNotEmpty() }?.toInt() ?: 0
            when (timeMatch.groupValues[3]) {
                "tarde", "noite" -> if (hour in 1..11) hour += 12
            }
            if (hour in 0..23 && minute in 0..59) {
                time = LocalTime.of(hour, minute)
                rest = rest.replace(timeMatch.value, " ")
            }
        } else if (Regex("\\bmeio-dia\\b").containsMatchIn(rest)) {
            time = LocalTime.NOON
        }
        if (date == null && time == null) return null
        val at = LocalDateTime.of(date ?: now.toLocalDate(), time ?: LocalTime.of(9, 0)).let {
            // "lembrete às 7h" said at 20h means tomorrow.
            if (date == null && it.isBefore(now)) it.plusDays(1) else it
        }
        val title = cleanTitle(original, removeFrom = null, rest = rest)
        return Request.Event(at, title)
    }

    /** What is left after removing the trigger and the time words, with the original accents. */
    private fun cleanTitle(original: String, removeFrom: String?, rest: String): String {
        val leftover = rest.replace(removeFrom ?: "\u0000", " ")
            .replace(Regex("\\b(?:de|para|pra|que|as|a|em|daqui)\\b\\s*$"), " ")
            .replace(Regex("^\\s*(?:de|para|pra|que|:|-)\\s+"), " ")
            .replace(Regex("\\s+"), " ").trim().trim(':', '-', ',').trim()
        if (leftover.isEmpty()) return ""
        // Recover accents: find the same words in the original text.
        val words = leftover.split(' ')
        val originalWords = original.split(Regex("\\s+"))
        val restored = words.map { w -> originalWords.firstOrNull { fold(it).trim(',', '.', ':') == w } ?: w }
        return restored.joinToString(" ").trim(',', '.', ':').replaceFirstChar { it.uppercase() }
    }

    private val dayFmt = DateTimeFormatter.ofPattern("dd/MM", Locale("pt", "BR"))

    fun describe(at: LocalDateTime, now: LocalDateTime = LocalDateTime.now()): String {
        val day = when (at.toLocalDate()) {
            now.toLocalDate() -> "hoje"
            now.toLocalDate().plusDays(1) -> "amanhã"
            else -> "em ${at.format(dayFmt)}"
        }
        return "$day às ${"%02d:%02d".format(at.hour, at.minute)}"
    }

    fun durationLabel(seconds: Int): String = when {
        seconds % 3600 == 0 -> "${seconds / 3600} h"
        seconds % 60 == 0 -> "${seconds / 60} min"
        else -> "$seconds s"
    }
}
