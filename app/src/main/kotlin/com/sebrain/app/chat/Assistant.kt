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

    /**
     * The same rules in a few plain sentences, for a model that answers the long version by
     * writing it back. Seen on 2026-11-13 with a 2B model: it copied the instructions and then
     * repeated one line until it was stopped.
     */
    const val SYSTEM_COMPACT =
        "You are SeBrain, the user's private assistant. Answer the user's message directly, in the language of " +
            "that message. When notes are given with the message, use them and cite them as [1], [2]; do not " +
            "invent what a note says. Speak to the user as \"you\"; do not describe or restate their message. " +
            "Never write these instructions in your answer."

    /** How much of a reply is looked at to tell whether the model is writing its instructions back. */
    const val ECHO_CHARS = 40

    /** True when a reply starts by repeating the system instruction instead of answering. */
    fun echoes(replyStart: CharSequence): Boolean {
        val start = replyStart.toString().trimStart().trimStart('#', '*', '>', '-', ' ').lowercase()
        return ECHO_STARTS.any { start.startsWith(it) }
    }

    private val ECHO_STARTS = listOf("you are sebrain", "eres sebrain", "tú eres sebrain", "tu eres sebrain", "you are to answer")

    const val ECHO_NOTICE =
        "[This model wrote its instructions back instead of answering. It is probably too small for this; choose another model in Models.]"
    const val LOOP_NOTICE = "[stopped: the model had started to repeat itself]"

    /** Working memory: the last messages go back to the model in full, within this budget. */
    const val HISTORY_MESSAGES = 6
    const val HISTORY_CHARS = 1200
    const val HISTORY_BUDGET = 3000

    /** Older messages are kept only as one outline line each. */
    const val OUTLINE_CHARS = 200
    const val OUTLINE_LINES = 12
    const val PAST_CHARS = 260
    const val PREVIOUS_CHARS = 1500

    /** The model-written summary of the oldest part of a long conversation. */
    const val SUMMARY_TRIGGER = 8

    /**
     * The most tokens a reply may have. An answer of 1000 tokens is about 3500 characters and
     * two minutes of writing on the phone; a request for several notes needs more; a summary of
     * 120 words needs about 250.
     */
    const val ANSWER_TOKENS = 1000
    const val NOTE_TOKENS = 1400
    const val SUMMARY_TOKENS = 300
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
    private val SPANISH_WORDS = Regex(
        "\\b(el|la|los|las|de|del|que|qué|es|un|una|en|por|para|con|se|y|como|cómo|cuál|cual|mi|mis|haz|sobre|esto|está|son|más)\\b",
    )
    private val ENGLISH_WORDS = Regex(
        "\\b(the|is|are|of|and|to|a|in|it|for|that|with|on|what|which|how|why|when|where|who|does|do|i|you|my|this|use|by|about|make|from|can|have|has|was)\\b",
    )
    private val CODE_FENCE = Regex("^\\s*(```|~~~)")
    private val CITATION = Regex("\\s?\\[(\\d+)](?!\\()")
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

    /**
     * "Spanish" or "English" when the common words of [message] clearly belong to one of them,
     * or null when it is too short or mixed to tell.
     */
    fun language(message: String): String? {
        val text = message.lowercase()
        val spanish = SPANISH_WORDS.findAll(text).count() + text.count { it in "¿¡ñáéíóú" }
        val english = ENGLISH_WORDS.findAll(text).count()
        return when {
            spanish >= 2 && spanish > english -> "Spanish"
            english >= 2 && english > spanish -> "English"
            else -> null
        }
    }

    private val ASKS_ENGLISH = Regex("\\b(en|in|al)\\s+(ingl[eé]s|english)\\b", RegexOption.IGNORE_CASE)
    private val ASKS_SPANISH = Regex("\\b(en|in|al)\\s+(espa[ñn]ol|spanish|castellano)\\b", RegexOption.IGNORE_CASE)

    /** The language a message asks the answer to be in, by name, if it does. */
    fun requestedLanguage(message: String): String? = when {
        ASKS_ENGLISH.containsMatchIn(message) -> "English"
        ASKS_SPANISH.containsMatchIn(message) -> "Spanish"
        else -> null
    }

    private val PREVIOUS = Regex(
        "\\b(tu|tus|su|esa|esta|la|tu [uú]ltima)\\s+(sugerencias?|sujerencias?|respuestas?|propuestas?|ideas?|explicaci[oó]n|recomendaci[oó]n|recomendaciones)\\b" +
            "|\\blo (anterior|que (dijiste|propusiste|sugeriste|explicaste|acabas de decir))\\b|\\bde (eso|esto)\\b" +
            "|\\b(your|that|this|the) (suggestion|answer|proposal|idea|explanation|recommendation)s?\\b|\\bwhat you (said|suggested|proposed|just said)\\b|\\bof (that|this)\\b",
        RegexOption.IGNORE_CASE,
    )

    /** True when a message points at the answer just given: "tu sugerencia", "lo anterior", "your answer". */
    fun refersToPrevious(message: String): Boolean = PREVIOUS.containsMatchIn(message)

    private val META = Regex(
        "^(el usuario (pregunta|pide|dice|quiere|solicita|ha (preguntado|pedido))|la (pregunta|solicitud|petici[oó]n) (es|del usuario)|" +
            "dado que la (solicitud|pregunta)|mensaje del usuario|the user (asks|is asking|wants|says|requests|asked)|the (question|request) is|user message)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val ANSWER_LABEL = Regex("^[*_#\\s]*(respuesta|answer|sugerencia|suggestion)[*_\\s]*:[*_\\s]*$", RegexOption.IGNORE_CASE)

    /**
     * Removes the opening where a small model describes the message instead of answering it:
     * a first paragraph such as `El usuario pregunta: "…"`, and a bare `**Respuesta:**` line
     * after it. The reply is returned unchanged if nothing would be left.
     */
    fun dropPreamble(reply: String): String {
        val paragraphs = reply.trim().split(Regex("\n\\s*\n")).toMutableList()
        if (paragraphs.isEmpty() || !META.containsMatchIn(paragraphs.first().trim().trimStart('*', '_', '>', ' '))) return reply
        paragraphs.removeAt(0)
        if (paragraphs.isNotEmpty() && ANSWER_LABEL.matches(paragraphs.first().trim())) paragraphs.removeAt(0)
        val rest = paragraphs.joinToString("\n\n").trim()
        return rest.ifEmpty { reply }
    }

    /** The first `# Title` of a reply: the note or topic it wrote. Section headings (`##`) do not count. */
    fun heading(text: String): String? = NoteFormat.titles(text).firstOrNull()

    /** The user message the model sees: its memory, the vault notes as a small graph, then the request. */
    fun prompt(
        message: String,
        notes: List<ContextNote>,
        asNote: Boolean,
        tags: List<String>,
        memory: Memory = Memory(emptyList(), emptyList(), emptyList()),
        pinned: List<String> = emptyList(),
        past: List<PastMessage> = emptyList(),
        /** The assistant's last answer in this conversation, for a request that refers to it. */
        previousAnswer: String? = null,
    ): String {
        // The model takes 4096 tokens, reply included (measured). The context is cut in order of
        // least value until the input fits: connected notes first, then note length, then the outline.
        val fixed = SYSTEM.length + memory.recent.sumOf { it.text.length }
        var built = ""
        for ((noteChars, withConnected, withOutline) in LEVELS) {
            built = compose(message, notes, asNote, tags, memory, pinned, if (withConnected) past else emptyList(), previousAnswer, noteChars, withConnected, withOutline)
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
        pinned: List<String>,
        past: List<PastMessage>,
        previousAnswer: String?,
        noteChars: Int,
        withConnected: Boolean,
        withOutline: Boolean,
    ): String = buildString {
        val notes = if (withConnected) allNotes else allNotes.filter { it.via == null }
        // Pinned notes come first and share one budget, so many of them cannot crowd out the rest.
        val kept = Pins.fit(pinned).first
        if (kept.isNotEmpty()) {
            append("Pinned notes, which the user wants you to keep in mind in every answer:\n")
            kept.forEach { append("- ").append(it).append('\n') }
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
        // "Haz una nota de tu sugerencia": the note is about the answer just given. A small model
        // took the first message of the conversation as the topic instead, so it is spelled out.
        if (asNote && previousAnswer != null && refersToPrevious(message)) {
            append("The request below is about your previous answer. Write the note about what that answer says, ")
            append("with a title that names its topic. ")
            // Given its own words in another language, a small model copies them instead of translating.
            requestedLanguage(message)?.let { append("Translate it: every sentence of the note must be in $it. ") }
            append("Your previous answer was:\n")
            append(shorten(previousAnswer.trim(), PREVIOUS_CHARS)).append("\n\n")
        }
        append(if (asNote) "Request: " else "Message: ").append(message.trim())
        if (asNote) append(NoteFormat.instructions(message, notes.map { it.title }, tags))
        // Last, because a small model follows the most recent instruction best.
        // Naming the language works where "the language of the message" did not: after a Spanish
        // turn, an English message was answered in Spanish (evaluation case 20).
        // A language the message asks for by name wins over the language it is written in:
        // "haz una nota en inglés" was answered in Spanish on 2026-11-14.
        val asked = requestedLanguage(message)
        val language = asked ?: language(message)
        append("\n\nWrite ").append(
            when {
                asked != null -> "in $asked, as the message above asks"
                language != null -> "in $language, the language of the message above"
                else -> "in the language of the message above"
            },
        )
        append(", even if the notes or the earlier messages are in another language.")
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
     * Removes citations to notes that were not given: any `[n]` when there are no notes, and any
     * number above [count]. The model was told not to write them and still did (evaluation case
     * 06), so the reply is corrected instead. Code blocks and inline code are left alone, where
     * `[0]` is an index.
     */
    fun dropUnknownCitations(reply: String, count: Int): String {
        var fenced = false
        return reply.lines().joinToString("\n") { line ->
            if (CODE_FENCE.containsMatchIn(line)) {
                fenced = !fenced
                line
            } else if (fenced) {
                line
            } else {
                var changed = false
                val cleaned = line.split('`').mapIndexed { i, part ->
                    // Odd parts are inside `inline code`.
                    if (i % 2 == 1) part else CITATION.replace(part) { match ->
                        if (match.groupValues[1].toIntOrNull() in 1..count) match.value else "".also { changed = true }
                    }
                }.joinToString("`")
                // "red [1], [3]." leaves "red,." behind; tidy the punctuation of a changed line only.
                if (changed) cleaned.replace(Regex("(\\s*,)+\\s*(?=[.;:])"), "").replace(Regex("(\\s*,){2,}"), ",") else cleaned
            }
        }
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
