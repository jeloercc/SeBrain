package com.sebrain.cognition

import com.sebrain.core.vault.Chunk

/**
 * Splits a capture into chunks for search. It prefers paragraph, then sentence, then word
 * boundaries, and only cuts inside a word when a single word is longer than a chunk.
 */
object Chunker {
    /** About 200 tokens of Spanish text, which fits the 256-token input of the embedding model. */
    const val DEFAULT_MAX_CHARS = 800

    private val PARAGRAPH = Regex("\\n\\s*\\n")
    private val SENTENCE_END = Regex("(?<=[.!?…])\\s+")
    private val SPACE = Regex("\\s+")

    fun chunk(text: String, maxChars: Int = DEFAULT_MAX_CHARS): List<Chunk> {
        require(maxChars > 0) { "maxChars must be positive" }
        val pieces = split(text, 0, text.length, maxChars, listOf(PARAGRAPH, SENTENCE_END, SPACE))
        val chunks = ArrayList<Chunk>()
        var start = -1
        var end = -1
        fun flush() {
            if (start >= 0) chunks += Chunk(chunks.size, start, end, text.substring(start, end))
            start = -1
        }
        for ((pieceStart, pieceEnd) in pieces) {
            // Merge neighbors while they fit, so chunks are not needlessly small.
            if (start >= 0 && pieceEnd - start > maxChars) flush()
            if (start < 0) start = pieceStart
            end = pieceEnd
        }
        flush()
        return chunks
    }

    /** Ranges of non-blank text, each at most [maxChars] long. */
    private fun split(text: String, from: Int, to: Int, maxChars: Int, separators: List<Regex>): List<Pair<Int, Int>> {
        var start = from
        var end = to
        while (start < end && text[start].isWhitespace()) start++
        while (end > start && text[end - 1].isWhitespace()) end--
        if (start == end) return emptyList()
        if (end - start <= maxChars) return listOf(start to end)
        if (separators.isEmpty()) {
            return (start until end step maxChars).map { it to minOf(it + maxChars, end) }
        }
        val parts = ArrayList<Pair<Int, Int>>()
        var cursor = start
        for (match in separators.first().findAll(text, start)) {
            if (match.range.first >= end) break
            parts += cursor to match.range.first
            cursor = match.range.last + 1
        }
        parts += cursor to end
        return parts.flatMap { (a, b) -> split(text, a, b, maxChars, separators.drop(1)) }
    }
}
