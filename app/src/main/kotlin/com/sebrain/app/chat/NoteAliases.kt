package com.sebrain.app.chat

/**
 * Other names for a note the assistant writes, so the notes that mention it by a shorter name
 * ("shell", "apt", "TCP") link to it. They go in the note's `aliases:` property, where the
 * linker, link resolution and the chunk headers already read them. See docs/design-note-aliases.md.
 */
object NoteAliases {
    const val MAX_ALIASES = 3
    private const val MIN_CHARS = 3
    private const val MAX_CHARS = 40

    /** English and Spanish articles that may open a title. */
    private val LEADING_ARTICLE = Regex("^(the|an?|el|la|los|las|una?)\\s+(\\S.*)$", RegexOption.IGNORE_CASE)
    private val TRAILING_PARENTHESIS = Regex("^(\\S.*?)\\s*\\(([^()]+)\\)\\s*$")

    /**
     * What the brain knows about names. Each check is one indexed query, and [choose] runs it
     * only for the few names that pass the checks done in memory, so the cost does not grow
     * with the number of notes.
     */
    data class Brain(
        /** Whether another note of the brain already answers to this name (title, alias or path). */
        val isTaken: (String) -> Boolean,
        /** The brain's tags, lowercase. */
        val tagsInUse: Set<String>,
        /** Whether the name is so common in the brain that linking it would connect everything. */
        val isGeneric: (String) -> Boolean,
    )

    /** Names that follow from the title alone: without its article, and a parenthesis split out. */
    fun derive(title: String): List<String> {
        val clean = title.trim()
        TRAILING_PARENTHESIS.find(clean)?.let { match -> return match.groupValues.drop(1).map { it.trim() } }
        LEADING_ARTICLE.find(clean)?.let { match -> return listOf(match.groupValues[2].trim()) }
        return emptyList()
    }

    /**
     * The aliases to store for a note titled [title]: the model's first, then the derived ones,
     * filtered. [ownTags] are the note's tags, which are not in the brain until it is saved: one
     * is dropped as a general word ("core") unless it is a word of the title ("kernel" in "The
     * Linux Kernel"), where it names the topic itself.
     */
    fun choose(title: String, proposed: List<String>, brain: Brain, ownTags: List<String> = emptyList()): List<String> {
        val own = title.trim().lowercase()
        val titleWords = own.split(Regex("[^\\p{L}\\p{N}]+")).toSet()
        val generalTags = ownTags.map { it.lowercase() }.filter { it !in titleWords }.toSet()
        return (proposed + derive(title)).asSequence()
            .map { it.trim() }
            .filter { it.length in MIN_CHARS..MAX_CHARS && it.lowercase() != own }
            .distinctBy { it.lowercase() }
            .filter { it.lowercase() !in brain.tagsInUse && it.lowercase() !in generalTags }
            .filter { !brain.isTaken(it) && !brain.isGeneric(it) }
            .take(MAX_ALIASES)
            .toList()
    }
}
