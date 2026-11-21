package com.sebrain.app.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SuggestionsTest {
    private fun note(number: Int, id: Long, title: String) = ContextNote(number, id, title, emptyList(), emptyList(), emptyList(), "")

    @Test
    fun `sources are the cited notes once each in order`() {
        val notes = listOf(note(1, 10, "Dentista"), note(2, 20, "Seguro"), note(3, 30, "Viaje"))

        assertEquals(listOf(20L to "Seguro", 10L to "Dentista"), Suggestions.sources("Es el jueves [2], con la Dra. [1] y [2].", notes))
        assertEquals(emptyList<Pair<Long, String>>(), Suggestions.sources("Sin citas.", notes))
    }

    @Test
    fun `suggestions follow the language and are understood by the app`() {
        val spanish = Suggestions.next(Route.GENERAL, "¿quién fue el primer fotógrafo?", "Fue Niépce.", cited = false)
        val english = Suggestions.next(Route.GENERAL, "who was the first photographer?", "It was Niépce.", cited = false)

        assertEquals("Cuéntame más", spanish.first())
        assertEquals("Tell me more", english.first())
        // The note suggestion must reach the note flow, not be answered as a question.
        assertTrue(Routing.isAction(spanish[1]))
        assertTrue(Routing.isAction(english[1]))
    }

    @Test
    fun `no suggestions after small talk, an action or an empty reply`() {
        assertEquals(emptyList<String>(), Suggestions.next(Route.CHAT, "hola", "¡Hola!", cited = false))
        assertEquals(emptyList<String>(), Suggestions.next(Route.ACTION, "haz una nota", "listo", cited = false))
        assertEquals(emptyList<String>(), Suggestions.next(Route.GENERAL, "x", " ", cited = false))
    }
}
