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
        assertTrue(prompt.contains("Message: Why do I drink coffee?\n\nWrite in the language of the message above"))
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

    @Test
    fun memoryKeepsRecentMessagesAndOutlinesOlderOnes() {
        val long = "x ".repeat(700)
        val earlier = listOf(
            Assistant.Line(true, "List the kernel types"),
            Assistant.Line(false, "Monolithic, microkernel, hybrid, exokernel."),
            Assistant.Line(true, "make a note of the first"),
            Assistant.Line(false, "# Monolithic Kernel\n\n$long"),
            Assistant.Line(true, "now the next one"),
            Assistant.Line(false, "# Microkernel\n\n$long"),
            Assistant.Line(true, "and the next"),
            Assistant.Line(false, "# Hybrid Kernel\n\n$long"),
        )
        val memory = Assistant.remember(earlier)
        // The budget holds the last two notes, shortened, and the requests before them.
        assertEquals(listOf(true, false, true, false), memory.recent.map { it.fromUser })
        assertEquals("now the next one", memory.recent.first().text)
        assertTrue(memory.recent.all { it.text.length <= Assistant.HISTORY_CHARS + 2 })
        assertEquals(
            listOf(
                "- User asked: List the kernel types",
                "- You answered: Monolithic, microkernel, hybrid, exokernel.",
                "- User asked: make a note of the first",
                "- You answered: Monolithic Kernel",
            ),
            memory.outline,
        )
        assertEquals(listOf("Monolithic Kernel", "Microkernel", "Hybrid Kernel"), memory.covered)

        val prompt = Assistant.prompt("haz la nota del siguiente", emptyList(), asNote = true, tags = emptyList(), memory = memory)
        assertTrue(prompt.contains("Already written in this conversation: Monolithic Kernel; Microkernel; Hybrid Kernel. Do not write any of them again."))
        assertTrue(prompt.contains("- You answered: Monolithic, microkernel, hybrid, exokernel."))
    }

    @Test
    fun historyStartsWithTheUser() {
        val memory = Assistant.remember(listOf(Assistant.Line(false, "hello"), Assistant.Line(true, "hi"), Assistant.Line(false, "yes")))
        assertEquals(listOf("hi", "yes"), memory.recent.map { it.text })
    }

    @Test
    fun factsToRememberAreRecognisedButQuestionsAreNot() {
        assertEquals("prefiero respuestas cortas", Assistant.factToRemember("Recuerda que prefiero respuestas cortas"))
        assertEquals("my server is called atlas", Assistant.factToRemember("remember that my server is called atlas"))
        assertEquals(null, Assistant.factToRemember("¿recuerdas que hablamos de kernels?"))
        assertEquals(null, Assistant.factToRemember("explícame qué recuerda un kernel"))
    }

    @Test
    fun standingFactsOpenThePrompt() {
        val prompt = Assistant.prompt("hola", emptyList(), asNote = false, tags = emptyList(), facts = listOf("Answer briefly."))
        assertTrue(prompt.startsWith("What the user asked you to always remember:\n- Answer briefly.\n\nMessage: hola"))
    }
}
