package com.sebrain.cognition

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

/** Captured on Saturday 7 November 2026, like ExtractionEvalTest. */
class DateMentionsTest {
    private val captured = LocalDate.of(2026, 11, 7)

    private fun first(text: String) = DateMentions.resolve(text, captured).firstOrNull()?.toString()

    @Test
    fun `the benchmark captures resolve to the expected day`() {
        val cases = mapOf(
            "la reunión con diseño pasa al jueves a las 10:30" to "2026-11-12",
            "su factura vence el 28 de noviembre" to "2026-11-28",
            "cita con la dentista mañana a las 17:15" to "2026-11-08",
            "el sábado 21 subimos a La Pedriza" to "2026-11-21",
            "se retrasa al lunes 16" to "2026-11-16",
            "antes del 20 de noviembre" to "2026-11-20",
            "the Heron review moved to next Friday at 3 pm" to "2026-11-13",
            "dinner at Marco's place tomorrow at 8" to "2026-11-08",
            "appointment with Dr. Okafor on the 20th at 9:00" to "2026-11-20",
            "flight to Lisbon on March 14, TP1023" to "2027-03-14",
            "renovar el pasaporte antes del 5 de enero" to "2027-01-05",
            "¿me devuelves el libro el miércoles?" to "2026-11-11",
            "the plumber comes on Monday morning" to "2026-11-09",
            "comemos en su casa el 3 de diciembre a las 14:00" to "2026-12-03",
            "pásame los colores del logo antes del martes" to "2026-11-10",
            "parents' meeting with Ms. Johnson on November 25 at 6 pm" to "2026-11-25",
            "no olvides pagar el alquiler el 1 de diciembre" to "2026-12-01",
            "I land in Madrid on the 18th at 7:40" to "2026-11-18",
            "housewarming party on Saturday the 28th" to "2026-11-28",
            // A day before the capture: a recent event, not next year's.
            "transferencia recibida de Lucas Pérez el 6 de noviembre" to "2026-11-06",
            "the contract must be signed by December 10" to "2026-12-10",
        )
        for ((text, expected) in cases) assertEquals(text, expected, first(text))
    }

    @Test
    fun `a text with no date gives none`() {
        assertEquals(emptyList<LocalDate>(), DateMentions.resolve("Lista de la compra: tomate, arroz y laurel.", captured))
        assertEquals(emptyList<LocalDate>(), DateMentions.resolve("May I borrow the map?", captured))
    }

    @Test
    fun `quantities after el or the are not days`() {
        assertEquals(emptyList<LocalDate>(), DateMentions.resolve("reducción del 30 % en nitratos", captured))
        assertEquals(emptyList<LocalDate>(), DateMentions.resolve("the 8 km run", captured))
    }

    @Test
    fun `a stated year is kept`() {
        assertEquals("2025-03-14", first("el 14 de marzo de 2025"))
    }

    @Test
    fun `several dates come in text order without repeats`() {
        val dates = DateMentions.resolve("mañana y el domingo, y luego el martes", captured).map { it.toString() }

        // "mañana" is Sunday the 8th, the same day as "el domingo".
        assertEquals(listOf("2026-11-08", "2026-11-10"), dates)
    }

    @Test
    fun `an impossible day is left out`() {
        assertEquals(emptyList<LocalDate>(), DateMentions.resolve("el 31 de febrero", captured))
    }
}
