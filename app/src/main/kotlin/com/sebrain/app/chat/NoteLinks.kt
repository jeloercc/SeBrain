package com.sebrain.app.chat

import com.sebrain.cognition.NoteMarkup

/**
 * Connects a note written in the chat with the rest of the brain, by the app and not by the model:
 * a small model forgets links it was told about. Both directions use [NoteEdits.linked], which
 * links only whole-word mentions of a title or alias, never inside code or an existing link.
 */
object NoteLinks {
    /**
     * [body] with every existing note it names linked, and how many links were added. [names] are
     * the brain's (note id, title, name) for every title and alias; the note titled [title] is skipped.
     */
    fun outgoing(body: String, title: String, names: List<Triple<Long, String, String>>): Pair<String, Int> {
        val others = names.groupBy({ it.second }, { it.third })
            .filterKeys { !it.equals(title.trim(), ignoreCase = true) }
            .map { (noteTitle, noteNames) -> noteTitle to noteNames.distinct() }
        val candidates = NoteEdits.Names(others).candidatesFor(body)
        val linked = NoteEdits.linked(body, candidates)?.text ?: return body to 0
        return linked to (links(linked) - links(body)).coerceAtLeast(0)
    }

    /**
     * [text] of an existing note with its mention of the new note ([title] or one of [aliases])
     * linked, or null if it does not name it or already links to it.
     */
    fun backlinked(text: String, title: String, aliases: List<String>): String? =
        NoteEdits.linked(text, listOf(title to (listOf(title) + aliases).distinct()))?.text

    private fun links(text: String) = NoteMarkup.parse(text).links.size
}
