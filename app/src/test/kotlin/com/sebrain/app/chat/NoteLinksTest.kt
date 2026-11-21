package com.sebrain.app.chat

import com.sebrain.cognition.NoteMarkup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteLinksTest {
    // (note id, title, name): every title and alias of the brain, as NoteStore.names() returns them.
    private val names = listOf(
        Triple(1L, "Kernel", "Kernel"),
        Triple(2L, "Open source", "Open source"),
        Triple(2L, "Open source", "FOSS"),
        Triple(3L, "Linux", "Linux"),
    )

    @Test
    fun `a new note links the existing notes it names`() {
        val (text, added) = NoteLinks.outgoing("Linux is built on a kernel and is open source.", "Distributions", names)

        assertEquals(3, added)
        assertEquals(setOf("kernel", "open source", "linux"), NoteMarkup.parse(text).links.map { it.lowercase() }.toSet())
    }

    @Test
    fun `an alias links to its note`() {
        val (text, added) = NoteLinks.outgoing("Most FOSS licences allow changes.", "Licences", names)

        assertEquals(1, added)
        assertTrue(NoteMarkup.parse(text).links.any { it.equals("Open source", ignoreCase = true) })
    }

    @Test
    fun `a note never links to itself`() {
        val (text, added) = NoteLinks.outgoing("Linux is a kernel.", "Linux", names)

        assertEquals(1, added)
        assertTrue(NoteMarkup.parse(text).links.none { it.equals("Linux", ignoreCase = true) })
    }

    @Test
    fun `a text without names is left as it is`() {
        val body = "Shells read commands."

        assertEquals(body to 0, NoteLinks.outgoing(body, "Shell", names))
    }

    @Test
    fun `an existing note that names the new one gets a link to it`() {
        val changed = NoteLinks.backlinked("We deploy every service on Linux servers.", "Linux", emptyList())

        assertTrue(NoteMarkup.parse(changed!!).links.any { it.equals("Linux", ignoreCase = true) })
    }

    @Test
    fun `an alias of the new note is linked too`() {
        val changed = NoteLinks.backlinked("Our stack is FOSS only.", "Open source", listOf("FOSS"))

        assertTrue(NoteMarkup.parse(changed!!).links.any { it.equals("Open source", ignoreCase = true) })
    }

    @Test
    fun `a note that already links the new one is not changed`() {
        assertNull(NoteLinks.backlinked("See [[Linux]] for details. Linux is fast.", "Linux", emptyList()))
    }
}
