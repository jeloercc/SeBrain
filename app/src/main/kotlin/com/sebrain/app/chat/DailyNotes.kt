package com.sebrain.app.chat

import java.text.Normalizer
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Daily notes the agent writes, as in Obsidian: one note per day, titled with its date
 * (`Daily/2026-11-18`), that grows through the day. "Save this in today's note" appends the
 * agent's last answer; "add to my daily note: …" appends what follows the colon. Recall by time
 * already finds these notes.
 */
object DailyNotes {
    /** [text] to append, or null for the agent's last answer. */
    data class Request(val text: String?)

    const val FOLDER = "Daily"
    const val TEMPLATE = "Daily"
    private val HOUR = DateTimeFormatter.ofPattern("HH:mm")

    private val TODAYS_NOTE = Regex("\\b(today'?s note|daily note|note (for|of) today|nota de hoy|nota diaria|diario de hoy)\\b")
    private val SAVE = Regex("\\b(save|add|append|put|write|log|record|note down|guarda\\w*|anad\\w*|agreg\\w*|apunt\\w*|anot\\w*|escrib\\w*|pon\\w*|mete\\w*)\\b")

    /**
     * "Apunta que mañana llamo al banco", "write down that…": a quick entry for today's note. Seen
     * on 2026-11-21: without this the model answered "¡Claro! Anoto…" and saved nothing.
     */
    private val QUICK = Regex("(?i)^\\s*(?:por favor,? |please )?(?:ap[uú]nta(?:me)?|an[oó]ta(?:me)?|write down|jot down|note down|make a note) (?:que |that )?(.+)$")

    /** The request [message] makes, or null if it does not ask to save into today's note. */
    fun request(message: String): Request? {
        QUICK.matchEntire(message.trim())?.let { quick ->
            val entry = quick.groupValues[1].trim()
            if (!entry.endsWith("?") && !entry.startsWith("about ") && !entry.startsWith("sobre ")) return Request(entry)
        }
        val text = fold(message)
        if (!TODAYS_NOTE.containsMatchIn(text) || !SAVE.containsMatchIn(text)) return null
        // "What is in today's note?" asks about it; it does not save.
        if (text.trimEnd().endsWith("?")) return null
        val after = message.substringAfter(':', "").trim()
        return Request(after.ifEmpty { null })
    }

    /** Where today's note lives: in the brain's folder, or at the root of the vault. */
    fun path(today: LocalDate, brain: String?): String =
        listOfNotNull(brain, FOLDER, today.toString()).joinToString("/")

    /** [noteText] with [entry] appended under the time it was written. */
    fun appended(noteText: String, entry: String, time: LocalTime): String =
        noteText.trimEnd() + "\n\n### " + time.format(HOUR) + "\n\n" + entry.trim() + "\n"

    /** Today's note, made from the user's Daily [template] if there is one, with its first entry. */
    fun newNote(today: LocalDate, template: Templates.NoteTemplate?, entry: String, time: LocalTime): String {
        val start = template?.let { Templates.fill(it.text, today.toString(), LocalDateTime.of(today, time)).trim() } ?: "# $today"
        return appended(start, entry, time)
    }

    private fun fold(text: String): String =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
}
