package com.sebrain.core.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LoopGuardTest {
    @Test
    fun aLineWrittenThreeTimesIsCutAfterItsFirstTime() {
        // What a small model wrote on the phone on 2026-11-13.
        val start = "You are SeBrain, the user's second brain.\n"
        val line = "You are to answer in the language of the user's message, directly and completely.\n"
        val text = start + line + line + line + "You are to answer in"
        assertEquals((start + line).length - 1, LoopGuard.cut(text))
        // Two times is not yet a loop, and the unfinished last line does not count.
        assertEquals(-1, LoopGuard.cut(start + line + line + "You are to answer in"))
    }

    @Test
    fun linesThatOnlyStartAlikeCountToo() {
        val text = "Intro.\nYou are to answer in the language of the user, directly.\nYou are to answer in the language of the user, directamente.\n" +
            "You are to answer in the language of the user's message.\nmore"
        assertTrue(LoopGuard.cut(text) > 0)
    }

    @Test
    fun aPhraseRepeatedInsideOneParagraphIsCut() {
        val phrase = "y entonces el protocolo vuelve a intentar la conexión otra vez, "
        val text = "El cliente abre la conexión, " + phrase.repeat(4)
        val cut = LoopGuard.cut(text)
        assertTrue(cut in 1 until text.length)
        assertTrue(text.substring(0, cut).split(phrase.take(30)).size <= 3)
    }

    @Test
    fun ordinaryTextListsAndCodeAreNotLoops() {
        val list = "## Puntos clave\n- Es un protocolo de transporte.\n- Es fiable y ordenado.\n- Usa puertos para las aplicaciones.\n- Controla la congestión.\n"
        assertEquals(-1, LoopGuard.cut(list))
        val code = "```bash\nssh usuario@servidor\nssh usuario@servidor2\nssh usuario@servidor3\n```\n"
        assertEquals(-1, LoopGuard.cut(code))
        assertEquals(-1, LoopGuard.cut("Una respuesta normal, de una sola frase."))
        assertEquals(-1, LoopGuard.cut("a\nb\nc\nd\n"))
    }
}
