package com.sebrain.app.chat

import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DailyNotesTest {
    private val today = LocalDate.of(2026, 11, 18)
    private val time = LocalTime.of(9, 5)

    @Test
    fun aRequestToSaveTheLastAnswerHasNoTextOfItsOwn() {
        assertEquals(DailyNotes.Request(null), DailyNotes.request("Save this in today's note"))
        assertEquals(DailyNotes.Request(null), DailyNotes.request("apúntalo en la nota de hoy"))
        assertEquals(DailyNotes.Request(null), DailyNotes.request("guarda esto en mi nota diaria"))
    }

    @Test
    fun theTextAfterAColonIsWhatGetsSaved() {
        assertEquals(DailyNotes.Request("bought milk"), DailyNotes.request("Add to my daily note: bought milk"))
        assertEquals(DailyNotes.Request("llamar a Ana"), DailyNotes.request("añade a la nota de hoy: llamar a Ana"))
    }

    @Test
    fun aQuestionAboutTheNoteOrAnotherNoteIsNotARequest() {
        assertNull(DailyNotes.request("What is in today's note?"))
        assertNull(DailyNotes.request("Make a note about TCP"))
        assertNull(DailyNotes.request("save this as a note"))
    }

    @Test
    fun anEntryIsAppendedUnderItsTime() {
        assertEquals(
            "# 2026-11-18\n\nMorning walk.\n\n### 09:05\n\nBought milk\n",
            DailyNotes.appended("# 2026-11-18\n\nMorning walk.\n", "Bought milk", time),
        )
    }

    @Test
    fun aNewDailyNoteStartsFromTheDailyTemplateOrTheDate() {
        assertEquals("# 2026-11-18\n\n### 09:05\n\nBought milk\n", DailyNotes.newNote(today, null, "Bought milk", time))
        val template = Templates.NoteTemplate("Daily", "---\ntype: daily\n---\n# {{date}}\n\n## Log")
        assertEquals(
            "---\ntype: daily\n---\n# 2026-11-18\n\n## Log\n\n### 09:05\n\nBought milk\n",
            DailyNotes.newNote(today, template, "Bought milk", time),
        )
    }

    @Test
    fun theDailyNoteIsNamedByItsDateInsideTheBrain() {
        assertEquals("Daily/2026-11-18", DailyNotes.path(today, null))
        assertEquals("Linux/Daily/2026-11-18", DailyNotes.path(today, "Linux"))
    }
}
