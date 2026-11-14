package com.sebrain.app.chat

import com.sebrain.app.chat.NoteEdits.Change
import com.sebrain.app.chat.NoteEdits.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteEditsTest {
    private val note = "---\nstatus: active\n---\n# Protocolo TCP\n\nTCP es fiavle y ordenado. Ver [[IP]].\n\n```bash\nnc -l 80\n```\n\n#redes #pinned\n"

    @Test
    fun aMessageStartsAToolOnlyWhenItOpensWithAnEditVerbAndSaysNote() {
        assertEquals(Kind.FIX, NoteEdits.kindOf("corrige la nota Protocolo TCP"))
        assertEquals(Kind.IMPROVE, NoteEdits.kindOf("por favor, mejora la nota de TCP"))
        assertEquals(Kind.LINK, NoteEdits.kindOf("Enlaza la nota TCP con las demás"))
        assertEquals(Kind.TAGS, NoteEdits.kindOf("etiqueta la nota TCP"))
        assertEquals(Kind.EDIT, NoteEdits.kindOf("añade a la nota TCP que usa el puerto 80"))
        assertEquals(Kind.EDIT, NoteEdits.kindOf("can you update the note TCP with the new port"))
        // Creating a note is not editing one, even if the request has an edit word further in.
        assertNull(NoteEdits.kindOf("crea una nota sobre cómo mejorar TCP"))
        assertNull(NoteEdits.kindOf("haz una nota del siguiente"))
        assertNull(NoteEdits.kindOf("mejora mi explicación de TCP"))
        assertNull(NoteEdits.kindOf("¿qué dice la nota TCP?"))
    }

    @Test
    fun theLongestExistingTitleInTheMessageIsTheNote() {
        val names = listOf(1L to "TCP", 2L to "Protocolo TCP", 3L to "Café", 4L to "IP")
        assertEquals(2L, NoteEdits.mentioned("corrige la nota protocolo tcp por favor", names))
        assertEquals(1L, NoteEdits.mentioned("mejora la nota TCP.", names))
        assertEquals(3L, NoteEdits.mentioned("corrige la nota cafe", names))
        // "IP" is too short to trust, and "TCPs" is another word.
        assertNull(NoteEdits.mentioned("corrige la nota sobre los TCPs e IP", names))
    }

    @Test
    fun correctionsAreAppliedWhereTheNoteSaysThemAndNowhereElse() {
        val reply = "Aquí están:\n- es fiavle => es fiable\n2. \"no existe\" => \"nada\"\nnc -l 80 => nc -l 8080\nIP => ip\nNONE"
        val result = NoteEdits.corrected(note, reply)!!
        assertEquals(note.replace("fiavle", "fiable"), result.text)
        // "IP" is only inside a link, so it has no place to go. Replacing two words by one and
        // changing a number in the code are not spelling.
        assertEquals(
            listOf(
                "1 correction(s) of the model did not match the note and were left out",
                "2 suggestion(s) changed more than spelling and were left out",
            ),
            result.warnings,
        )
        assertNull(NoteEdits.corrected(note, "NONE"))
        assertNull(NoteEdits.corrected(note, "fiavle => fiavle"))
    }

    @Test
    fun aCorrectionNeverTouchesTheFrontMatter() {
        assertNull(NoteEdits.corrected(note, "status: active => status: done"))
    }

    @Test
    fun aRewriteKeepsFrontMatterTitleAndTagsWhateverTheModelDid() {
        val reply = "Aquí tienes la nota mejorada:\n\n# TCP mejorado\n\nTCP es fiable y ordenado. Ver [[IP]].\n\n## Puntos clave\n- Fiable\n\n```bash\nnc -l 80\n```\n\n¿Quieres que cambie algo más?"
        val result = NoteEdits.rewritten(Kind.IMPROVE, note, reply)!!
        assertEquals(
            "---\nstatus: active\n---\n# Protocolo TCP\n\nTCP es fiable y ordenado. Ver [[IP]].\n\n## Puntos clave\n- Fiable\n\n```bash\nnc -l 80\n```\n\n#redes #pinned\n",
            result.text,
        )
        assertEquals(emptyList<String>(), result.warnings)
    }

    @Test
    fun whatARewriteLostIsReported() {
        val result = NoteEdits.rewritten(Kind.IMPROVE, note, "# Protocolo TCP\n\nFiable. Ver [[UDP]].\n\n#redes #pinned")!!
        assertEquals(
            listOf(
                "The model dropped links; they were put back under Related: [[IP]]",
                "The model dropped or changed a code block; the original was put back at the end",
                "Links added by the model: [[UDP]]",
            ),
            result.warnings,
        )
        // The link and the code block are back, above the closing tags.
        assertTrue(result.text.endsWith("Fiable. Ver [[UDP]].\n\n## Relacionado\n- [[IP]]\n\n```bash\nnc -l 80\n```\n\n#redes #pinned\n"))
        // An edit may change the code and drop a link on purpose; both are reported, not undone.
        val edited = NoteEdits.rewritten(Kind.EDIT, note, "# Protocolo TCP\n\nTCP es fiavle y ordenado.\n\n```bash\nnc -l 8080\n```\n\n#redes #pinned")!!
        assertEquals(listOf("Links removed: [[IP]]", "A code block was changed or removed"), edited.warnings)
        assertTrue(edited.text.contains("nc -l 8080") && !edited.text.contains("nc -l 80\n"))
        assertNull(NoteEdits.rewritten(Kind.EDIT, note, "  \n "))
        // A reply wrapped in a code fence is unwrapped.
        assertTrue(NoteEdits.rewritten(Kind.EDIT, note, "```markdown\n# Protocolo TCP\n\nTexto [[IP]].\n```")!!.text.contains("# Protocolo TCP\n\nTexto [[IP]]."))
    }

    @Test
    fun onlyNewTagsAreAddedAndTheyAreNormalised() {
        val result = NoteEdits.tagged(note, "Tags: Redes, Capa de Transporte, #protocolos, pinned")!!
        assertTrue(result.text.endsWith("#redes #pinned\n\n#capa-de-transporte #protocolos\n"))
        assertNull(NoteEdits.tagged(note, "Tags: redes, pinned"))
    }

    @Test
    fun mentionsOfOtherNotesBecomeLinksWithoutTheModel() {
        val text = "# Capa de transporte\n\nEl protocolo tcp es fiable; UDP no. Ya enlazado: [[IP]]. IP otra vez.\n"
        val others = listOf(
            "Protocolo TCP" to listOf("Protocolo TCP", "TCP"),
            "UDP" to listOf("UDP"),
            "IP" to listOf("IP", "Internet Protocol"),
            "Sin mención" to listOf("Sin mención"),
        )
        assertEquals(
            "# Capa de transporte\n\nEl [[Protocolo TCP|protocolo tcp]] es fiable; [[UDP]] no. Ya enlazado: [[IP]]. IP otra vez.\n",
            NoteEdits.linked(text, others)!!.text,
        )
        assertNull(NoteEdits.linked("Nada que enlazar aquí.", others))
    }

    @Test
    fun aDiffMarksWhatWasKeptAddedAndRemoved() {
        assertEquals(
            listOf(Change.SAME to "# Título", Change.REMOVED to "viejo", Change.ADDED to "nuevo", Change.SAME to "fin", Change.ADDED to "extra"),
            NoteEdits.diff("# Título\nviejo\nfin\n", "# Título\nnuevo\nfin\nextra"),
        )
        assertTrue(NoteEdits.diff("igual", "igual").all { it.first == Change.SAME })
    }

    @Test
    fun aFixIsEstimatedInSecondsAndARewriteInProportionToTheNote() {
        assertTrue(NoteEdits.secondsFor(Kind.FIX, 3000) < 15)
        assertTrue(NoteEdits.secondsFor(Kind.IMPROVE, 3000) > 100)
        assertEquals(0, NoteEdits.secondsFor(Kind.LINK, 3000))
        // A slower model takes longer, and adding a sentence is short whatever the note's size.
        assertTrue(NoteEdits.secondsFor(Kind.IMPROVE, 3000, tokensPerSecond = 2.0) > 3 * NoteEdits.secondsFor(Kind.IMPROVE, 3000))
        assertTrue(NoteEdits.secondsFor(Kind.EDIT, 3000, addition = true) < 15)
    }

    @Test
    fun notesFoundByMeaningGoUnderRelatedAboveTheClosingTags() {
        val text = "# Capa de transporte\n\nEl protocolo tcp es fiable.\n\n#redes #pinned\n"
        val result = NoteEdits.linked(text, listOf("Protocolo TCP" to listOf("Protocolo TCP")), related = listOf("Protocolo TCP", "Congestión", "Sockets"))!!
        assertEquals(
            "# Capa de transporte\n\nEl [[Protocolo TCP|protocolo tcp]] es fiable.\n\n## Relacionado\n- [[Congestión]]\n- [[Sockets]]\n\n#redes #pinned\n",
            result.text,
        )
        assertEquals(1, result.warnings.size)
        // An existing section gets the new links at its end.
        val again = NoteEdits.linked(result.text, emptyList(), related = listOf("Sockets", "QUIC"))!!
        assertTrue(again.text.contains("## Relacionado\n- [[Congestión]]\n- [[Sockets]]\n- [[QUIC]]\n\n#redes #pinned"))
        assertNull(NoteEdits.linked(result.text, emptyList(), related = listOf("Sockets")))
    }

    @Test
    fun aCorrectedCopyGivesOnlyItsSmallWordChanges() {
        // What a small model returned instead of a list: the text again, partly fixed and partly rewritten.
        val reply = "SSH es fiable y muy ordenado, de verdad. Ver [[ip]].\n```bash\nnc -l 8080\n```"
        val result = NoteEdits.corrected(note.replace("TCP es fiavle", "SSH es fiavle"), reply)!!
        assertTrue(result.text.contains("SSH es fiable y ordenado. Ver [[IP]]."))
        // The link, the code and the front matter are as they were.
        assertTrue(result.text.contains("nc -l 80\n"))
        assertTrue(result.text.startsWith("---\nstatus: active\n---\n# Protocolo TCP"))
        // Accents count as spelling; an unrelated word does not.
        assertEquals("La máquina está lista.\n", NoteEdits.corrected("La maquina esta lista.\n", "La máquina está lista.")!!.text)
        assertNull(NoteEdits.corrected("La casa es azul.\n", "El coche es rojo."))
    }

    @Test
    fun anAdditionIsPlacedAboveTheClosingTags() {
        assertTrue(NoteEdits.isAddition("añade a la nota SSH que el puerto es el 22"))
        assertTrue(NoteEdits.isAddition("por favor, agrega el puerto"))
        assertTrue(!NoteEdits.isAddition("cambia el puerto a 2222"))
        val result = NoteEdits.appended(note, "Aquí está la frase:\n\"El puerto por defecto es el 22.\"")!!
        assertTrue(result.text.endsWith("```bash\nnc -l 80\n```\n\nEl puerto por defecto es el 22.\n\n#redes #pinned\n"))
        assertNull(NoteEdits.appended(note, "TCP es fiavle y ordenado. Ver [[IP]]."))
        assertNull(NoteEdits.appended(note, "  "))
    }

    @Test
    fun aRepeatedTitleAtAnyLevelAndStarBulletsAreNormalised() {
        val result = NoteEdits.rewritten(Kind.IMPROVE, note, "## Protocolo TCP\n\nFiable. Ver [[IP]].\n\n* Uno\n* Dos\n\n```bash\nnc -l 80\n```")!!
        assertEquals("---\nstatus: active\n---\n# Protocolo TCP\n\nFiable. Ver [[IP]].\n\n- Uno\n- Dos\n\n```bash\nnc -l 80\n```\n\n#redes #pinned\n", result.text)
    }

    @Test
    fun aCorrectionThatChangesTheMeaningOrTheTitleIsLeftOut() {
        // What the smaller model answered on the phone on 2026-11-13.
        val text = "# Protocolo SSH\n\nUsa cifrado asimetrico para el intercanbio de claves y despues cifrado simetrico. El Protocolo es seguro.\n"
        val reply = "Protocolo => Protocol\ncifrado asimetrico => criptográfico\nintercanbio de claves => intercambio de claves\ndespues cifrado simetrico => después cifrado simétrico"
        val result = NoteEdits.corrected(text, reply)!!
        assertEquals(
            "# Protocolo SSH\n\nUsa cifrado asimetrico para el intercambio de claves y después cifrado simétrico. El Protocol es seguro.\n",
            result.text,
        )
        assertEquals(listOf("1 suggestion(s) changed more than spelling and were left out"), result.warnings)
    }

    @Test
    fun whenTheModelCopiesTheNoteOnlyTheNewSentenceIsAdded() {
        val reply = "# Protocolo TCP\n\nTCP es fiavle y ordenado. El puerto por defecto es el 80. Ver [[IP]].\n\n```bash\nnc -l 80\n```\n\n#redes #pinned"
        val result = NoteEdits.appended(note, reply)!!
        assertTrue(result.text.endsWith("```bash\nnc -l 80\n```\n\nEl puerto por defecto es el 80.\n\n#redes #pinned\n"))
    }
}
