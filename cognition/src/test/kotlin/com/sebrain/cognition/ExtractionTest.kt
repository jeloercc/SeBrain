package com.sebrain.cognition

import com.sebrain.cognition.Extraction.Failure
import com.sebrain.cognition.Extraction.Outcome
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtractionTest {
    private val valid =
        """{"people":["Laura Méndez","Diego"],"dates":["2026-11-19T10:30"],"topics":["diseño"],"tasks":["traer bocetos"],"title":"Reunión de diseño"}"""

    @Test
    fun `a complete reply gives its fields`() {
        val outcome = Extraction.parse(valid) as Outcome.Valid

        assertEquals(listOf("Laura Méndez", "Diego"), outcome.fields.people)
        assertEquals("Reunión de diseño", outcome.fields.title)
    }

    @Test
    fun `the spike's broken title counts as empty`() {
        val reply = """{"people":[],"dates":[],"topics":[],"tasks":[],"title":": "}"""

        assertEquals(Outcome.Invalid(Failure.TITLE_EMPTY), Extraction.parse(reply))
    }

    @Test
    fun `quotes and colons around a title are removed`() {
        val reply = """{"people":[],"dates":[],"topics":[],"tasks":[],"title":"\": Factura de la tarjeta"}"""

        assertEquals("Factura de la tarjeta", (Extraction.parse(reply) as Outcome.Valid).fields.title)
    }

    @Test
    fun `a missing key is invalid JSON for the schema`() {
        val reply = """{"people":[],"dates":[],"topics":[],"title":"Sin tareas"}"""

        assertEquals(Outcome.Invalid(Failure.JSON_INVALID), Extraction.parse(reply))
    }

    @Test
    fun `a list with something other than strings is invalid`() {
        val reply = """{"people":[{"name":"Ana"}],"dates":[],"topics":[],"tasks":[],"title":"Ana"}"""

        assertEquals(Outcome.Invalid(Failure.JSON_INVALID), Extraction.parse(reply))
    }

    @Test
    fun `an object that never closes ran out of tokens`() {
        assertEquals(Outcome.Invalid(Failure.TRUNCATED), Extraction.parse("""{"people":["Ana"],"dates":["""))
    }

    @Test
    fun `a reply cut by the whitespace guard is truncated`() {
        assertEquals(Outcome.Invalid(Failure.TRUNCATED), Extraction.parse(valid, cut = true))
    }

    @Test
    fun `blank list items are dropped`() {
        val reply = """{"people":[" ","Ana"],"dates":[],"topics":[],"tasks":[],"title":"Ana llama"}"""

        assertEquals(listOf("Ana"), (Extraction.parse(reply) as Outcome.Valid).fields.people)
    }

    @Test
    fun `the whitespace loop is seen outside strings only`() {
        val loop = "{ \"title\":\": \"" + "\n\t".repeat(20)
        val longString = "{\"tasks\":[\"" + " ".repeat(60) + "\"]"

        assertTrue(Extraction.inWhitespaceLoop(loop))
        assertFalse(Extraction.inWhitespaceLoop(longString))
        assertFalse(Extraction.inWhitespaceLoop(valid))
    }

    @Test
    fun `the message gives the capture date in the user's zone, with the weekday`() {
        val message = Extraction.message("el jueves 14", Instant.parse("2026-11-08T03:24:00Z"), ZoneId.of("America/Mexico_City"))

        assertTrue(message, message.startsWith("Capture date and time: 2026-11-07T21:24:00-06:00 (Saturday)."))
        assertTrue(message.endsWith("el jueves 14"))
    }

    @Test
    fun `the retry message adds the corrective note`() {
        val message = Extraction.message("texto", Instant.parse("2026-11-08T03:24:00Z"), ZoneId.of("UTC"), retry = true)

        assertTrue(message.endsWith(Extraction.RETRY_NOTE))
    }
}
