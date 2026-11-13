package com.sebrain.app.chat

import com.sebrain.core.vault.ConversationStore
import com.sebrain.core.vault.HybridSearch
import com.sebrain.core.vault.NoteStore
import net.zetetic.database.sqlcipher.SQLiteDatabase

/**
 * Builds the assistant's context from the vault as a small graph: the notes that best match
 * the message (hybrid search, best chunks grouped by note), then their neighbours one step away
 * through `[[links]]`, backlinks and shared tags. It also brings the pinned notes
 * and the best matches from other saved conversations. Runs on the vault thread.
 */
object VaultContext {
    private const val SEARCH_HITS = 16
    private const val MATCHED_NOTES = 4
    private const val CONNECTED_NOTES = 4
    private const val EXPANDED_FROM = 3
    private const val TAGS_SHOWN = 30

    /** Pinned notes read at most; the prompt keeps the ones that fit [Pins.BUDGET_CHARS]. */
    private const val PINNED_NOTES = 20

    private const val PAST_MESSAGES = 2

    class Result(val notes: List<ContextNote>, val tags: List<String>, val pinned: List<String>, val past: List<PastMessage>)

    /**
     * [exclude] holds titles to leave out: the notes already written in this conversation, when
     * the user asks for a new one, so the model cannot copy them.
     */
    fun build(
        db: SQLiteDatabase,
        query: String,
        vector: FloatArray?,
        exclude: Collection<String> = emptyList(),
        /** The open conversation, which is not searched: the model already has it. */
        conversationId: Long? = null,
    ): Result {
        val store = NoteStore(db)
        val excluded = exclude.map { it.lowercase() }.toSet()
        // Several chunks of one note can match; keep the note once, with its best chunks in order.
        val chunksByNote = LinkedHashMap<Long, MutableList<String>>()
        for (hit in HybridSearch(db).search(query, vector, SEARCH_HITS)) {
            chunksByNote.getOrPut(hit.captureId) { mutableListOf() }.add(hit.text)
        }
        val notes = mutableListOf<ContextNote>()
        val included = HashSet<Long>()
        for ((id, chunks) in chunksByNote) {
            if (notes.size == MATCHED_NOTES) break
            val note = store.get(id) ?: continue
            included += id
            if (note.title.lowercase() in excluded || Pins.TAG in note.tags) continue
            notes += ContextNote(
                number = notes.size + 1,
                id = id,
                title = note.title,
                tags = note.tags,
                linksTo = store.linkedFrom(id).mapNotNull { it.title },
                linkedFrom = store.backlinks(id).mapNotNull { it.title },
                // A short note is given whole; a long one by its matching chunks.
                text = if (note.text.length <= Assistant.NOTE_CHARS) note.text else chunks.distinct().joinToString("\n…\n"),
            )
        }

        val matched = notes.toList()
        for (origin in matched.take(EXPANDED_FROM)) {
            val neighbours = store.linkedFrom(origin.id).map { it to "linked from [${origin.number}]" } +
                store.backlinks(origin.id).map { it to "links to [${origin.number}]" } +
                store.sharingTags(origin.id, CONNECTED_NOTES).map { it to "shares tags with [${origin.number}]" }
            for ((summary, via) in neighbours) {
                if (notes.size - matched.size >= CONNECTED_NOTES) break
                if (!included.add(summary.id)) continue
                val note = store.get(summary.id) ?: continue
                if (note.title.lowercase() in excluded || Pins.TAG in note.tags) continue
                notes += ContextNote(notes.size + 1, note.id, note.title, note.tags, emptyList(), emptyList(), note.text, via)
            }
        }
        // Newest first. A pinned note is not also listed as a search result above.
        val pinned = store.withTag(Pins.TAG, PINNED_NOTES).map { Pins.line(it.title.orEmpty(), it.text) }
        val past = ConversationStore(db).search(query, PAST_MESSAGES, conversationId)
            .map { PastMessage(it.title, it.updatedAt, it.fromUser, it.text) }
        return Result(notes, store.tags().take(TAGS_SHOWN).map { it.first }, pinned, past)
    }
}
