package com.sebrain.app.chat

import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TemplatesTest {
    private val now = LocalDateTime.of(2026, 11, 18, 9, 5)
    private val meeting = Templates.NoteTemplate(
        "Meeting",
        "---\ntype: meeting\ndate: \"{{date}}\"\ntags: [work]\nstatus: open\n---\n# {{title}}\n\n## Attendees\n\n## Decisions\n\n## Next steps\nWritten {{date:DD/MM/YYYY}} at {{time}}.",
    )
    private val reunion = Templates.NoteTemplate("Reunión de equipo", "## Asistentes\n\n## Acuerdos")

    @Test
    fun theTemplateTheRequestNamesIsChosen() {
        assertEquals("Meeting", Templates.chosen("Make a meeting note about the budget", listOf(meeting, reunion))?.title)
        assertEquals("Reunión de equipo", Templates.chosen("haz una nota de la reunion de equipo de hoy", listOf(meeting, reunion))?.title)
        assertNull(Templates.chosen("Make a note about TCP", listOf(meeting, reunion)))
    }

    @Test
    fun obsidianVariablesAreFilledIn() {
        assertEquals(
            "Budget 2026-11-18 09:05 18/11/2026 {{unknown}}",
            Templates.fill("{{title}} {{date}} {{time}} {{date:DD/MM/YYYY}} {{unknown}}", "Budget", now),
        )
    }

    @Test
    fun theModelGetsTheStructureWithoutTheProperties() {
        val structure = Templates.structure(meeting, now)
        assertEquals("# Title of the topic\n\n## Attendees\n\n## Decisions\n\n## Next steps\nWritten 18/11/2026 at 09:05.", structure)
    }

    @Test
    fun theSavedNoteGetsTheTemplatesPropertiesFilledIn() {
        // Tags and aliases come from the note itself; the rest is copied.
        assertEquals(
            listOf("type" to listOf("meeting"), "date" to listOf("2026-11-18"), "status" to listOf("open")),
            Templates.properties(meeting, "Budget", now),
        )
    }
}
