package com.sebrain.app

import com.sebrain.app.chat.Assistant
import com.sebrain.app.chat.ContextNote
import com.sebrain.app.chat.NoteToolSet
import com.sebrain.app.chat.RelevanceGate
import com.sebrain.app.chat.VaultContext
import com.sebrain.cognition.FrontMatter
import com.sebrain.core.vault.NoteStore

/**
 * Runs the model's note tools ([NoteToolSet]) for one reply, read-only and inside one brain: a
 * search goes through [VaultContext] with the personal relevance gate, a read returns one note
 * whole. Every note a tool shows the model gets one number for the whole reply, so the model's
 * [1], [2] point at [notes] and the sources chip can open them. Drafting a note is left to the
 * chat's own "make a note" flow, which has the review screen.
 *
 * Called on the chat thread; the vault and embedder are safe to use from it.
 */
class NoteToolRunner(private val app: SeBrainApp, private val brain: String?) {
    private val found = LinkedHashMap<Long, ContextNote>()

    /** The notes the tools showed, numbered as the model saw them. */
    val notes: List<ContextNote> get() = found.values.toList()

    fun run(name: String, arguments: Map<String, Any?>): String = when (name) {
        NoteToolSet.SEARCH -> search(arguments["query"]?.toString().orEmpty())
        NoteToolSet.READ -> read(arguments["title"]?.toString().orEmpty())
        NoteToolSet.CREATE -> "Notes are not written from here. Tell the user they can say \"make a note about…\" to save it."
        else -> "Unknown tool \"$name\"."
    }

    private fun search(query: String): String {
        if (query.isBlank()) return "Give a few words to search for."
        val vector = if (app.embedder.isAvailable) runCatching { app.embedder.embedQuery(query) }.getOrNull() else null
        val hits = app.session.withDatabase { db -> VaultContext.build(db, query, vector, brain = brain, maxDistance = RelevanceGate.PERSONAL).notes }
        if (hits.isEmpty()) return "No note matches \"$query\"."
        return hits.take(MAX_RESULTS).joinToString("\n\n") { render(it) }
    }

    private fun read(title: String): String {
        val note = app.session.withDatabase { db ->
            val store = NoteStore(db, brain)
            store.names().firstOrNull { it.third.equals(title.trim(), ignoreCase = true) }?.let { store.get(it.first) }
        } ?: return "No note is titled \"$title\"."
        return render(ContextNote(0, note.id, note.title, note.tags, emptyList(), emptyList(), FrontMatter.body(note.text)), READ_CHARS)
    }

    /** The note as the model sees it, under its number for this reply. */
    private fun render(note: ContextNote, chars: Int = Assistant.NOTE_CHARS): String {
        val numbered = found.getOrPut(note.id) { note.copy(number = found.size + 1) }
        return Assistant.render(numbered.copy(text = note.text), chars)
    }

    private companion object {
        const val MAX_RESULTS = 4
        const val READ_CHARS = 4000
    }
}
