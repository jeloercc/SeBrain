package com.sebrain.app.chat

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

    @Test
    fun atMostTwoAreGivenInTheOrderOfTheMessage() {
        assertEquals(listOf("project: Atlas", "status: done"), named("project atlas notes with status done, type meeting"))
    }
}
