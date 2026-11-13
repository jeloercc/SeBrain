package com.sebrain.app.chat

import com.sebrain.cognition.NoteMarkup

/**
 * A pinned note is a note the assistant reads in every prompt. The pin is the tag `#pinned`
 * in the note's own text: tags are read from the text, so there is nothing else to store, and
 * the pin travels with a backup and with a Markdown export. Phase 3 moves pins to a table so
 * a note can be pinned in one brain and not in another.
 */
object Pins {
    const val TAG = "pinned"

    /** The tag that meant the same before 2026-11-12; notes with it are migrated once. */
    const val LEGACY_TAG = "memory"

    /** All pinned notes share this many characters of the prompt, newest first. */
    const val BUDGET_CHARS = 1200
    private const val NOTE_CHARS = 600
    private const val MIN_CHARS = 80

    private fun token(tag: String) = Regex("(?<![\\p{L}\\p{N}_#/&=?.:])#$tag(?![\\p{L}\\p{N}_/-])", RegexOption.IGNORE_CASE)
    private val HEADING = Regex("(?m)^#\\s.*$")
    private val SPACE = Regex("\\s+")

    fun isPinned(text: String) = TAG in NoteMarkup.parse(text).tags

    /** [text] with the pin added, as a last line, or unchanged if it is already pinned. */
    fun pin(text: String): String = if (isPinned(text)) text else text.trimEnd() + "\n\n#$TAG"

    /** [text] without the pin. Other tags and the rest of the text are untouched. */
    fun unpin(text: String): String =
        text.lines().joinToString("\n") { line ->
            if (token(TAG).containsMatchIn(line)) token(TAG).replace(line, "").replace(Regex(" {2,}"), " ").trimEnd() else line
        }.trimEnd()

    /** Turns the old `#memory` tag into `#pinned`. Null if the note does not have it. */
    fun migrate(text: String): String? {
        if (LEGACY_TAG !in NoteMarkup.parse(text).tags) return null
        val replaced = token(LEGACY_TAG).replace(text, "#$TAG")
        // A note that had both tags would now say #pinned twice.
        return if (token(TAG).findAll(replaced).count() > 1) pin(unpin(replaced)) else replaced
    }

    /** What the model reads of a pinned note: its title and its text on one line, without the pin. */
    fun line(title: String, text: String): String {
        val body = unpin(HEADING.replace(text, "")).replace(SPACE, " ").trim()
        val name = title.trim().removePrefix("Memory:").trim()
        // A title cut with "…" that only repeats the start of the text adds nothing.
        val stem = name.trimEnd('…', '.', ' ')
        return if (stem.isEmpty() || body.startsWith(stem, ignoreCase = true)) body else "$name: $body"
    }

    /**
     * The pinned lines that fit [BUDGET_CHARS], in order, a long one shortened. The second value
     * is how many were left out, so the app can say so instead of dropping them silently.
     */
    fun fit(lines: List<String>): Pair<List<String>, Int> {
        var remaining = BUDGET_CHARS
        val kept = mutableListOf<String>()
        for (line in lines.filter { it.isNotBlank() }) {
            if (remaining < MIN_CHARS) break
            val text = Assistant.shorten(line, minOf(remaining, NOTE_CHARS))
            kept += text
            remaining -= text.length
        }
        return kept to (lines.count { it.isNotBlank() } - kept.size)
    }
}
