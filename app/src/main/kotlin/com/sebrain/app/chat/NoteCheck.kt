package com.sebrain.app.chat

/**
 * Checks and repairs a note the model wrote before it is shown for review. Deterministic: it
 * fixes the format mistakes small models make and warns about what it cannot fix.
 */
object NoteCheck {
    /** A longer title still works, but links to it read badly. */
    const val MAX_TITLE_WORDS = 6

    data class Checked(
        val title: String,
        val body: String,
        val tags: List<String>,
        /** What was repaired, in words the user reads on the review screen. */
        val fixes: List<String>,
        /** What could not be repaired. */
        val warnings: List<String>,
    )

    private val TITLE_MARKS = Regex("^[#\\s]+|[*_\"“”]+")
    private val TITLE_END = Regex("[.:;\\s]+$")
    private val H1 = Regex("^#\\s+")
    private val LEFTOVER = Regex("^\\s*(tags|etiquetas|aliases|alias)\\s*:.*$", RegexOption.IGNORE_CASE)
    private val FENCE = Regex("^\\s*(```|~~~)")
    private val BLANK_RUN = Regex("\n{3,}")

    fun check(title: String, body: String, tags: List<String>): Checked {
        val fixes = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        // Marks first: in "**Title.**" the full stop is only at the end once the emphasis is gone.
        val cleanTitle = TITLE_END.replace(TITLE_MARKS.replace(title.trim(), "").trim(), "")
        if (cleanTitle != title.trim()) fixes += "Title cleaned of formatting marks"
        val words = cleanTitle.split(Regex("\\s+")).count { it.isNotBlank() }
        if (cleanTitle.isEmpty()) warnings += "The note has no title"
        if (words > MAX_TITLE_WORDS) warnings += "The title has $words words; a title of up to $MAX_TITLE_WORDS links better"

        val (cleanBody, bodyFixes) = body(body)
        fixes += bodyFixes
        if (cleanBody.isBlank()) warnings += "The note has no text"

        val cleanTags = cleanTags(tags)
        if (cleanTags != tags.map { it.trim() }.filter { it.isNotEmpty() }) fixes += "Tags cleaned"
        if (cleanTags.isEmpty()) warnings += "The note has no tags"

        return Checked(cleanTitle, cleanBody, cleanTags, fixes, warnings)
    }

    /** Tags as Obsidian accepts them: lowercase, no `#`, words joined by `-`, not only digits. */
    fun cleanTags(tags: List<String>): List<String> =
        tags.map { it.trim().trimStart('#').lowercase().replace(Regex("[^\\p{L}\\p{N}_/-]"), "-").trim('-') }
            .filter { it.isNotEmpty() && !it.first().isDigit() }.distinct()

    /**
     * The note that already answers to [title] or one of [aliases], as (id, title), from the
     * brain's [names] (note id, title, name). Links resolve by name, so a second one would be ambiguous.
     */
    fun duplicateOf(title: String, aliases: List<String>, names: List<Triple<Long, String, String>>): Pair<Long, String>? {
        val wanted = (listOf(title) + aliases).map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
        return names.firstOrNull { (_, _, name) -> name.trim().lowercase() in wanted }?.let { (id, noteTitle, _) -> id to noteTitle }
    }

    private fun body(body: String): Pair<String, List<String>> {
        val fixes = linkedSetOf<String>()
        var inCode = false
        val lines = body.trim().lines().mapNotNull { line ->
            if (FENCE.containsMatchIn(line)) {
                inCode = !inCode
                return@mapNotNull line
            }
            when {
                inCode -> line
                LEFTOVER.matches(line) -> {
                    fixes += "Leftover Tags or Aliases line removed from the text"
                    null
                }
                H1.containsMatchIn(line) -> {
                    fixes += "A second title in the text became a section"
                    "## " + H1.replace(line, "")
                }
                else -> line
            }
        }
        return BLANK_RUN.replace(lines.joinToString("\n").trim(), "\n\n") to fixes.toList()
    }
}
