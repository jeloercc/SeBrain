package com.sebrain.core.ml

/**
 * Notices when a reply has started to repeat itself. A small model can fall into writing the
 * same line, or the same phrase, over and over until its token limit; on the phone that is
 * minutes of nothing. The check is plain text matching, so it works for any model.
 */
object LoopGuard {
    private const val MIN_LINE = 12
    private const val PREFIX = 30
    private const val REPEATS = 3
    private const val TAIL = 50
    private const val MIN_TEXT = 200
    private val SPACE = Regex("\\s+")

    /**
     * Where [text] should be cut because what follows only repeats, or -1 if it is not looping.
     * Two shapes are caught: the last three lines starting alike, and the last [TAIL] characters
     * already written twice before.
     */
    fun cut(text: String): Int {
        lines(text)?.let { return it }
        if (text.length < MIN_TEXT) return -1
        val tail = text.substring(text.length - TAIL)
        if (tail.isBlank()) return -1
        val first = text.indexOf(tail)
        val second = text.indexOf(tail, first + 1)
        // Found at least twice before the end itself: three times in all.
        return if (second >= 0 && second < text.length - TAIL) first + TAIL else -1
    }

    /** The end of the first of three or more consecutive lines that start the same way. */
    private fun lines(text: String): Int? {
        // Only complete lines count; the one being written may still diverge.
        val complete = text.substringBeforeLast('\n', "")
        if (complete.isEmpty()) return null
        var offset = 0
        val starts = ArrayList<Pair<Int, String>>()
        for (line in complete.split('\n')) {
            val key = SPACE.replace(line.trim().lowercase(), " ")
            if (key.length >= MIN_LINE) starts += (offset + line.length) to key.take(PREFIX)
            offset += line.length + 1
        }
        if (starts.size < REPEATS) return null
        val last = starts.last().second
        var run = 0
        for (i in starts.indices.reversed()) if (starts[i].second == last) run++ else break
        return if (run >= REPEATS) starts[starts.size - run].first else null
    }
}
