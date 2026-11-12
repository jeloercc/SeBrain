package com.sebrain.app.chat

/**
 * One note given to the model as context. [via] is null for a note found by search, or says how
 * a connected note was reached through the graph, such as "linked from [1]".
 */
data class ContextNote(
    val number: Int,
    val id: Long,
    val title: String,
    val tags: List<String>,
    val linksTo: List<String>,
    val linkedFrom: List<String>,
    val text: String,
    val via: String? = null,
)

/** The prompts and text rules of the SeBrain assistant. Pure functions, no Android. */
object Assistant {
    const val SYSTEM =
        "You are SeBrain, the user's second brain: a private assistant that runs entirely on their phone " +
            "and knows their notes.\n" +
            "- Answer in the language of the user's message, directly and completely.\n" +
            "- The vault notes given with a message are the user's own knowledge. Prefer them over general " +
            "knowledge and cite them as [1], [2].\n" +
            "- The notes come with their tags and their links to other notes. Use them to see how ideas " +
            "relate, and when two notes are connected, say how.\n" +
            "- Never invent what a note says, and never cite a number that was not given.\n" +
            "- If the notes do not cover the question, say so in one short sentence, then answer from " +
            "general knowledge.\n" +
            "- Earlier messages are the conversation so far; use them to understand follow-up questions. " +
            "When the user asks to continue or for the next one, move on to a topic not covered yet; " +
            "never repeat an answer or a note you already wrote.\n" +
            "- Put every script or piece of code in a fenced code block with its language, like ```bash."

    /** Working memory: the last messages go back to the model in full, within this budget. */
    const val HISTORY_MESSAGES = 6
    const val HISTORY_CHARS = 1200
    const val HISTORY_BUDGET = 3000

    /** Older messages are kept only as one outline line each. */
    const val OUTLINE_CHARS = 100
    const val OUTLINE_LINES = 20
    const val MEMORY_CHARS = 300

    /** Context budget, so the prompt stays well inside the model's context window. */
    const val NOTE_CHARS = 900
    const val CONNECTED_CHARS = 280

    private val NOTE_WORD = Regex("\\b(notas?|notes?)\\b", RegexOption.IGNORE_CASE)
    private val SAVE_WORD = Regex(
        "\\b(crea\\w*|haz\\w*|hace\\w*|guard\\w*|anot\\w*|escrib\\w*|gener\\w*|agreg\\w*|añad\\w*|pon\\w*|create|make|save|write|add|put)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val TAG_LINE = Regex("^[*_#\\s]*(?:tags?|etiquetas?)[*_\\s]*:(.*)$", RegexOption.IGNORE_CASE)
    private val CITATION = Regex("\\s?\\[(\\d+)](?!\\()")
    private val HEADING = Regex("^#{1,3}\\s+(.+)$", RegexOption.MULTILINE)
    private val REMEMBER = Regex(
        "^(?:recuerda|recuerde|acu[eé]rdate|ten en cuenta|remember|keep in mind)(?:\\s+(?:de\\s+)?(?:que|that))?[:,]?\\s+(.+)$",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val WIKILINK = Regex("\\[\\[([^\\[\\]|]+)(?:\\|[^\\[\\]]*)?]]")

    /** True when the user asks for the answer to be stored as a note. */
    fun wantsNote(message: String) = NOTE_WORD.containsMatchIn(message) && SAVE_WORD.containsMatchIn(message)

    /**
     * What to search the vault for. A short follow-up such as "and why?" says little on its own,
     * so the previous question is searched with it.
     */
    fun retrievalQuery(message: String, previousUserMessage: String?): String {
        val text = message.trim()
        if (previousUserMessage.isNullOrBlank() || text.length >= FOLLOW_UP_LENGTH) return text
        return previousUserMessage.trim().take(HISTORY_CHARS) + "\n" + text
    }

    /** One message of the conversation. */
    class Line(val fromUser: Boolean, val text: String)

    /**
     * The conversation as the model remembers it, in three layers: [recent] messages word for
     * word, [outline] with one line for each older message, and [covered], the titles of the
     * notes and answers already written, so "the next one" does not repeat one of them.
     */
    class Memory(val recent: List<Line>, val outline: List<String>, val covered: List<String>)

    fun remember(earlier: List<Line>): Memory {
        var budget = HISTORY_BUDGET
        val recent = ArrayDeque<Line>()
        for (line in earlier.asReversed()) {
            val text = shorten(line.text, HISTORY_CHARS)
            if (recent.size == HISTORY_MESSAGES || text.length > budget) break
            budget -= text.length
            recent.addFirst(Line(line.fromUser, text))
        }
        // The model's chat format expects the history to start with the user.
        while (recent.isNotEmpty() && !recent.first().fromUser) recent.removeFirst()
        val older = earlier.dropLast(recent.size)
        val outline = older.takeLast(OUTLINE_LINES).map { line ->
            val gist = if (line.fromUser) line.text else heading(line.text) ?: line.text
            (if (line.fromUser) "- User asked: " else "- You answered: ") +
                shorten(gist.trim().lineSequence().firstOrNull().orEmpty(), OUTLINE_CHARS)
        }
        val covered = earlier.filter { !it.fromUser }.mapNotNull { heading(it.text) }.distinctBy { it.lowercase() }
        return Memory(recent.toList(), outline, covered)
    }

    /** The first `# Heading` of a reply: the title of the note or topic it wrote. */
    fun heading(text: String): String? = HEADING.find(text)?.groupValues?.get(1)?.trim()?.trim('*')?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * A fact the user asks SeBrain to keep, such as "recuerda que prefiero respuestas cortas".
     * Null for anything else, including questions like "¿recuerdas que…?".
     */
    fun factToRemember(message: String): String? {
        if ('?' in message) return null
        return REMEMBER.find(message.trim())?.groupValues?.get(1)?.trim()?.takeIf { it.length >= 3 }
    }

    /** The user message the model sees: its memory, the vault notes as a small graph, then the request. */
    fun prompt(
        message: String,
        notes: List<ContextNote>,
        asNote: Boolean,
        tags: List<String>,
        memory: Memory = Memory(emptyList(), emptyList(), emptyList()),
        facts: List<String> = emptyList(),
    ): String = buildString {
        if (facts.isNotEmpty()) {
            append("What the user asked you to always remember:\n")
            facts.forEach { append("- ").append(shorten(it.replace('\n', ' '), MEMORY_CHARS)).append('\n') }
            append('\n')
        }
        if (memory.outline.isNotEmpty()) {
            append("Earlier in this conversation:\n").append(memory.outline.joinToString("\n")).append("\n\n")
        }
        if (memory.covered.isNotEmpty()) {
            append("Already written in this conversation: ").append(memory.covered.joinToString("; ")).append(". ")
            append("Do not write any of them again. If the user asks for the next one, write about a new topic ")
            append("that follows them.\n\n")
        }
        val found = notes.filter { it.via == null }
        val connected = notes.filter { it.via != null }
        if (found.isNotEmpty()) {
            append("Notes from the vault that match the message:\n\n")
            found.forEach { append(render(it, NOTE_CHARS)).append("\n\n") }
        }
        if (connected.isNotEmpty()) {
            append("Connected notes, reached through links or shared tags:\n\n")
            connected.forEach { append(render(it, CONNECTED_CHARS)).append("\n\n") }
        }
        append(if (asNote) "Request: " else "Message: ").append(message.trim())
        if (asNote) append(noteFormat(notes.map { it.title }, tags))
        // Last, because a small model follows the most recent instruction best.
        append("\n\nWrite in the language of the message above, even if the notes are in another language.")
    }

    fun render(note: ContextNote, chars: Int): String = buildString {
        append('[').append(note.number).append("] ").append(note.title.ifBlank { "Untitled" })
        note.via?.let { append(" (").append(it).append(')') }
        val facts = listOfNotNull(
            note.tags.takeIf { it.isNotEmpty() }?.joinToString(" ", prefix = "Tags: ") { "#$it" },
            note.linksTo.takeIf { it.isNotEmpty() }?.joinToString(", ", prefix = "Links to: "),
            note.linkedFrom.takeIf { it.isNotEmpty() }?.joinToString(", ", prefix = "Linked from: "),
        )
        if (facts.isNotEmpty()) append('\n').append(facts.joinToString(" · "))
        append('\n').append(shorten(stripHeading(note.text, note.title), chars))
    }

    private fun noteFormat(titles: List<String>, tags: List<String>): String = buildString {
        append("\n\nThe answer will be saved as a note in the vault. Write the information that was asked for, ")
        append("complete and useful on its own. Do not repeat the request and do not say that you are creating a note.")
        val linkable = titles.filter { it.isNotBlank() }.distinct()
        if (linkable.isNotEmpty()) {
            append("\nExisting notes you may link to: ").append(linkable.joinToString(", ") { "[[$it]]" }).append('.')
            append(" Link one only where the new note really builds on it, writing its title exactly as shown.")
        }
        if (tags.isNotEmpty()) {
            append("\nTags already in use: ").append(tags.joinToString(", ")).append(". Reuse them when they fit.")
        }
        append("\nUse exactly this format:\n# A short title\n\nThe content, in Markdown\n\n")
        append("Tags: two to four lowercase one-word tags, separated by commas")
    }

    /** Splits a reply into title, body and tags. Without a `# Title` line, [fallbackTitle] is used. */
    fun parseNote(reply: String, fallbackTitle: String): Triple<String, String, List<String>> {
        val lines = reply.trim().lines().toMutableList()
        var tags = emptyList<String>()
        val tagIndex = lines.indexOfLast { TAG_LINE.matches(it.trim()) }
        if (tagIndex >= 0) {
            tags = TAG_LINE.find(lines[tagIndex].trim())!!.groupValues[1].split(',', ' ')
                .map { it.trim().trim('*', '_', '#', '.') }.filter { it.isNotEmpty() }
            lines.removeAt(tagIndex)
        }
        val first = lines.indexOfFirst { it.isNotBlank() }
        val title = if (first >= 0 && lines[first].trimStart().startsWith("#")) {
            lines.removeAt(first).trim().trimStart('#').trim().trim('*').trim()
        } else {
            fallbackTitle.lineSequence().firstOrNull().orEmpty().take(60)
        }
        return Triple(title.ifBlank { "Note" }, lines.joinToString("\n").trim(), tags)
    }

    /**
     * Makes a reply safe to keep as a note: each citation `[n]` becomes a link to that note's
     * title, and a `[[link]]` to a title that does not exist in [notes] becomes plain text, so a
     * made-up link does not create a false connection in the graph.
     */
    fun linkify(body: String, notes: List<ContextNote>, ownTitle: String): String {
        val byNumber = notes.associateBy { it.number }
        val known = notes.associateBy { it.title.lowercase() }
        val cited = CITATION.replace(body) { match ->
            val title = byNumber[match.groupValues[1].toInt()]?.title
            if (title.isNullOrBlank() || title.equals(ownTitle, ignoreCase = true)) "" else " [[$title]]"
        }
        return WIKILINK.replace(cited) { match ->
            val target = match.groupValues[1].trim()
            val note = known[target.lowercase()]
            if (note != null && !note.title.equals(ownTitle, ignoreCase = true)) "[[${note.title}]]" else target
        }
    }

    private fun stripHeading(text: String, title: String): String {
        val lines = text.trim().lines()
        val first = lines.firstOrNull()?.trim().orEmpty()
        return if (first.startsWith("#") && first.trimStart('#').trim().equals(title.trim(), ignoreCase = true)) {
            lines.drop(1).joinToString("\n").trim()
        } else {
            text.trim()
        }
    }

    fun shorten(text: String, chars: Int): String =
        if (text.length <= chars) text else text.take(chars).substringBeforeLast(' ').trimEnd() + " …"

    private const val FOLLOW_UP_LENGTH = 60
}
