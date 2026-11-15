package com.sebrain.app.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PinsTest {
    @Test
    fun pinningAddsTheTagOnceAndUnpinningRemovesOnlyIt() {
        val note = "# Estilo\n\nResponde en una frase.\n\n#preferencias"
        val pinned = Pins.pin(note)
        assertEquals("# Estilo\n\nResponde en una frase.\n\n#preferencias\n\n#pinned", pinned)
        assertTrue(Pins.isPinned(pinned))
        assertEquals(pinned, Pins.pin(pinned))
        assertEquals(note, Pins.unpin(pinned))
        assertFalse(Pins.isPinned(Pins.unpin(pinned)))
    }

    @Test
    fun unpinningKeepsOtherTagsOnTheSameLineAndSimilarWords() {
        assertEquals("Texto\n\n#a #b", Pins.unpin("Texto\n\n#a #pinned #b"))
        // #pinned-notes is another tag, and the word in prose is not a tag.
        assertEquals("About #pinned-notes and pinned things", Pins.unpin("About #pinned-notes and pinned things"))
        assertFalse(Pins.isPinned("See `#pinned` in code"))
    }

    @Test
    fun theOldMemoryTagBecomesThePin() {
        assertEquals("# Memory: atlas\n\nMi servidor se llama atlas\n\n#pinned", Pins.migrate("# Memory: atlas\n\nMi servidor se llama atlas\n\n#memory"))
        assertEquals("Texto\n\n#pinned", Pins.migrate("Texto\n\n#memory #pinned"))
        assertEquals(null, Pins.migrate("A note about #memories and memory"))
    }

    @Test
    fun aPinnedNoteIsReadAsOneLineWithoutItsHeadingOrPin() {
        assertEquals("Estilo: Responde en una frase. #preferencias", Pins.line("Estilo", "# Estilo\n\nResponde en una frase.\n\n#preferencias\n\n#pinned"))
        // Notes made by the old "remember" command are titled "Memory: <start of the fact>".
        assertEquals("Mi servidor se llama atlas", Pins.line("Memory: Mi servidor se …", "# Memory: Mi servidor se …\n\nMi servidor se llama atlas\n\n#pinned"))
    }

    @Test
    fun pinnedNotesShareOneBudgetAndTheRestAreCounted() {
        val long = "palabra ".repeat(100)
        val (kept, omitted) = Pins.fit(listOf(long, long, long, "corta"))
        // 600 + 600 fill the 1200 characters; the third and fourth do not fit.
        assertEquals(2, kept.size)
        assertEquals(2, omitted)
        assertTrue(kept.sumOf { it.length } <= Pins.BUDGET_CHARS)
        assertEquals(listOf("uno", "dos") to 0, Pins.fit(listOf("uno", "", "dos")))
    }

    @Test
    fun manyLongPinnedNotesNeverBreakThePromptBudget() {
        val pinned = List(10) { "nota fijada muy larga ".repeat(60) }
        val notes = (1..4).map { ContextNote(it, it.toLong(), "Note $it", emptyList(), emptyList(), emptyList(), "word ".repeat(400)) }
        val prompt = Assistant.prompt("question", notes, asNote = false, tags = emptyList(), pinned = pinned)
        assertTrue(Assistant.SYSTEM.length + prompt.length <= Assistant.INPUT_CHARS)
    }
}
