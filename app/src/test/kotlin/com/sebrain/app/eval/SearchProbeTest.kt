package com.sebrain.app.eval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchProbeTest {
    @Test
    fun theQuestionIsTheFirstLineWithoutLabelsOrQuotes() {
        assertEquals("How do I renew the certificate?", SearchProbe.question("Question: \"How do I renew the certificate?\"\n\nIt is in the note."))
        assertEquals("¿Cuándo vence el seguro?", SearchProbe.question("\n**Pregunta:** ¿Cuándo vence el seguro?"))
        assertEquals("What port does the server use?", SearchProbe.question("1. What port does the server use"))
    }

    @Test
    fun aReplyWithoutAQuestionGivesNothing() {
        assertNull(SearchProbe.question("   \n  "))
        assertNull(SearchProbe.question("OK."))
    }

    @Test
    fun overlapIsTheShareOfTheQuestionsWordsFoundInTheNote() {
        // "renew" and "certificate" are in the note; "expired" is not. Short words do not count.
        val note = "To renew the certificate, run certbot."
        assertEquals(2.0 / 3, SearchProbe.overlap("How do I renew an expired certificate?", note), 1e-9)
        assertEquals(0.0, SearchProbe.overlap("Why is it so?", note), 1e-9)
    }

    @Test
    fun overlapIgnoresCaseAndAccents() {
        assertEquals(1.0, SearchProbe.overlap("¿Qué CANCIÓN?", "la cancion que"), 1e-9)
    }

    @Test
    fun aQuestionUsesTheTitleWhenItHasOneOfItsWords() {
        assertTrue(SearchProbe.usesTitle("How do I renew a certificate?", "Certificate renewal"))
        assertFalse(SearchProbe.usesTitle("How do I keep the site on HTTPS?", "Certificate renewal"))
    }

    @Test
    fun recallAndMrrCountMissesAsZero() {
        val summary = SearchProbe.summarize(listOf(1, 3, null, 8))
        assertEquals(0.25, summary.recallAt1, 1e-9)
        assertEquals(0.5, summary.recallAt5, 1e-9)
        assertEquals(0.75, summary.recallAt8, 1e-9)
        assertEquals((1.0 + 1.0 / 3 + 1.0 / 8) / 4, summary.mrr, 1e-9)
    }
}
