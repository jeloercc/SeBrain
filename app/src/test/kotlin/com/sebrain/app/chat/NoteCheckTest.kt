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

    @Test
    fun `a title that is another note's title or alias is a duplicate`() {
        val names = listOf(Triple(1L, "Vector search", "Vector search"), Triple(1L, "Vector search", "Semantic search"), Triple(2L, "Atlas", "Atlas"))

        assertEquals(1L to "Vector search", NoteCheck.duplicateOf("semantic SEARCH", emptyList(), names))
        assertEquals(2L to "Atlas", NoteCheck.duplicateOf("Project", listOf("atlas"), names))
        assertNull(NoteCheck.duplicateOf("Graph", listOf("Network"), names))
    }
}
