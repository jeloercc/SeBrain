package com.sebrain.cognition

import org.junit.Assert.assertEquals
import org.junit.Test

class NoteMarkupTest {
    @Test
    fun findsTagsLikeObsidian() {
        val parsed = NoteMarkup.parse("Reunión #Trabajo y #proyecto/sebrain. Ver #trabajo otra vez, #año_2026 #a-b.")
        // A nested tag is also found under its parent.
        assertEquals(listOf("trabajo", "proyecto/sebrain", "proyecto", "año_2026", "a-b"), parsed.tags)
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

    @Test
    fun linksKeepTheirAnchorAndWhetherTheyEmbed() {
        val parsed = NoteMarkup.parse("![[Diagrama]] y [[Recetas#Postres]], [[Recetas#^bloque1|ver]], [[Recetas]], [[#Aquí mismo]].")
        assertEquals(
            listOf(
                NoteMarkup.Link("Diagrama", "", embed = true),
                NoteMarkup.Link("Recetas", "Postres"),
                NoteMarkup.Link("Recetas", "^bloque1"),
                NoteMarkup.Link("Recetas"),
            ),
            parsed.linkDetails,
        )
        assertEquals(listOf("Diagrama", "Recetas"), parsed.links)
    }

    @Test
    fun frontMatterGivesTagsAliasesAndPropertiesAndIsNotReadAsText() {
        val text = "---\ntags: [Redes, '#redes/tcp']\naliases: [TCP]\nstatus: active\nnote: \"#notatag [[NoLink]]\"\n---\n# Protocolo\n\nUsa #transporte y [[IP]]."
        val parsed = NoteMarkup.parse(text)
        assertEquals(listOf("transporte", "redes", "redes/tcp"), parsed.tags)
        assertEquals(listOf("TCP"), parsed.aliases)
        assertEquals(listOf("IP"), parsed.links)
        assertEquals(listOf("tags", "aliases", "status", "note"), parsed.properties.map { it.key })
        assertEquals("Protocolo", NoteMarkup.titleOf(text))
    }

    @Test
    fun frontMatterIsNotChunked() {
        val text = "---\nstatus: active\n---\nPrimer párrafo.\n\nSegundo párrafo."
        val chunks = Chunker.chunk(text)
        assertEquals(listOf("Primer párrafo.\n\nSegundo párrafo."), chunks.map { it.text })
        assertEquals(text.indexOf("Primer"), chunks.single().startOffset)
    }
}
