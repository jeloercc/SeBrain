package com.sebrain.app.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantTest {
    private val coffee = ContextNote(1, 10, "Coffee", listOf("drinks"), listOf("Caffeine"), listOf("Mornings"), "# Coffee\n\nI drink it black.")
    private val caffeine = ContextNote(2, 11, "Caffeine", emptyList(), emptyList(), emptyList(), "A stimulant.", via = "linked from [1]")

    @Test
    fun promptShowsMatchedAndConnectedNotesWithTheirGraph() {
        val prompt = Assistant.prompt("Why do I drink coffee?", listOf(coffee, caffeine), asNote = false, tags = emptyList())
        assertTrue(prompt.contains("[1] Coffee\nTags: #drinks · Links to: Caffeine · Linked from: Mornings\nI drink it black."))
        assertTrue(prompt.contains("Connected notes"))
        assertTrue(prompt.contains("[2] Caffeine (linked from [1])\nA stimulant."))
        assertTrue(prompt.endsWith("Message: Why do I drink coffee?"))
    }

    @Test
    fun notePromptOffersExistingTitlesAndTags() {
        val prompt = Assistant.prompt("make a note about tea", listOf(coffee), asNote = true, tags = listOf("drinks"))
        assertTrue(prompt.contains("Existing notes you may link to: [[Coffee]]"))
        assertTrue(prompt.contains("Tags already in use: drinks"))
    }

    @Test
    fun citationsBecomeLinksAndUnknownLinksBecomeText() {
        val body = "Tea has less caffeine than coffee [1][2]. See [[Matcha]] and [[coffee]]."
        assertEquals(
            "Tea has less caffeine than coffee [[Coffee]] [[Caffeine]]. See Matcha and [[Coffee]].",
            Assistant.linkify(body, listOf(coffee, caffeine), ownTitle = "Tea"),
        )
    }

    @Test
    fun aNoteDoesNotLinkToItself() {
        assertEquals("About coffee.", Assistant.linkify("About coffee [1].", listOf(coffee), ownTitle = "coffee"))
    }

    @Test
    fun shortFollowUpsAreSearchedWithThePreviousQuestion() {
        assertEquals("What is my coffee routine?\nand why?", Assistant.retrievalQuery("and why?", "What is my coffee routine?"))
        val long = "Tell me everything I wrote about the coffee I drink every morning"
        assertEquals(long, Assistant.retrievalQuery(long, "previous"))
        assertEquals("hi", Assistant.retrievalQuery(" hi ", null))
    }

    @Test
    fun noteRequestsAreDetectedInSpanishAndEnglish() {
        assertTrue(Assistant.wantsNote("crea una nota sobre el té"))
        assertTrue(Assistant.wantsNote("save a note about tea"))
        assertFalse(Assistant.wantsNote("what do my notes say about tea?"))
    }

    @Test
    fun parsedNoteKeepsTitleBodyAndTags() {
        val (title, body, tags) = Assistant.parseNote("# Tea\n\nGreen and black.\n\nTags: drinks, tea", "fallback")
        assertEquals("Tea", title)
        assertEquals("Green and black.", body)
        assertEquals(listOf("drinks", "tea"), tags)
    }
}
