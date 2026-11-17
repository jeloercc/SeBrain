package com.sebrain.cognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NoteSectionsTest {
    private val note = """
        ---
        status: active
        ---
        # Recetas

        Introducción.

        ## Postres
        Flan y arroz con leche. ^clasicos

        ```
        ## esto es código
        ```

        ### Helados
        De vainilla.

        ## Sopas
        De ajo.
        Con pan. ^sopa-ajo
    """.trimIndent()

    @Test
    fun aLinkIsSplitIntoTargetAnchorAndDisplay() {
        assertEquals(NoteSections.Reference("Recetas", "", null), NoteSections.reference("Recetas"))
        assertEquals(NoteSections.Reference("Recetas", "Postres", "ver postres"), NoteSections.reference(" Recetas#Postres | ver postres "))
        assertEquals("Recetas › Postres", NoteSections.reference("Recetas#Postres").label)
        assertEquals("Recetas › clasicos", NoteSections.reference("Recetas#^clasicos").label)
        assertEquals("ver", NoteSections.reference("Recetas|ver").label)
    }

    @Test
    fun aHeadingGivesItsSectionWithItsSubsections() {
        assertEquals(
            "## Postres\nFlan y arroz con leche. ^clasicos\n\n```\n## esto es código\n```\n\n### Helados\nDe vainilla.",
            NoteSections.extract(note, "postres"),
        )
        assertEquals("### Helados\nDe vainilla.", NoteSections.extract(note, "Helados"))
        assertEquals("## Sopas\nDe ajo.\nCon pan. ^sopa-ajo", NoteSections.extract(note, "Sopas"))
        assertNull(NoteSections.extract(note, "Ensaladas"))
        // A heading inside a code block is not a heading.
        assertNull(NoteSections.extract(note, "esto es código"))
    }

    @Test
    fun aBlockIdGivesItsParagraphWithoutTheMarker() {
        assertEquals("## Postres\nFlan y arroz con leche.", NoteSections.extract(note, "^clasicos"))
        assertEquals("## Sopas\nDe ajo.\nCon pan.", NoteSections.extract(note, "^sopa-ajo"))
        assertNull(NoteSections.extract(note, "^nada"))
    }

    @Test
    fun theWholeNoteComesWithoutItsFrontMatter() {
        assertEquals("# Recetas", NoteSections.extract(note, "")!!.lineSequence().first())
    }

    @Test
    fun aMentionBecomesALinkThatKeepsTheWordsAsWritten() {
        assertEquals(
            "Aquí se habla del [[Protocolo TCP|protocolo tcp]] sin enlazarlo, y del protocolo TCP otra vez.",
            NoteSections.linkMention("Aquí se habla del protocolo tcp sin enlazarlo, y del protocolo TCP otra vez.", "Protocolo TCP", listOf("Protocolo TCP")),
        )
        assertEquals("Ver [[Protocolo TCP]].", NoteSections.linkMention("Ver Protocolo TCP.", "Protocolo TCP", listOf("Protocolo TCP")))
        // The longer name wins over a shorter alias inside it.
        assertEquals("El [[Protocolo TCP|Transmission Control Protocol]].", NoteSections.linkMention("El Transmission Control Protocol.", "Protocolo TCP", listOf("TCP", "Transmission Control Protocol")))
    }

    @Test
    fun mentionsInsideLinksCodeOrOtherWordsAreNotTouched() {
        assertNull(NoteSections.linkMention("Ya está en [[Protocolo TCP]] y en `Protocolo TCP`.", "Protocolo TCP", listOf("Protocolo TCP")))
        assertNull(NoteSections.linkMention("Los TCPs y el xTCP no cuentan.", "Protocolo TCP", listOf("TCP")))
    }

    @Test
    fun aTagIsNeverTurnedIntoALink() {
        // Seen on the phone: "#kernel" became "#[[The Linux Kernel|kernel]]".
        val text = "# Systemd\n\nAn init system.\n\n#linux #core #kernel"
        assertNull(NoteSections.linkMention(text, "The Linux Kernel", listOf("kernel", "core")))
        // A nested tag too, while the same word in prose is still linked.
        assertEquals(
            "Talks to the [[The Linux Kernel|kernel]].\n\n#os/kernel",
            NoteSections.linkMention("Talks to the kernel.\n\n#os/kernel", "The Linux Kernel", listOf("kernel")),
        )
    }

    @Test
    fun aHeadingIsNeverTurnedIntoALink() {
        // Seen on the phone: the title "SSH Remote Access" lost its last word to a link.
        val text = "# SSH Remote Access\n\n## Access keys\nUse a key."
        assertNull(NoteSections.linkMention(text, "File Permissions and Chmod", listOf("access")))
        assertEquals(
            "# SSH\n\nControls [[File Permissions and Chmod|access]].",
            NoteSections.linkMention("# SSH\n\nControls access.", "File Permissions and Chmod", listOf("access")),
        )
        assertEquals(
            "---\ntitle: TCP\n---\nEl [[Protocolo TCP|TCP]].",
            NoteSections.linkMention("---\ntitle: TCP\n---\nEl TCP.", "Protocolo TCP", listOf("TCP")),
        )
    }

    @Test
    fun vaultFilesBecomeNotePathsAndBack() {
        assertEquals("Redes/Protocolos/TCP", MarkdownInterop.notePath("Redes/Protocolos/TCP.md"))
        assertEquals("Suelta", MarkdownInterop.notePath("Suelta.MD"))
        assertNull(MarkdownInterop.notePath(".obsidian/workspace.md"))
        assertNull(MarkdownInterop.notePath("Redes/.trash/vieja.md"))
        assertNull(MarkdownInterop.notePath("Redes/diagrama.png"))
        assertNull(MarkdownInterop.notePath(".md"))
        assertEquals(listOf("Redes", "Protocolos", "TCP.md"), MarkdownInterop.exportFile("Redes/Protocolos/TCP", "Protocolo TCP"))
        assertEquals(listOf("Protocolo TCP.md"), MarkdownInterop.exportFile(null, "Protocolo TCP"))
        assertEquals(listOf("a_b", "c_d.md"), MarkdownInterop.exportFile("a:b/c?d", "x"))
    }
}
