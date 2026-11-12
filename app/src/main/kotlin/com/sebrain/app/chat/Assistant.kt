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

/** A message from another saved conversation that matches the current one. */
data class PastMessage(val conversation: String, val date: String, val fromUser: Boolean, val text: String)

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
    const val OUTLINE_CHARS = 200
    const val OUTLINE_LINES = 12
    const val MEMORY_CHARS = 300
    const val PAST_CHARS = 260

    /** Suggested facts about the user, proposed only when the user asks. */
    const val FACTS_MAX = 3
    const val FACTS_MESSAGES = 12
    private const val FACT_MIN_CHARS = 8
    private const val FACT_MAX_CHARS = 200

    /** The model-written summary of the oldest part of a long conversation. */
    const val SUMMARY_TRIGGER = 8
    const val SUMMARY_WORDS = 120
    const val SUMMARY_CHARS = 1000
    const val SUMMARY_LINE_CHARS = 500
    const val SUMMARY_INPUT_CHARS = 6000

    /** Context budget, so the prompt stays well inside the model's context window. */
    const val NOTE_CHARS = 900
    const val CONNECTED_CHARS = 280

    /**
     * System instruction + history + prompt, in characters. Measured on the Pixel 10 with Gemma 4
     * E4B (2026-11-12): the limit is 4096 tokens for input and reply together, and text runs at
     * 3.5 to 4.6 characters per token. 9000 characters is at most about 2600 tokens, which
     * leaves about 1500 for the reply.
     */
    const val INPUT_CHARS = 9000
    private val LEVELS = listOf(
        Triple(NOTE_CHARS, true, true),
        Triple(NOTE_CHARS, false, true),
        Triple(NOTE_CHARS / 2, false, true),
        Triple(NOTE_CHARS / 2, false, false),
        Triple(NOTE_CHARS / 4, false, false),
    )

    private val NOTE_WORD = Regex("\\b(notas?|notes?)\\b", RegexOption.IGNORE_CASE)
    private val SAVE_WORD = Regex(
        "\\b(crea\\w*|haz\\w*|hace\\w*|guard\\w*|anot\\w*|escrib\\w*|gener\\w*|agreg\\w*|añad\\w*|pon\\w*|create|make|save|write|add|put)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val CITATION = Regex("\\s?\\[(\\d+)](?!\\()")
    private val REMEMBER = Regex(
        "^(?:recuerda|recuerde|acu[eé]rdate|ten en cuenta|remember|keep in mind)(?:\\s+(?:de\\s+)?(?:que|that))?[:,]?\\s+(.+)$",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val FACT_LINE = Regex("^(?:[-*•]|\\d+[.)])\\s+(.+)$")
    private val WHITESPACE = Regex("\\s+")
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
     * The conversation as the model remembers it, in layers: [recent] messages word for word,
     * [outline] with one line for each older message, [summary] for the oldest part once the
     * model has summarized it, and [covered], the titles of the notes and answers already
     * written, so "the next one" does not repeat one of them.
     */
    class Memory(
        val recent: List<Line>,
        val outline: List<String>,
        val covered: List<String>,
        val summary: String? = null,
    )

    /**
     * @param summary what the model wrote about the first [summarized] messages, if anything.
     *   Those messages are then left out of the outline.
     */
    fun remember(earlier: List<Line>, summary: String? = null, summarized: Int = 0): Memory {
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
        val hasSummary = !summary.isNullOrBlank()
        val older = earlier.dropLast(recent.size).drop(if (hasSummary) summarized else 0)
        val outline = older.takeLast(OUTLINE_LINES).map { line ->
            val gist = if (line.fromUser) line.text else heading(line.text) ?: line.text
            // The whole message on one line: a list answered earlier must survive in the outline.
            (if (line.fromUser) "- User asked: " else "- You answered: ") +
                shorten(gist.trim().replace(WHITESPACE, " "), OUTLINE_CHARS)
        }
        val covered = earlier.filter { !it.fromUser }.flatMap { NoteFormat.titles(it.text) }.distinctBy { it.lowercase() }
        return Memory(recent.toList(), outline, covered, summary?.takeIf { hasSummary })
    }

    /**
     * How many messages from the start should be covered by the summary now, or null if it is
     * not time yet. A summary is written when [SUMMARY_TRIGGER] messages have left the recent
     * window since the last one, so it costs one extra model call every few turns, not each turn.
     */
    fun summaryDue(earlier: List<Line>, summarized: Int): Int? {
        val older = earlier.size - remember(earlier).recent.size
        return older.takeIf { it - summarized >= SUMMARY_TRIGGER }
    }

    const val SUMMARY_SYSTEM = "You write short, faithful summaries of conversations. You never add information."

    /** Asks for a new summary from the [previous] one and the [lines] that came after it. */
    fun summaryRequest(previous: String?, lines: List<Line>): String = buildString {
        append("Summarize the conversation below between a user and their assistant in at most $SUMMARY_WORDS words, ")
        append("in the language the user writes in. Keep what the user wanted, the names, lists and facts that were ")
        append("stated, the titles of notes that were written, and what was left pending. Write plain sentences, ")
        append("no heading and no preamble.\n\n")
        if (!previous.isNullOrBlank()) append("Summary so far:\n").append(previous.trim()).append("\n\nWhat came next:\n")
        var budget = SUMMARY_INPUT_CHARS
        for (line in lines) {
            val text = shorten(line.text.trim().replace(WHITESPACE, " "), SUMMARY_LINE_CHARS)
            if (text.length > budget) break
            budget -= text.length
            append(if (line.fromUser) "User: " else "Assistant: ").append(text).append('\n')
        }
    }

    /** The first `# Title` of a reply: the note or topic it wrote. Section headings (`##`) do not count. */
    fun heading(text: String): String? = NoteFormat.titles(text).firstOrNull()

    /**
     * A fact the user asks SeBrain to keep, such as "recuerda que prefiero respuestas cortas".
     * Null for anything else, including questions like "¿recuerdas que…?".
     */
    fun factToRemember(message: String): String? {
        if ('?' in message) return null
        return REMEMBER.find(message.trim())?.groupValues?.get(1)?.trim()?.takeIf { it.length >= 3 }
    }

    const val FACTS_SYSTEM = "You extract lasting facts about a user from what the user wrote. You never invent facts."

    /**
     * Asks which lasting facts about the user are worth keeping, from the user's own messages.
     * [known] are the facts already in standing memory, which must not be proposed again.
     */
    fun factsRequest(lines: List<Line>, known: List<String>): String = buildString {
        append("Below are messages a user wrote to their assistant. List at most $FACTS_MAX lasting facts about the user ")
        append("that would help in future conversations: preferences, the names of their things, ongoing projects, ")
        append("constraints. Use only what the user stated. Leave out questions, one-off requests and anything the ")
        append("assistant said. Write one fact per line, starting with \"- \", as a short sentence that stands on its ")
        append("own, in the user's language. If there is nothing worth keeping, write only NONE.\n")
        if (known.isNotEmpty()) {
            append("\nAlready remembered, do not repeat:\n")
            known.forEach { append("- ").append(shorten(it.replace(WHITESPACE, " "), MEMORY_CHARS)).append('\n') }
        }
        append("\nMessages:\n")
        var budget = SUMMARY_INPUT_CHARS
        for (line in lines.filter { it.fromUser }.takeLast(FACTS_MESSAGES)) {
            val text = shorten(line.text.trim().replace(WHITESPACE, " "), SUMMARY_LINE_CHARS)
            if (text.length > budget) break
            budget -= text.length
            append("- ").append(text).append('\n')
        }
    }

    /** The facts in a reply to [factsRequest], without the ones in [exclude] (known or rejected). */
    fun parseFacts(reply: String, exclude: Collection<String>): List<String> {
        val seen = exclude.map { normalize(it) }.toMutableSet()
        return reply.lines()
            .mapNotNull { FACT_LINE.find(it.trim())?.groupValues?.get(1)?.trim()?.trim('*')?.trim() }
            .filter { it.length in FACT_MIN_CHARS..FACT_MAX_CHARS && !it.equals("NONE", ignoreCase = true) }
            .filter { seen.add(normalize(it)) }
            .take(FACTS_MAX)
    }

    private fun normalize(text: String) = text.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    /** The user message the model sees: its memory, the vault notes as a small graph, then the request. */
    fun prompt(
        message: String,
        notes: List<ContextNote>,
        asNote: Boolean,
        tags: List<String>,
        memory: Memory = Memory(emptyList(), emptyList(), emptyList()),
        facts: List<String> = emptyList(),
        past: List<PastMessage> = emptyList(),
    ): String {
        // The model takes 4096 tokens, reply included (measured). The context is cut in order of
        // least value until the input fits: connected notes first, then note length, then the outline.
        val fixed = SYSTEM.length + memory.recent.sumOf { it.text.length }
        var built = ""
        for ((noteChars, withConnected, withOutline) in LEVELS) {
            built = compose(message, notes, asNote, tags, memory, facts, if (withConnected) past else emptyList(), noteChars, withConnected, withOutline)
            if (fixed + built.length <= INPUT_CHARS) break
        }
        return built
    }

    private fun compose(
        message: String,
        allNotes: List<ContextNote>,
        asNote: Boolean,
        tags: List<String>,
        memory: Memory,
        facts: List<String>,
        past: List<PastMessage>,
        noteChars: Int,
        withConnected: Boolean,
        withOutline: Boolean,
    ): String = buildString {
        val notes = if (withConnected) allNotes else allNotes.filter { it.via == null }
        if (facts.isNotEmpty()) {
            append("What the user asked you to always remember:\n")
            facts.forEach { append("- ").append(shorten(it.replace('\n', ' '), MEMORY_CHARS)).append('\n') }
            append('\n')
        }
        memory.summary?.let { append("Summary of this conversation so far:\n").append(shorten(it.trim(), SUMMARY_CHARS)).append("\n\n") }
        if (withOutline && memory.outline.isNotEmpty()) {
            append(if (memory.summary == null) "Earlier in this conversation:\n" else "After that:\n").append(memory.outline.joinToString("\n")).append("\n\n")
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
            found.forEach { append(render(it, noteChars)).append("\n\n") }
        }
        if (connected.isNotEmpty()) {
            append("Connected notes, reached through links or shared tags:\n\n")
            connected.forEach { append(render(it, CONNECTED_CHARS)).append("\n\n") }
        }
        // Seen on the phone: with no note given, the model still cited "[1]".
        if (notes.isEmpty()) append("No note from the vault matches this message, so do not cite any note.\n\n")
        if (past.isNotEmpty()) {
            append("From earlier conversations with the user:\n")
            past.forEach {
                append("- ").append(it.date.take(10)).append(", \"").append(it.conversation).append("\": ")
                append(if (it.fromUser) "the user said: " else "you answered: ")
                append(shorten(it.text.trim().replace(WHITESPACE, " "), PAST_CHARS)).append('\n')
            }
            append('\n')
        }
        append(if (asNote) "Request: " else "Message: ").append(message.trim())
        if (asNote) append(NoteFormat.instructions(message, notes.map { it.title }, tags))
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
