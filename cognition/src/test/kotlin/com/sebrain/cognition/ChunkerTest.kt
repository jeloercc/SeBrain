package com.sebrain.cognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChunkerTest {
    private fun texts(text: String, max: Int) = Chunker.chunk(text, max).map { it.text }

    @Test
    fun shortTextIsOneChunk() {
        val chunks = Chunker.chunk("  Hola mundo.  ")
        assertEquals(listOf("Hola mundo."), chunks.map { it.text })
        assertEquals(0, chunks[0].position)
        assertEquals(2, chunks[0].startOffset)
        assertEquals(13, chunks[0].endOffset)
    }

    @Test
    fun emptyOrBlankTextHasNoChunks() {
        assertTrue(Chunker.chunk("").isEmpty())
        assertTrue(Chunker.chunk(" \n\n ").isEmpty())
    }

    @Test
    fun splitsAtParagraphsBeforeSentences() {
        assertEquals(
            listOf("Uno dos. Tres.", "Cuatro cinco."),
            texts("Uno dos. Tres.\n\nCuatro cinco.", 20),
        )
    }

    @Test
    fun splitsLongParagraphsAtSentences() {
        assertEquals(
            listOf("Primera frase.", "Segunda frase!", "Tercera?"),
            texts("Primera frase. Segunda frase! Tercera?", 16),
        )
    }

    @Test
    fun mergesSmallPiecesUpToTheLimit() {
        assertEquals(listOf("A. B. C.", "D. E."), texts("A. B. C. D. E.", 8))
    }

    @Test
    fun splitsAtWordsAndThenInsideAWord() {
        assertEquals(listOf("uno dos", "tres"), texts("uno dos tres", 8))
        assertEquals(listOf("abcd", "efgh", "ij"), texts("abcdefghij", 4))
    }

    @Test
    fun offsetsPointIntoTheOriginalTextAndNothingIsLost() {
        val text = "Reunión el jueves 14 a las 10:30.\n\nTrae los bocetos. Avisa a Diego, por favor.\n\n" + "palabra ".repeat(60)
        val chunks = Chunker.chunk(text, 50)
        for ((i, chunk) in chunks.withIndex()) {
            assertEquals(i, chunk.position)
            assertEquals(chunk.text, text.substring(chunk.startOffset, chunk.endOffset))
            assertTrue(chunk.text.length <= 50)
            assertTrue(chunk.text == chunk.text.trim())
        }
        assertEquals(
            text.filterNot { it.isWhitespace() },
            chunks.joinToString("") { it.text }.filterNot { it.isWhitespace() },
        )
    }
}
