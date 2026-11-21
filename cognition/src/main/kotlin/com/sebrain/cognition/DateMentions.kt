package com.sebrain.cognition

import java.text.Normalizer
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.MonthDay
import java.time.temporal.TemporalAdjusters

/**
 * The dates a text mentions, resolved forward from the day it was captured, in English and
 * Spanish. Done in code because the extraction model does not count days: given a calendar,
 * E2B still put "el jueves" on a Saturday (ExtractionEvalTest, 2026-11-20). The chat's
 * `MemoryTime` answers the opposite question, past periods for recall, in another module.
 *
 * Rules (docs/design-cognition-worker.md §3): a weekday is its next occurrence after the
 * capture day; a day of the month alone is in the capture month, or the next one if it has
 * passed; a day and month without a year are in the capture year, or the next one if they have
 * passed by more than [RECENT_PAST_DAYS] (a transfer "received on 6 November", captured on the
 * 7th, happened this year). "jueves 14" takes the number. Whatever cannot be resolved is left
 * out, never guessed.
 */
object DateMentions {
    private val WEEKDAYS = mapOf(
        "monday" to DayOfWeek.MONDAY, "tuesday" to DayOfWeek.TUESDAY, "wednesday" to DayOfWeek.WEDNESDAY,
        "thursday" to DayOfWeek.THURSDAY, "friday" to DayOfWeek.FRIDAY, "saturday" to DayOfWeek.SATURDAY, "sunday" to DayOfWeek.SUNDAY,
        "lunes" to DayOfWeek.MONDAY, "martes" to DayOfWeek.TUESDAY, "miercoles" to DayOfWeek.WEDNESDAY,
        "jueves" to DayOfWeek.THURSDAY, "viernes" to DayOfWeek.FRIDAY, "sabado" to DayOfWeek.SATURDAY, "domingo" to DayOfWeek.SUNDAY,
    )
    private val MONTHS = mapOf(
        "january" to 1, "february" to 2, "march" to 3, "april" to 4, "may" to 5, "june" to 6, "july" to 7,
        "august" to 8, "september" to 9, "october" to 10, "november" to 11, "december" to 12,
        "enero" to 1, "febrero" to 2, "marzo" to 3, "abril" to 4, "mayo" to 5, "junio" to 6, "julio" to 7,
        "agosto" to 8, "septiembre" to 9, "setiembre" to 9, "octubre" to 10, "noviembre" to 11, "diciembre" to 12,
    )
    private val WEEKDAY = WEEKDAYS.keys.joinToString("|")
    private val MONTH = MONTHS.keys.sortedByDescending { it.length }.joinToString("|")
    private const val DAY = "(\\d{1,2})(?:st|nd|rd|th)?"

    /** A day and month this far in the past are a recent event, not next year's. */
    private const val RECENT_PAST_DAYS = 60L

    /** Each pattern, most specific first; a span already used is not read again. */
    private val PATTERNS: List<Pair<Regex, (MatchResult, LocalDate) -> LocalDate?>> = listOf(
        // "14 de marzo de 2027", "3 de diciembre", "14 march"
        Regex("\\b$DAY\\s+(?:de\\s+|of\\s+)?($MONTH)(?:\\s+(?:de\\s+)?(\\d{4}))?\\b") to { m, today ->
            dayOfMonth(m.groupValues[1], MONTHS.getValue(m.groupValues[2]), m.groupValues[3], today)
        },
        // "March 14", "November 25, 2027"
        Regex("\\b($MONTH)\\s+$DAY(?:,?\\s+(\\d{4}))?\\b") to { m, today ->
            dayOfMonth(m.groupValues[2], MONTHS.getValue(m.groupValues[1]), m.groupValues[3], today)
        },
        Regex("\\b(pasado manana|day after tomorrow)\\b") to { _, today -> today.plusDays(2) },
        Regex("\\b(manana|tomorrow)\\b") to { _, today -> today.plusDays(1) },
        Regex("\\b(hoy|today|tonight|esta noche)\\b") to { _, today -> today },
        // "el sábado 21", "Saturday the 28th": the number decides.
        Regex("\\b(?:$WEEKDAY)\\s+(?:the\\s+)?$DAY\\b") to { m, today -> dayOnly(m.groupValues[1], today) },
        // "el 20", "the 20th"; not "el 30 %" or "the 8 km".
        Regex("\\b(?:el|the)\\s+$DAY\\b(?!\\s*(?:%|euros?|eur|km|minutos|minutes|de\\s+la|of\\s+the))") to { m, today -> dayOnly(m.groupValues[1], today) },
        Regex("\\b($WEEKDAY)\\b") to { m, today -> today.with(TemporalAdjusters.next(WEEKDAYS.getValue(m.groupValues[1]))) },
    )

    /** The dates [text] mentions, in order, as distinct days. */
    fun resolve(text: String, captured: LocalDate): List<LocalDate> {
        val plain = plain(text)
        val used = BooleanArray(plain.length)
        val found = mutableListOf<Pair<Int, LocalDate>>()
        for ((pattern, toDate) in PATTERNS) {
            for (match in pattern.findAll(plain)) {
                if (match.range.any { used[it] }) continue
                // An impossible date ("31 de febrero") still uses up its words, so "el 31" is not read from it.
                match.range.forEach { used[it] = true }
                val date = toDate(match, captured) ?: continue
                found += match.range.first to date
            }
        }
        return found.sortedBy { it.first }.map { it.second }.distinct()
    }

    private fun dayOfMonth(day: String, month: Int, year: String, today: LocalDate): LocalDate? {
        val d = day.toInt()
        if (year.isNotEmpty()) return runCatching { LocalDate.of(year.toInt(), month, d) }.getOrNull()
        val monthDay = runCatching { MonthDay.of(month, d) }.getOrNull() ?: return null
        val thisYear = monthDay.atYear(today.year)
        return if (thisYear.isBefore(today.minusDays(RECENT_PAST_DAYS))) monthDay.atYear(today.year + 1) else thisYear
    }

    private fun dayOnly(day: String, today: LocalDate): LocalDate? {
        val d = day.toInt()
        if (d !in 1..31) return null
        val thisMonth = runCatching { today.withDayOfMonth(d) }.getOrNull()
        if (thisMonth != null && !thisMonth.isBefore(today)) return thisMonth
        return runCatching { today.plusMonths(1).withDayOfMonth(d) }.getOrNull()
    }

    /** Lower case without accents, so "miércoles" and "mañana" match. Keeps every index in place. */
    private fun plain(text: String): String = text.map { c ->
        Normalizer.normalize(c.toString(), Normalizer.Form.NFD).first().lowercaseChar()
    }.joinToString("")
}
