package com.sebrain.cognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChunkHeaderTest {
    private val note = "# Protocolo SSH\n\nPermite conectarse de forma segura.\n\n## Detalles\n\n### Puertos\nUsa el puerto 22 por defecto.\n\n" +
        "```bash\n# no es un título\nssh host\n```\n\n## Historia\nNació en 1995.\n"

    private fun header(marker: String) = ChunkHeader.of(note, "Protocolo SSH", listOf("SSH"), listOf("redes", "seguridad"), note.indexOf(marker))

    @Test
    fun aChunkCarriesItsNoteItsSectionAndItsTags() {
        assertEquals("[Protocolo SSH · also: SSH · Detalles › Puertos · #redes #seguridad]", header("Usa el puerto"))
        assertEquals("[Protocolo SSH · also: SSH · Historia · #redes #seguridad]", header("Nació"))
    }

    @Test
    fun theTitleHeadingIsNotRepeatedAndCodeCommentsAreNotHeadings() {
        assertEquals("[Protocolo SSH · also: SSH · #redes #seguridad]", header("Permite"))
        assertEquals("[Protocolo SSH · also: SSH · Detalles › Puertos · #redes #seguridad]", header("ssh host"))
        // A heading that opens the chunk is in its text already.
        assertEquals("[Protocolo SSH · also: SSH · Detalles › Puertos · #redes #seguridad]", header("## Historia"))
    }

    @Test
    fun aNoteWithoutAnythingElseStillNamesItself() {
        assertEquals("[Lista de la compra]", ChunkHeader.of("leche\npan", "Lista de la compra", emptyList(), emptyList(), 0))
        assertEquals("[Untitled]", ChunkHeader.of("texto", " ", emptyList(), emptyList(), 0))
    }

    @Test
    fun aLongHeaderIsCut() {
        val long = ChunkHeader.of("x", "T".repeat(300), emptyList(), listOf("a"), 0)
        assertTrue(long.length <= 164 && long.endsWith("…]"))
    }
}
