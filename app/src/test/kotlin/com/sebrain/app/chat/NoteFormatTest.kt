package com.sebrain.app.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteFormatTest {
    @Test
    fun aCleanNoteKeepsTitleBodyAndTags() {
        val note = NoteFormat.parse("# Tea\n\nA drink.\n\n## Key points\n- Green\n- Black\n\nTags: drinks, tea", "fallback").single()
        assertEquals(DraftNote("Tea", "A drink.\n\n## Key points\n- Green\n- Black", listOf("drinks", "tea")), note)
    }

    @Test
    fun anIntroductionAndAClosingQuestionAreDropped() {
        val reply = "El siguiente kernel que falta es el **Exokernel**.\n\n# Exokernel\n\nExpone el hardware.\n\n" +
            "Tags: kernel, os\n\n¿Quieres que haga otra nota?"
        assertEquals(listOf(DraftNote("Exokernel", "Expone el hardware.", listOf("kernel", "os"))), NoteFormat.parse(reply, "fallback"))
    }

    @Test
    fun severalNotesAreSplitAtTheirTitles() {
        val reply = "# UDP\n\nSin conexión.\n\nTags: redes\n\n---\n\n# QUIC\n\nSobre UDP.\n\n## Detalles\nCifrado.\n\nTags: redes, quic"
        assertEquals(
            listOf(
                DraftNote("UDP", "Sin conexión.", listOf("redes")),
                DraftNote("QUIC", "Sobre UDP.\n\n## Detalles\nCifrado.", listOf("redes", "quic")),
            ),
            NoteFormat.parse(reply, "fallback"),
        )
        assertEquals(listOf("UDP", "QUIC"), NoteFormat.titles(reply))
    }

    @Test
    fun aCommentInACodeBlockIsNotATitle() {
        val reply = "# Bash\n\nUn intérprete.\n\n```bash\n# lista archivos\nls -la\n```\n\nTags: shell"
        val note = NoteFormat.parse(reply, "fallback").single()
        assertEquals("Bash", note.title)
        assertTrue(note.body.contains("```bash\n# lista archivos\nls -la\n```"))
    }

    @Test
    fun titlesBulletsAndTagsAreNormalised() {
        val reply = "```markdown\n# **Título: Kernel Monolítico.**\n\n**Kernel Monolítico**\n\n*   Rápido\n•  Un solo espacio\n\n\n\n" +
            "**Etiquetas:** #Kernel #Sistemas-Operativos, OS Design, 2fast, kernel\n```"
        val note = NoteFormat.parse(reply, "fallback").single()
        assertEquals("Kernel Monolítico", note.title)
        assertEquals("- Rápido\n- Un solo espacio", note.body)
        assertEquals(listOf("kernel", "sistemas-operativos", "os-design"), note.tags)
    }

    @Test
    fun aReplyWithoutATitleBecomesOneNoteNamedAfterTheRequest() {
        assertEquals(
            listOf(DraftNote("haz una nota de tcp", "TCP es fiable.", listOf("redes", "tcp"))),
            NoteFormat.parse("TCP es fiable.\n\nTags: redes tcp", "haz una nota de tcp\nsegunda línea"),
        )
        assertTrue(NoteFormat.parse("# Empty\n\nTags: a", "fallback").isEmpty())
    }

    @Test
    fun aRepeatedTitleIsSavedOnce() {
        assertEquals(listOf("UDP"), NoteFormat.parse("# UDP\n\nOne.\n\n# udp\n\nTwo.", "fallback").map { it.title })
    }

    @Test
    fun instructionsUseTheLanguageOfTheRequestAndOfferLinksAndTags() {
        val spanish = NoteFormat.instructions("haz una nota para cada protocolo", listOf("TCP"), listOf("redes"))
        assertTrue(spanish.contains("## Puntos clave"))
        assertTrue(spanish.contains("## Detalles"))
        assertTrue(spanish.contains("Existing notes you may link to: [[TCP]]"))
        assertTrue(spanish.contains("Tags already in use: redes"))
        val english = NoteFormat.instructions("make a note about TCP", emptyList(), emptyList())
        assertTrue(english.contains("## Key points"))
        assertFalse(english.contains("Existing notes"))
    }

    @Test
    fun instructionsAskForAliasesBeforeTheTags() {
        val text = NoteFormat.instructions("make a note about TCP", emptyList(), emptyList())
        assertTrue(text.indexOf("\nAliases:") in 0 until text.indexOf("\nTags:"))
        // General words link every note to one; the model is told so.
        assertTrue(text.contains("not general words"))
    }

    @Test
    fun anAliasesLineIsParsedAndLeftOutOfTheBody() {
        val reply = "# Package Management with apt\n\nInstalls software.\n\nAliases: apt, **APT**, \"apt-get\"\nTags: linux, apt"
        val note = NoteFormat.parse(reply, "fallback").single()
        assertEquals(listOf("apt", "apt-get"), note.aliases)
        assertEquals("Installs software.", note.body)
        assertEquals(listOf("linux", "apt"), note.tags)
    }

    @Test
    fun aliasesNoneOrMissingGivesNoAliases() {
        val none = NoteFormat.parse("# systemd\n\nInit system.\n\nAliases: none\nTags: linux", "fallback").single()
        assertEquals(emptyList<String>(), none.aliases)
        assertEquals("Init system.", none.body)
        val spanish = NoteFormat.parse("# UDP\n\nSin conexión.\n\nAlias: ninguno\nEtiquetas: redes", "fallback").single()
        assertEquals(emptyList<String>(), spanish.aliases)
        val missing = NoteFormat.parse("# Tea\n\nA drink.\n\nTags: tea", "fallback").single()
        assertEquals(emptyList<String>(), missing.aliases)
    }
}
