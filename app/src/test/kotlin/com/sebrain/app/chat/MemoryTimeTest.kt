package com.sebrain.app.chat

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MemoryTimeTest {
    /** A Wednesday: this week starts on Monday 2026-11-16. */
    private val today = LocalDate.of(2026, 11, 18)

    private fun range(message: String): Pair<String, String>? =
        MemoryTime.periodIn(message, today)?.let { it.from.toString() to it.to.toString() }

    @Test
    fun todayAndYesterdayInBothLanguages() {
        assertEquals("2026-11-18" to "2026-11-18", range("¿Qué escribí hoy?"))
        assertEquals("2026-11-17" to "2026-11-17", range("What did I write yesterday?"))
        assertEquals("2026-11-17" to "2026-11-17", range("lo que anoté ayer"))
    }

    @Test
    fun weeksStartOnMonday() {
        assertEquals("2026-11-16" to "2026-11-18", range("notes from this week"))
        assertEquals("2026-11-09" to "2026-11-15", range("Summarize last week"))
        assertEquals("2026-11-09" to "2026-11-15", range("lo que decidí la semana pasada"))
    }

    @Test
    fun monthsCoverTheWholeMonthUpToToday() {
        assertEquals("2026-11-01" to "2026-11-18", range("what did I do this month"))
        assertEquals("2026-10-01" to "2026-10-31", range("¿qué hice el mes pasado?"))
        assertEquals("2026-05-01" to "2026-05-31", range("what did I plan in May"))
        // A month still to come this year is last year's.
        assertEquals("2025-12-01" to "2025-12-31", range("lo que escribí en diciembre"))
    }

    @Test
    fun aDayCanBeWrittenInSeveralWays() {
        assertEquals("2026-11-10" to "2026-11-10", range("what happened on 2026-11-10"))
        assertEquals("2026-11-10" to "2026-11-10", range("¿qué pasó el 10 de noviembre?"))
        assertEquals("2026-11-10" to "2026-11-10", range("my notes from November 10th"))
        assertEquals("2026-03-04" to "2026-03-04", range("the meeting on 4 March"))
    }

    @Test
    fun aMessageWithoutATimeHasNoPeriod() {
        assertNull(range("How does TCP work?"))
        // "May" the verb is not the month, and "anteayer" is not "ayer".
        assertNull(range("May I ask you something?"))
        assertNull(range("lo vi anteayer"))
    }

    @Test
    fun thePeriodIsNamedForThePrompt() {
        assertEquals("last week (2026-11-09 to 2026-11-15)", MemoryTime.periodIn("last week", today)!!.label)
        assertEquals("yesterday (2026-11-17)", MemoryTime.periodIn("ayer", today)!!.label)
    }

    @Test
    fun theModelIsToldTodaysDate() {
        assertEquals("Today is Wednesday, 18 November 2026.", MemoryTime.todayLine(today))
    }
}
