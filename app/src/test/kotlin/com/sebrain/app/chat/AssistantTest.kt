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
        assertTrue(prompt.startsWith("What the user asked you to always remember:\n- Answer briefly.\n\nNo note from the vault matches"))
        assertTrue(prompt.contains("do not cite any note.\n\nMessage: hola"))
    }

    @Test
    fun anOversizedContextIsCutToFitTheModel() {
        val text = "word ".repeat(400)
        val matched = (1..4).map { ContextNote(it, it.toLong(), "Note $it", emptyList(), emptyList(), emptyList(), text) }
        val connected = (5..8).map { ContextNote(it, it.toLong(), "Note $it", emptyList(), emptyList(), emptyList(), text, via = "linked from [1]") }
        val history = (1..4).map { Assistant.Line(it % 2 == 1, "h".repeat(700)) }
        val memory = Assistant.Memory(history, (1..12).map { "- User asked: " + "o".repeat(180) }, emptyList())
        val prompt = Assistant.prompt("question", matched + connected, asNote = false, tags = emptyList(), memory = memory)
        assertTrue(Assistant.SYSTEM.length + history.sumOf { it.text.length } + prompt.length <= Assistant.INPUT_CHARS)
        // The matching notes survive, shortened; the connected ones are dropped first.
        assertTrue(prompt.contains("[4] Note 4"))
        assertFalse(prompt.contains("Connected notes"))
        assertTrue(prompt.contains("Message: question"))
    }

    @Test
    fun theOutlineKeepsAListAnsweredEarlier() {
        val earlier = listOf(Assistant.Line(true, "List them"), Assistant.Line(false, "They are:\n* Monolithic\n* Exokernel")) +
            (1..6).map { Assistant.Line(it % 2 == 1, "m".repeat(1000)) }
        assertTrue(Assistant.remember(earlier).outline.contains("- You answered: They are: * Monolithic * Exokernel"))
    }

    @Test
    fun pastConversationsAreQuotedWithTheirSource() {
        val past = listOf(PastMessage("Kernels", "2026-11-10T09:00:00Z", fromUser = false, text = "The exokernel\nexposes hardware."))
        val prompt = Assistant.prompt("what did we say about exokernels?", emptyList(), asNote = false, tags = emptyList(), past = past)
        assertTrue(prompt.contains("From earlier conversations with the user:\n- 2026-11-10, \"Kernels\": you answered: The exokernel exposes hardware.\n"))
    }

    @Test
    fun aSummaryIsDueOnceEnoughMessagesLeftTheRecentWindow() {
        val turns = (1..7).flatMap { listOf(Assistant.Line(true, "question $it"), Assistant.Line(false, "answer $it")) }
        // 14 short messages: 6 are recent, 8 are older.
        assertEquals(8, Assistant.summaryDue(turns, summarized = 0))
        assertEquals(null, Assistant.summaryDue(turns, summarized = 2))
        assertEquals(null, Assistant.summaryDue(turns.take(10), summarized = 0))
    }

    @Test
    fun theSummaryReplacesTheMessagesItCovers() {
        val turns = (1..7).flatMap { listOf(Assistant.Line(true, "question $it"), Assistant.Line(false, "answer $it")) }
        val memory = Assistant.remember(turns, summary = "They discussed kernels.", summarized = 6)
        assertEquals(listOf("- User asked: question 4", "- You answered: answer 4"), memory.outline)
        val prompt = Assistant.prompt("next", emptyList(), asNote = false, tags = emptyList(), memory = memory)
        assertTrue(prompt.startsWith("Summary of this conversation so far:\nThey discussed kernels.\n\nAfter that:\n- User asked: question 4"))
        // Without a summary the same messages stay in the outline.
        assertEquals(8, Assistant.remember(turns, summary = null, summarized = 6).outline.size)
    }

    @Test
    fun theSummaryRequestCarriesThePreviousSummaryAndTheNewMessages() {
        val request = Assistant.summaryRequest("They listed kernel types.", listOf(Assistant.Line(true, "now the\nexokernel"), Assistant.Line(false, "# Exokernel\n\nIt exposes hardware.")))
        assertTrue(request.contains("Summary so far:\nThey listed kernel types.\n\nWhat came next:\nUser: now the exokernel\nAssistant: # Exokernel It exposes hardware.\n"))
    }

    @Test
    fun suggestedFactsAreParsedAndKnownOnesAreLeftOut() {
        val reply = "Here are the facts:\n- Su servidor se llama atlas.\n* Prefiere Docker a Podman\n1. ok\n- su servidor se llama Atlas\n- NONE\n- Usa Debian 12 en atlas\n- A fourth fact that is long enough"
        assertEquals(
            listOf("Su servidor se llama atlas.", "Usa Debian 12 en atlas", "A fourth fact that is long enough"),
            Assistant.parseFacts(reply, exclude = listOf("prefiere docker a podman")),
        )
        assertTrue(Assistant.parseFacts("NONE", emptyList()).isEmpty())
    }

    @Test
    fun theFactsRequestUsesOnlyWhatTheUserWrote() {
        val request = Assistant.factsRequest(
            listOf(Assistant.Line(true, "mi servidor se llama atlas"), Assistant.Line(false, "Perfecto, atlas.")),
            known = listOf("Prefiere respuestas cortas"),
        )
        assertTrue(request.contains("Already remembered, do not repeat:\n- Prefiere respuestas cortas\n"))
        assertTrue(request.endsWith("Messages:\n- mi servidor se llama atlas\n"))
        assertFalse(request.contains("Perfecto"))
    }

    @Test
    fun citationsToNotesThatWereNotGivenAreRemoved() {
        assertEquals(
            "Una IP identifica un dispositivo. Sirve para dirigir datos.",
            Assistant.dropUnknownCitations("Una IP identifica un dispositivo [1]. Sirve para dirigir datos [1], [3].", count = 0),
        )
        // With two notes, [1] and [2] stay and [3] goes.
        assertEquals("Uno [1], dos [2] y tres.", Assistant.dropUnknownCitations("Uno [1], dos [2] y tres [3].", count = 2))
        // Indexes in code are not citations.
        val code = "Usa `lista[0]` así:\n```python\nprint(lista[0])\n```\nFin [2]."
        assertEquals("Usa `lista[0]` así:\n```python\nprint(lista[0])\n```\nFin.", Assistant.dropUnknownCitations(code, count = 1))
    }
}
