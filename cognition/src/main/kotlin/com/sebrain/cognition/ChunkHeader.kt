package com.sebrain.cognition

/**
 * The line that goes in front of each chunk before it is indexed, for full text and for
 * vectors: which note the chunk belongs to, under which headings, with which tags.
 *
 * A chunk alone often does not say what it is about: "Usa el puerto 22 por defecto" is found
 * by "puerto", not by "SSH". With its header it is found by both, and the model that reads the
 * chunk later also sees where it comes from. This is contextual retrieval without a model
 * call: everything in the header is already in the note.
 */
object ChunkHeader {
    private val HEADING = Regex("^(#{1,6})\\s+(.*?)\\s*#*\\s*$")
    private val FENCE = Regex("^\\s*(```|~~~)")
    private const val MAX_CHARS = 160
    private const val MAX_ALIASES = 3
    private const val MAX_TAGS = 6

    /**
     * The header of the chunk that starts at [offset] of [text]: `[Title · also: alias ·
     * Section › Subsection · #tag #tag]`. The headings are the ones open at that point, without
     * the heading that repeats the title.
     */
    fun of(text: String, title: String, aliases: List<String>, tags: List<String>, offset: Int): String {
        val parts = ArrayList<String>()
        parts += title.trim().ifEmpty { "Untitled" }
        if (aliases.isNotEmpty()) parts += "also: " + aliases.take(MAX_ALIASES).joinToString(", ")
        val path = headings(text, offset).filterNot { it.equals(title.trim(), ignoreCase = true) }
        if (path.isNotEmpty()) parts += path.joinToString(" › ")
        if (tags.isNotEmpty()) parts += tags.take(MAX_TAGS).joinToString(" ") { "#$it" }
        val line = parts.joinToString(" · ")
        return "[" + (if (line.length <= MAX_CHARS) line else line.take(MAX_CHARS).trimEnd() + "…") + "]"
    }

    /** The headings open at [offset], outermost first: the last of each level above the text there. */
    private fun headings(text: String, offset: Int): List<String> {
        val open = arrayOfNulls<String>(7)
        var position = 0
        var fenced = false
        for (line in text.lines()) {
            // A heading that starts the chunk itself is part of its text, not of its context.
            if (position >= offset) break
            if (FENCE.containsMatchIn(line)) fenced = !fenced
            if (!fenced) {
                HEADING.find(line)?.let { match ->
                    val level = match.groupValues[1].length
                    open[level] = match.groupValues[2].trim()
                    for (deeper in level + 1..6) open[deeper] = null
                }
            }
            position += line.length + 1
        }
        return open.filterNotNull().filter { it.isNotEmpty() }
    }
}
