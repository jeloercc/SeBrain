package com.sebrain.app.chat

import com.sebrain.core.vault.NoteStore.PropertyKey
import com.sebrain.core.vault.NoteStore.PropertyValue
import org.junit.Assert.assertEquals
import org.junit.Test

class MemoryQueryTest {
    private val values = listOf(
        PropertyValue("status", "pending", 12),
        PropertyValue("status", "done", 3),
        PropertyValue("project", "Atlas", 4),
        PropertyValue("type", "meeting", 5),
        PropertyValue("estado", "en curso", 2),
    )

    private fun named(message: String) = MemoryQuery.mentioned(message, values).map { "${it.key}: ${it.value}" }

    @Test
    fun aValueCountsWhenItsKeyIsNamedToo() {
        assertEquals(listOf("status: pending"), named("Which tasks have status pending?"))
        assertEquals(listOf("project: Atlas"), named("¿qué notas son del project atlas?"))
    }

    @Test
    fun aValueOrAKeyAloneIsNotEnough() {
        assertEquals(emptyList<String>(), named("I have pending things to do"))
        assertEquals(emptyList<String>(), named("What is the status of the project?"))
    }

    @Test
    fun valuesOfSeveralWordsAndAccentsMatch() {
        assertEquals(listOf("estado: en curso"), named("¿Qué está en curso? Dime el estado."))
    }

    private val keys = listOf(PropertyKey("priority", "number"), PropertyKey("due", "date"), PropertyKey("vencimiento", "date"))

    /** A Wednesday. */
    private val today = java.time.LocalDate.of(2026, 11, 18)

    private fun compared(message: String) = MemoryQuery.comparisons(message, keys, today).map { "${it.key} ${it.operator} ${it.value}" }

    @Test
    fun numbersAreComparedInBothLanguages() {
        assertEquals(listOf("priority > 2"), compared("tasks with priority above 2"))
        assertEquals(listOf("priority >= 3"), compared("priority at least 3"))
        assertEquals(listOf("priority < 2"), compared("notas con priority menor que 2"))
        assertEquals(listOf("priority > 1.5"), compared("priority mayor a 1,5"))
    }

    @Test
    fun datesAreComparedToADateOrTheNextWeekday() {
        assertEquals(listOf("due < 2026-11-20"), compared("What is due before Friday?"))
        assertEquals(listOf("vencimiento < 2026-11-20"), compared("tareas con vencimiento antes del viernes"))
        assertEquals(listOf("due > 2026-11-25"), compared("due after 2026-11-25"))
        assertEquals(listOf("due <= 2026-11-19"), compared("everything due by tomorrow"))
    }

    @Test
    fun aComparisonNeedsAKnownKeyOfItsType() {
        // "status" is not a number or date property; "priority" with no comparison is equality's job.
        assertEquals(emptyList<String>(), compared("status above 2"))
        assertEquals(emptyList<String>(), compared("what is my priority?"))
    }

    @Test
    fun atMostTwoAreGivenInTheOrderOfTheMessage() {
        assertEquals(listOf("project: Atlas", "status: done"), named("project atlas notes with status done, type meeting"))
    }
}
