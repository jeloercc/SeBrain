package com.sebrain.cognition

import org.junit.Assert.assertEquals
import org.junit.Test

class NoteMarkupTest {
    @Test
    fun findsTagsLikeObsidian() {
        val parsed = NoteMarkup.parse("Reunión #Trabajo y #proyecto/sebrain. Ver #trabajo otra vez, #año_2026 #a-b.")
        assertEquals(listOf("trabajo", "proyecto/sebrain", "año_2026", "a-b"), parsed.tags)
    }

    @Test
    fun ignoresThingsThatLookLikeTags() {
        val text = "Pedido #1234, https://example.com/page#section, a&#39;b, color:#fff, C# y ## Título\n`#codigo` ```\n#bloque\n```"
        assertEquals(emptyList<String>(), NoteMarkup.parse(text).tags)
    }

    @Test
    fun findsLinksWithAliasesAndHeadings() {
        val parsed = NoteMarkup.parse("Ver [[Plan de viaje]], [[Laura Méndez|Laura]] y [[Recetas#Postres]]. Otra vez [[plan de viaje]].")
        assertEquals(listOf("Plan de viaje", "Laura Méndez", "Recetas"), parsed.links)
    }

    @Test
    fun ignoresBrokenLinks() {
        assertEquals(emptyList<String>(), NoteMarkup.parse("[[]] [[ ]] [no] [[sin cerrar").links)
    }

    @Test
    fun titleIsTheSubjectOrTheFirstLine() {
        assertEquals("Asunto", NoteMarkup.titleOf("texto", " Asunto "))
        assertEquals("Plan de viaje", NoteMarkup.titleOf("\n\n# Plan de viaje\nDetalles", null))
        assertEquals("Untitled", NoteMarkup.titleOf("  \n ", ""))
        assertEquals(81, NoteMarkup.titleOf("a".repeat(200)).length)
    }
}
