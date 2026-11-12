package com.sebrain.cognition

import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownInteropTest {
    @Test
    fun fileNameSanitizesTitleAndAddsMarkdownExtension() {
        assertEquals("Project_ Alpha.md", MarkdownInterop.fileName(" Project: Alpha "))
        assertEquals("Existing.md", MarkdownInterop.fileName("Existing.md"))
        assertEquals("Untitled.md", MarkdownInterop.fileName(" : "))
    }

    @Test
    fun importUsesFilenameAsSubjectAndParsesExistingMarkup() {
        val imported = MarkdownInterop.importDocument(
            "Travel Plans.md",
            "# Heading\n\n#trip [[Packing List]]",
        )

        assertEquals("Travel Plans", imported.subject)
        assertEquals(listOf("trip"), imported.markup.tags)
        assertEquals(listOf("Packing List"), imported.markup.links)
    }
}
