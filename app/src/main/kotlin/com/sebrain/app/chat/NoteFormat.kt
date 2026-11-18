package com.sebrain.app.chat

/** A note taken out of a model reply, ready to be saved. */
data class DraftNote(
    val title: String,
    val body: String,
    val tags: List<String>,
    /** Other names the model gave the topic, not yet filtered: see [NoteAliases.choose]. */
    val aliases: List<String> = emptyList(),
)

/**
 * The format of the notes the assistant writes. It has two halves: [instructions] tell the
 * model the template, and [parse] makes the reply fit it whatever the model did. A small
 * model does not follow a template every time, so the cleaning does not depend on it.
 *
 * One reply can hold several notes: each starts at a `# Title` line.
 */
object NoteFormat {
    const val MAX_NOTES = 5
    private const val MAX_TAGS = 4
    private const val TITLE_CHARS = 80

    private val TITLE_LINE = Regex("^#\\s+(\\S.*)$")
    private val FENCE = Regex("^\\s*(```|~~~)")
    private val TAG_LINE = Regex("^[*_#\\s>-]*(?:tags?|etiquetas?)[*_\\s]*:(.*)$", RegexOption.IGNORE_CASE)
    private val ALIAS_LINE = Regex("^[*_#\\s>-]*(?:aliases|alias|otros nombres)[*_\\s]*:(.*)$", RegexOption.IGNORE_CASE)
    private val NO_ALIAS = setOf("none", "no", "n/a", "ninguno", "ninguna", "-")
    private val TITLE_LABEL =Regex("^(?:title|t[ií]tulo|note|nota)\\s*:\\s*", RegexOption.IGNORE_CASE)
    private val BULLET = Regex("^(\\s*)[*•+]\\s+")
    private val RULE = Regex("^\\s*([-*_])(\\s*\\1){2,}\\s*$")
    private val SPANISH = Regex(
        "\\b(el|la|los|las|una?|del|que|qué|haz|crea|nota|notas|sobre|cada|para|como|cómo|siguiente|explica\\w*)\\b",
        RegexOption.IGNORE_CASE,
    )

    /** What the model is told when its answer will be saved. [message] decides the language of the section names. */
    fun instructions(
        message: String,
        linkable: List<String>,
        tags: List<String>,
        /** The structure of a template the request names; it replaces the default sections. */
        template: String? = null,
    ): String = buildString {
        // The section names follow the language the note will be in: the one asked for by name, or the message's.
        val spanish = when (Assistant.requestedLanguage(message)) {
            "English" -> false
            "Spanish" -> true
            else -> SPANISH.findAll(message).count() >= 2
        }
        val points = if (spanish) "Puntos clave" else "Key points"
        val details = if (spanish) "Detalles" else "Details"
        append("\n\nThe answer will be saved in the vault as a note. If the request asks for several notes, write one ")
        append("note for each topic, one after another, at most $MAX_NOTES. Write only the notes: no introduction, no ")
        append("closing remark, no question, and do not say that you are creating a note.\n")
        if (template != null) {
            // The user's own template, from the Templates folder: its headings, in its order.
            append("Every note follows exactly this template, with its headings in this order; fill each part in:\n\n")
            append(template.trim()).append("\n\n")
        } else {
            append("Every note follows exactly this format:\n\n")
            append("# Title of the topic\n\n")
            append("One or two sentences that say what it is.\n\n")
            append("## $points\n- three to six short bullets\n\n")
            append("## $details\nA short paragraph, a list or a code block. Leave this section out if it adds nothing.\n\n")
        }
        // Other notes link to this one when they use one of these names.
        append("Aliases: up to three other names that mean only this topic (an abbreviation, the command, another common name), ")
        append("not general words, separated by commas, or none\n")
        append("Tags: two to four lowercase tags, separated by commas\n\n")
        append("Rules: the title is the topic itself, at most six words, without words like \"note\" or ")
        append("\"description\". Use \"#\" only for the title of a note and \"##\" for its sections. Every note ends ")
        append("with its own \"Tags:\" line.")
        val titles = linkable.filter { it.isNotBlank() }.distinct()
        if (titles.isNotEmpty()) {
            append("\nExisting notes you may link to: ").append(titles.joinToString(", ") { "[[$it]]" }).append('.')
            append(" Link one only where the new note really builds on it, writing its title exactly as shown.")
        }
        if (tags.isNotEmpty()) {
            append("\nTags already in use: ").append(tags.joinToString(", ")).append(". Reuse them when they fit.")
        }
    }

    /**
     * The notes in [reply], cleaned. Text before the first `# Title` is an introduction and is
     * dropped. A reply without any title line is one note titled [fallbackTitle].
     */
    fun parse(reply: String, fallbackTitle: String): List<DraftNote> {
        val lines = unwrap(reply).lines()
        val starts = titleLines(lines)
        val notes = if (starts.isEmpty()) {
            listOfNotNull(draft(fallbackTitle.lineSequence().firstOrNull().orEmpty(), lines))
        } else {
            starts.mapIndexedNotNull { i, start ->
                val end = starts.getOrElse(i + 1) { lines.size }
                draft(TITLE_LINE.find(lines[start].trim())!!.groupValues[1], lines.subList(start + 1, end))
            }
        }
        return notes.distinctBy { it.title.lowercase() }.take(MAX_NOTES)
    }

    /** Every `# Title` in [text], outside code blocks: the notes or topics a reply wrote. */
    fun titles(text: String): List<String> {
        val lines = text.lines()
        return titleLines(lines).map { cleanTitle(TITLE_LINE.find(lines[it].trim())!!.groupValues[1]) }.filter { it.isNotEmpty() }
    }

    /** Indexes of the `# Title` lines that are not inside a fenced code block, where `#` is a comment. */
    private fun titleLines(lines: List<String>): List<Int> {
        var fenced = false
        return lines.indices.filter { i ->
            if (FENCE.containsMatchIn(lines[i])) fenced = !fenced
            !fenced && TITLE_LINE.matches(lines[i].trim())
        }
    }

    private fun draft(rawTitle: String, lines: List<String>): DraftNote? {
        val kept = lines.toMutableList()
        var tags = emptyList<String>()
        val tagIndex = kept.indexOfLast { TAG_LINE.matches(it.trim()) }
        if (tagIndex >= 0) {
            tags = cleanTags(TAG_LINE.find(kept[tagIndex].trim())!!.groupValues[1])
            kept.removeAt(tagIndex)
        }
        var aliases = emptyList<String>()
        val aliasIndex = kept.indexOfLast { ALIAS_LINE.matches(it.trim()) }
        if (aliasIndex >= 0) {
            aliases = cleanAliases(ALIAS_LINE.find(kept[aliasIndex].trim())!!.groupValues[1])
            kept.removeAt(aliasIndex)
        }
        val title = cleanTitle(rawTitle).ifBlank { "Note" }
        val body = cleanBody(kept, title)
        return if (body.isBlank()) null else DraftNote(title, body, tags, aliases)
    }

    /** Names as written, without Markdown or quotes; the brain-aware filtering is [NoteAliases.choose]. */
    private fun cleanAliases(raw: String): List<String> =
        raw.split(',', ';')
            .map { it.trim().trim('*', '_', '`', '"', '“', '”', '\'', '[', ']', '.', ' ') }
            .filter { it.isNotEmpty() && it.lowercase() !in NO_ALIAS }
            .distinctBy { it.lowercase() }
            .take(NoteAliases.MAX_ALIASES)

    /** Removes a code fence the model put around the whole reply. */
    private fun unwrap(reply: String): String {
        val lines = reply.trim().lines()
        val wrapped = lines.size >= 3 && FENCE.containsMatchIn(lines.first()) && lines.last().trim().let { it == "```" || it == "~~~" } &&
            lines.subList(1, lines.size - 1).none { FENCE.containsMatchIn(it) }
        return if (wrapped) lines.subList(1, lines.size - 1).joinToString("\n") else reply.trim()
    }

    private fun cleanTitle(raw: String): String =
        raw.trim().trim('#', '*', '_', '`', '"', '“', '”', ' ').replace(TITLE_LABEL, "").trim().trimEnd(':', '.').trim().take(TITLE_CHARS)

    private fun cleanTags(raw: String): List<String> =
        // "a, b" and "#a #b" and "a b" are all lists of tags; "os design, kernel" has a two-word tag.
        (if (',' in raw || ';' in raw) raw.split(',', ';').flatMap { it.trim().split(Regex("\\s+#")) } else raw.trim().split(Regex("\\s+")))
            .map { it.trim().trim('*', '_', '#', '.', '`').lowercase().replace(Regex("\\s+"), "-").replace(Regex("[^\\p{L}\\p{N}_/-]"), "") }
            .filter { it.isNotEmpty() && !it.first().isDigit() }
            .distinct().take(MAX_TAGS)

    private fun cleanBody(lines: List<String>, title: String): String {
        var fenced = false
        val cleaned = lines.map { line ->
            if (FENCE.containsMatchIn(line)) {
                fenced = !fenced
                line.trimEnd()
            } else if (fenced) {
                line.trimEnd()
            } else {
                // One list marker everywhere, and no stray top-level heading inside a note.
                BULLET.replace(line.trimEnd()) { "${it.groupValues[1]}- " }
            }
        }.toMutableList()
        // A first line that only repeats the title.
        val first = cleaned.indexOfFirst { it.isNotBlank() }
        if (first >= 0 && cleaned[first].trim().trim('*', '_', '#', ' ').equals(title, ignoreCase = true)) cleaned.removeAt(first)
        // Horizontal rules between notes, and a closing question such as "Do you want another one?".
        while (cleaned.isNotEmpty() && (cleaned.last().isBlank() || RULE.matches(cleaned.last()))) cleaned.removeAt(cleaned.lastIndex)
        if (cleaned.size > 1 && cleaned.last().trim().endsWith("?") && cleaned[cleaned.lastIndex - 1].isBlank()) cleaned.removeAt(cleaned.lastIndex)
        while (cleaned.isNotEmpty() && (cleaned.last().isBlank() || RULE.matches(cleaned.last()))) cleaned.removeAt(cleaned.lastIndex)
        return cleaned.joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim()
    }
}
