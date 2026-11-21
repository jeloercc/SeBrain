package com.sebrain.app.chat

import com.sebrain.cognition.FrontMatter
import java.text.Normalizer
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Note templates, as in Obsidian's core Templates plugin: notes in the vault's `Templates/`
 * folder. When a request for a note names one ("a meeting note"), the agent writes in its
 * structure, and the saved note gets its properties. Supported variables: `{{title}}`,
 * `{{date}}`, `{{time}}`, and `{{date:FORMAT}}` / `{{time:FORMAT}}` with Moment.js tokens.
 */
object Templates {
    data class NoteTemplate(val title: String, val text: String)

    /** What `{{title}}` reads as in the structure the model gets: it writes the title itself. */
    private const val TITLE_FOR_MODEL = "Title of the topic"

    /** Properties that come from the new note itself, not from its template. */
    private val OWN_KEYS = setOf("tags", "tag", "aliases", "alias", "source", "created")

    private val VARIABLE = Regex("\\{\\{\\s*(title|date|time)\\s*(?::([^}]*))?\\}\\}")
    private val MOMENT_TOKEN = Regex("YYYY|YY|MMMM|MMM|MM|M|DD|D|dddd|ddd|HH|H|hh|h|mm|ss|A")
    private val MOMENT_TO_JAVA = mapOf(
        "YYYY" to "yyyy", "YY" to "yy", "MMMM" to "MMMM", "MMM" to "MMM", "MM" to "MM", "M" to "M",
        "DD" to "dd", "D" to "d", "dddd" to "EEEE", "ddd" to "EEE", "HH" to "HH", "H" to "H",
        "hh" to "hh", "h" to "h", "mm" to "mm", "ss" to "ss", "A" to "a",
    )
    private val WORD = Regex("[\\p{L}\\p{N}]+")
    private val MARKS = Regex("\\p{M}+")

    /** The template whose title [message] names, the longest title first; null if none. */
    fun chosen(message: String, templates: List<NoteTemplate>): NoteTemplate? {
        val text = " ${words(message)} "
        return templates.filter { words(it.title).isNotEmpty() && text.contains(" ${words(it.title)} ") }
            .maxByOrNull { words(it.title).length }
    }

    /** [text] with the variables filled in; an unknown variable is left as written. */
    fun fill(text: String, title: String, now: LocalDateTime): String = VARIABLE.replace(text) { match ->
        val format = match.groupValues[2].trim()
        when (match.groupValues[1]) {
            "title" -> title
            "date" -> format(now, format.ifEmpty { "YYYY-MM-DD" }) ?: match.value
            else -> format(now, format.ifEmpty { "HH:mm" }) ?: match.value
        }
    }

    /** The structure the model follows: the template's body, without its properties, dates filled in. */
    fun structure(template: NoteTemplate, now: LocalDateTime): String =
        fill(FrontMatter.body(template.text).trim(), TITLE_FOR_MODEL, now)

    /** The template's properties for a note titled [title], filled in. Tags and aliases are the note's own. */
    fun properties(template: NoteTemplate, title: String, now: LocalDateTime): List<Pair<String, List<String>>> =
        FrontMatter.parse(template.text).properties
            .filter { it.key.lowercase() !in OWN_KEYS }
            .map { property -> property.key to property.values.map { fill(it, title, now) } }

    /** Formats [now] with a Moment.js pattern; null if it cannot be read. */
    private fun format(now: LocalDateTime, moment: String): String? = runCatching {
        val java = buildString {
            var last = 0
            for (token in MOMENT_TOKEN.findAll(moment)) {
                append(quoted(moment.substring(last, token.range.first)))
                append(MOMENT_TO_JAVA.getValue(token.value))
                last = token.range.last + 1
            }
            append(quoted(moment.substring(last)))
        }
        DateTimeFormatter.ofPattern(java, Locale.ENGLISH).format(now)
    }.getOrNull()

    /** Literal text in a Java date pattern: letters are quoted, so they are not read as fields. */
    private fun quoted(text: String): String =
        if (text.none { it.isLetter() || it == '\'' }) text else "'" + text.replace("'", "''") + "'"

    private fun words(text: String): String =
        WORD.findAll(MARKS.replace(Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD), "")).joinToString(" ") { it.value }
}
