package com.sebrain.app.chat

import java.text.Normalizer
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * Time in the agent's memory: the period a message asks about ("yesterday", "la semana
 * pasada", "10 de noviembre"), so the notes written then can be recalled, and today's date,
 * which the model cannot know by itself. English and Spanish. Weeks start on Monday.
 */
object MemoryTime {
    /** From [from] to [to], both included. [label] names it for the prompt. */
    data class Period(val from: LocalDate, val to: LocalDate, val label: String)

    private val MONTHS = mapOf(
        "january" to 1, "february" to 2, "march" to 3, "april" to 4, "may" to 5, "june" to 6,
        "july" to 7, "august" to 8, "september" to 9, "october" to 10, "november" to 11, "december" to 12,
        "enero" to 1, "febrero" to 2, "marzo" to 3, "abril" to 4, "mayo" to 5, "junio" to 6,
        "julio" to 7, "agosto" to 8, "septiembre" to 9, "setiembre" to 9, "octubre" to 10, "noviembre" to 11, "diciembre" to 12,
    )
    private val MONTH = MONTHS.keys.sortedByDescending { it.length }.joinToString("|")

    private val ISO_DAY = Regex("\\b(\\d{4})-(\\d{2})-(\\d{2})\\b")
    private val DAY_DE_MONTH = Regex("\\b(\\d{1,2}) de ($MONTH)(?: de (\\d{4}))?\\b")
    private val MONTH_DAY = Regex("\\b($MONTH) (\\d{1,2})(?:st|nd|rd|th)?\\b(?:,? (\\d{4})\\b)?")
    private val DAY_MONTH = Regex("\\b(\\d{1,2})(?:st|nd|rd|th)? (?:of )?($MONTH)\\b(?:,? (\\d{4})\\b)?")
    // A month alone only after a preposition: "May I ask" is not the month.
    private val IN_MONTH = Regex("\\b(?:in|en|during|durante|de) ($MONTH)\\b")

    private val TODAY = Regex("\\b(today|hoy)\\b")
    private val YESTERDAY = Regex("\\b(yesterday|ayer)\\b")
    private val THIS_WEEK = Regex("\\b(this week|esta semana)\\b")
    private val LAST_WEEK = Regex("\\b(last week|(la )?semana pasada)\\b")
    private val THIS_MONTH = Regex("\\b(this month|este mes)\\b")
    private val LAST_MONTH = Regex("\\b(last month|(el )?mes pasado)\\b")

    /** The period [message] asks about, or null if it names none. A date with no year is the latest one not after [today]. */
    fun periodIn(message: String, today: LocalDate): Period? {
        val text = fold(message)
        return exactDay(text, today) ?: namedMonth(text, today) ?: relative(text, today)
    }

    /** "Today is Wednesday, 18 November 2026." */
    fun todayLine(today: LocalDate): String =
        "Today is ${today.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)}, " +
            "${today.dayOfMonth} ${today.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)} ${today.year}."

    private fun exactDay(text: String, today: LocalDate): Period? {
        ISO_DAY.find(text)?.let { m ->
            val day = runCatching { LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()) }.getOrNull()
            if (day != null) return day(day, day.toString())
        }
        DAY_DE_MONTH.find(text)?.let { m -> return dayOf(m.groupValues[1], m.groupValues[2], m.groupValues[3], today) }
        MONTH_DAY.find(text)?.let { m -> return dayOf(m.groupValues[2], m.groupValues[1], m.groupValues[3], today) }
        DAY_MONTH.find(text)?.let { m -> return dayOf(m.groupValues[1], m.groupValues[2], m.groupValues[3], today) }
        return null
    }

    private fun dayOf(dayText: String, monthName: String, yearText: String, today: LocalDate): Period? {
        val month = MONTHS[monthName] ?: return null
        val day = dayText.toInt()
        val year = yearText.toIntOrNull()
        val date = if (year != null) {
            runCatching { LocalDate.of(year, month, day) }.getOrNull()
        } else {
            val thisYear = runCatching { LocalDate.of(today.year, month, day) }.getOrNull() ?: return null
            if (thisYear.isAfter(today)) runCatching { LocalDate.of(today.year - 1, month, day) }.getOrNull() else thisYear
        } ?: return null
        return day(date, date.toString())
    }

    private fun namedMonth(text: String, today: LocalDate): Period? {
        val month = IN_MONTH.find(text)?.let { MONTHS[it.groupValues[1]] } ?: return null
        val start = LocalDate.of(today.year, month, 1).let { if (it.isAfter(today)) it.minusYears(1) else it }
        val name = start.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
        return span(start, start.withDayOfMonth(start.lengthOfMonth()), "$name ${start.year}")
    }

    private fun relative(text: String, today: LocalDate): Period? {
        val monday = today.with(DayOfWeek.MONDAY)
        val firstOfMonth = today.withDayOfMonth(1)
        return when {
            YESTERDAY.containsMatchIn(text) -> today.minusDays(1).let { day(it, "yesterday ($it)") }
            TODAY.containsMatchIn(text) -> day(today, "today ($today)")
            LAST_WEEK.containsMatchIn(text) -> span(monday.minusWeeks(1), monday.minusDays(1), "last week")
            THIS_WEEK.containsMatchIn(text) -> span(monday, today, "this week")
            LAST_MONTH.containsMatchIn(text) -> firstOfMonth.minusMonths(1).let { span(it, firstOfMonth.minusDays(1), "last month") }
            THIS_MONTH.containsMatchIn(text) -> span(firstOfMonth, today, "this month")
            else -> null
        }
    }

    private fun day(date: LocalDate, label: String) = Period(date, date, label)

    private fun span(from: LocalDate, to: LocalDate, name: String) = Period(from, to, "$name ($from to $to)")

    private fun fold(text: String): String =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
}
