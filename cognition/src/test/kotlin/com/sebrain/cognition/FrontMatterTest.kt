package com.sebrain.cognition

import com.sebrain.cognition.FrontMatter.Type
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FrontMatterTest {
    private val note = """
        ---
        title: "TCP: el protocolo"
        aliases: [TCP, "Transmission Control Protocol"]
        tags:
          - redes
          - redes/protocolos
        status: active
        port: 443
        created: 2026-11-12
        done: false
        ---
        # TCP

        Texto de la nota.
    """.trimIndent()

    @Test
    fun readsScalarsListsAndTypes() {
        val parsed = FrontMatter.parse(note)
        assertNull(parsed.problem)
        assertEquals(listOf("title", "aliases", "tags", "status", "port", "created", "done"), parsed.properties.map { it.key })
        assertEquals(FrontMatter.Property("title", Type.TEXT, listOf("TCP: el protocolo")), parsed["title"])
        assertEquals(listOf("TCP", "Transmission Control Protocol"), parsed.valuesOf("aliases", "alias"))
        assertEquals(listOf("redes", "redes/protocolos"), parsed["tags"]!!.values)
        assertEquals(Type.LIST, parsed["tags"]!!.type)
        assertEquals(Type.NUMBER to listOf<Double?>(443.0), parsed["port"]!!.let { it.type to it.numbers })
        assertEquals(Type.DATE, parsed["created"]!!.type)
        assertEquals(java.time.LocalDate.of(2026, 11, 12).toEpochDay().toDouble(), parsed["created"]!!.numbers.single())
        assertEquals(Type.BOOL to listOf<Double?>(0.0), parsed["done"]!!.let { it.type to it.numbers })
        assertEquals(listOf<Double?>(null), parsed["status"]!!.numbers)
    }

    @Test
    fun theBodyStartsAfterTheBlock() {
        assertEquals("# TCP\n\nTexto de la nota.", FrontMatter.body(note))
        assertEquals("Sin propiedades", FrontMatter.body("Sin propiedades"))
        assertEquals(0, FrontMatter.parse("--- no es un bloque\ntexto").bodyStart)
        // A block that is never closed is ordinary text.
        assertEquals(0, FrontMatter.parse("---\nkey: value\ntexto sin cierre").bodyStart)
        // A rule in the middle of a note is not front matter.
        assertEquals(0, FrontMatter.parse("Texto\n---\nkey: value\n---\n").bodyStart)
    }

    @Test
    fun aQuotedNumberIsTextAndCommentsAreIgnored() {
        val parsed = FrontMatter.parse("---\n# comentario\nversion: \"22\"\nport: 22 # ssh\nname: 'O''Brien'\n---\n")
        assertEquals(Type.TEXT, parsed["version"]!!.type)
        assertEquals(listOf<Double?>(null), parsed["version"]!!.numbers)
        assertEquals(FrontMatter.Property("port", Type.NUMBER, listOf("22")), parsed["port"])
        assertEquals("O'Brien", parsed["name"]!!.values.single())
    }

    @Test
    fun whatItCannotReadIsReportedAndTheRestIsKept() {
        val parsed = FrontMatter.parse("---\nok: yes\nesto no es yaml\nnested:\n  inner: 1\ntext: |\n  varias\n  lineas\nlast: fin\n---\nCuerpo")
        assertEquals(listOf("ok", "last"), parsed.properties.map { it.key })
        assertNotNull(parsed.problem)
        assertTrue(parsed.problem!!.contains("esto no es yaml"))
        assertEquals("Cuerpo", "---\nok: yes\nesto no es yaml\nnested:\n  inner: 1\ntext: |\n  varias\n  lineas\nlast: fin\n---\nCuerpo".substring(parsed.bodyStart))
    }

    @Test
    fun emptyValuesAndUnicodeKeys() {
        val parsed = FrontMatter.parse("---\nvacío:\nárea: Diseño\nlista: []\n---\n")
        assertEquals(listOf(""), parsed["vacío"]!!.values)
        assertEquals("Diseño", parsed["área"]!!.values.single())
        assertEquals(emptyList<String>(), parsed["lista"]!!.values)
    }

    @Test
    fun renderedBlocksAreReadBack() {
        val block = FrontMatter.render(listOf("source" to listOf("assistant"), "tags" to listOf("redes", "a: b"), "created" to listOf("2026-11-12")))
        val parsed = FrontMatter.parse(block + "Texto")
        assertEquals(listOf("assistant"), parsed["source"]!!.values)
        assertEquals(listOf("redes", "a: b"), parsed["tags"]!!.values)
        assertEquals(Type.DATE, parsed["created"]!!.type)
        assertEquals("Texto", (block + "Texto").substring(parsed.bodyStart))
    }
}
