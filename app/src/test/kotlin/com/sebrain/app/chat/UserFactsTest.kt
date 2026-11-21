package com.sebrain.app.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UserFactsTest {
    @Test
    fun `statements about the user are facts, questions are not`() {
        assertEquals("Me llamo Julián", UserFacts.detect("me llamo Julián")?.line)
        assertEquals("my dentista", UserFacts.detect("mi dentista es la Dra. Ruiz")?.key)
        assertEquals("I park on level 3", UserFacts.detect("Remember that I park on level 3.")?.line)
        assertNull(UserFacts.detect("¿cuál es mi casillero?"))
        assertNull(UserFacts.detect("what is my locker?"))
        assertNull(UserFacts.detect("¿Quién fue el primer fotógrafo?"))
    }

    @Test
    fun `a newer fact about the same thing replaces the older one`() {
        val first = UserFacts.merge(null, UserFacts.detect("mi casillero es el 117")!!)
        val second = UserFacts.merge(first, UserFacts.detect("mi casillero es el 204")!!)

        assertTrue(second, second.contains("casillero es el 204"))
        assertTrue(!second.contains("117"))
        assertTrue(Pins.isPinned(second))
    }

    @Test
    fun `different facts are kept, the same one is not repeated, and keys never reach the model`() {
        var note = UserFacts.merge(null, UserFacts.detect("me llamo Julián")!!)
        note = UserFacts.merge(note, UserFacts.detect("soy alérgico a la penicilina")!!)
        note = UserFacts.merge(note, UserFacts.detect("soy alérgico a la penicilina")!!)

        assertEquals(2, note.lines().count { it.startsWith("- ") })
        val line = Pins.line(UserFacts.TITLE, note)
        assertTrue(line, line.contains("Me llamo Julián") && line.contains("penicilina") && !line.contains("<!--"))
    }
}
