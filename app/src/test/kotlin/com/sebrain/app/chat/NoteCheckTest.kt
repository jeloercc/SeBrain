package com.sebrain.app.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteCheckTest {
    @Test
    fun `a clean note passes unchanged`() {
        val checked = NoteCheck.check("Vector search", "Finds text by meaning.\n\n## Key points\n- uses embeddings", listOf("search", "ml"))

        assertEquals("Vector search", checked.title)
        assertEquals("Finds text by meaning.\n\n## Key points\n- uses embeddings", checked.body)
        assertEquals(listOf("search", "ml"), checked.tags)
        assertTrue(checked.fixes.isEmpty())
        assertTrue(checked.warnings.isEmpty())
    }

    @Test
    fun `the title loses heading marks, emphasis and a final full stop`() {
        val checked = NoteCheck.check("# **Vector search.**", "Text.", listOf("search"))

        assertEquals("Vector search", checked.title)
        assertEquals(1, checked.fixes.size)
    }

    @Test
    fun `a long title is kept but warned about`() {
        val checked = NoteCheck.check("How vector search works on a phone today", "Text.", listOf("search"))

        assertEquals("How vector search works on a phone today", checked.title)
        assertTrue(checked.warnings.single().contains("8 words"))
    }

    @Test
    fun `a second top-level heading in the body becomes a section`() {
        val checked = NoteCheck.check("Atlas", "Intro.\n# Details\nMore.", listOf("project"))

        assertEquals("Intro.\n## Details\nMore.", checked.body)
        assertTrue(checked.fixes.isNotEmpty())
    }

    @Test
    fun `leftover Tags and Aliases lines are removed from the body`() {
        val checked = NoteCheck.check("Atlas", "Intro.\n\nTags: project, work\nAliases: none", listOf("project"))

        assertEquals("Intro.", checked.body)
    }

    @Test
    fun `headings inside a code block are left alone`() {
        val body = "Run it:\n```bash\n# install\nTags: x\n```"
        val checked = NoteCheck.check("Setup", body, listOf("tools"))

        assertEquals(body, checked.body)
    }

    @Test
    fun `runs of blank lines collapse to one`() {
        val checked = NoteCheck.check("Atlas", "One.\n\n\n\nTwo.", listOf("project"))

        assertEquals("One.\n\nTwo.", checked.body)
    }

    @Test
    fun `tags are lowercased, stripped of hash marks and repeated ones dropped`() {
        val checked = NoteCheck.check("Atlas", "Text.", listOf("#Project", "Big Ideas", "project", "2026"))

        assertEquals(listOf("project", "big-ideas"), checked.tags)
        assertTrue(checked.fixes.isNotEmpty())
    }

    @Test
    fun `an empty note and a note without tags are warned about`() {
        val checked = NoteCheck.check("Atlas", "  ", emptyList())

        assertEquals(2, checked.warnings.size)
    }

    // The previous answer and the note the Pixel wrote from "the first one" on 2026-11-19.
    private val previous = "Linux is a family of free and open-source Unix-like operating systems based on the Linux kernel. " +
        "Open-source and free to use. Based on the Linux kernel. Highly customizable. Used in servers and embedded systems."
    private val repeated = "Linux is a family of free and open-source Unix-like operating systems based on the Linux kernel. " +
        "Highly customizable. Used in servers and embedded systems. Strong community support."

    @Test
    fun `a draft that mostly copies an earlier text is a repeat of it`() {
        val found = NoteCheck.repeats(repeated, listOf("your previous answer" to previous, "\"Docker\"" to "Containers package software."))

        assertEquals("your previous answer", found)
    }

    @Test
    fun `a draft that explains one point in new words is not a repeat`() {
        val point = "Open-source software publishes its source code under a licence such as the GPL, so anyone can study, " +
            "change and share it. Free here means freedom, and usually no price either. Example: patching a driver yourself."

        assertNull(NoteCheck.repeats(point, listOf("your previous answer" to previous)))
    }

    @Test
    fun `overlap counts the draft's content words found in the other text`() {
        assertEquals(1.0, NoteCheck.overlap("Kernel modules load drivers", "drivers, kernel and modules load"), 0.001)
        assertEquals(0.0, NoteCheck.overlap("", "anything"), 0.001)
    }

    @Test
    fun `a title that is another note's title or alias is a duplicate`() {
        val names = listOf(Triple(1L, "Vector search", "Vector search"), Triple(1L, "Vector search", "Semantic search"), Triple(2L, "Atlas", "Atlas"))

        assertEquals(1L to "Vector search", NoteCheck.duplicateOf("semantic SEARCH", emptyList(), names))
        assertEquals(2L to "Atlas", NoteCheck.duplicateOf("Project", listOf("atlas"), names))
        assertNull(NoteCheck.duplicateOf("Graph", listOf("Network"), names))
    }
}
