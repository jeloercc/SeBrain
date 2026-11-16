package com.sebrain.app.chat

import com.sebrain.cognition.FrontMatter
import com.sebrain.core.vault.ConversationStore
import com.sebrain.core.vault.GraphRank
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
        /** The user's message as written, to see whether it names a note. */
        message: String = query,
        /** The brain to read from, or null for the universal brain. See [NoteStore]. */
        brain: String? = null,
        /** The brain's row id, for its conversations; null with the universal brain. */
        brainId: Long? = null,
    ): Result {
        val store = NoteStore(db, brain)
        val excluded = exclude.map { it.lowercase() }.toSet()
        // "¿Qué dice la nota Protocolo SSH?": the note a message names is read, first and whole.
        // Search alone may rank it low, or give only one of its chunks.
        val named = NoteEdits.mentioned(message, store.names().map { it.first to it.third })?.let(store::get)
            ?.takeIf { it.title.lowercase() !in excluded }
        // Several chunks of one note can match; keep the note once, with its best chunks in order.
        val chunksByNote = LinkedHashMap<Long, MutableList<String>>()
        for (hit in HybridSearch(db, brain).search(query, vector, SEARCH_HITS)) {
            chunksByNote.getOrPut(hit.captureId) { mutableListOf() }.add(hit.text)
        }
        val notes = mutableListOf<ContextNote>()
        val included = HashSet<Long>()
        if (named != null) {
            included += named.id
            notes += ContextNote(
                1, named.id, named.title, named.tags,
                store.linkedFrom(named.id).mapNotNull { it.title }, store.backlinks(named.id).mapNotNull { it.title },
                FrontMatter.body(named.text), focus = true,
            )
        }
        for ((id, chunks) in chunksByNote) {
            if (notes.size == MATCHED_NOTES) break
            if (id in included) continue
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
                // The model reads the note's own text; its properties block is left out.
                text = FrontMatter.body(note.text).let { body -> if (body.length <= Assistant.NOTE_CHARS) body else chunks.distinct().joinToString("\n…\n") },
            )
        }

        // Connected notes are chosen by Personalized PageRank over the brain's links and shared
        // tags, seeded by the notes the search found (better hits weigh more). A note two links
        // away can win when it is central to what was found; a note one link away from a weak
        // hit can lose.
        val matched = notes.toList()
        if (matched.isNotEmpty()) {
            val edges = store.rankEdges()
            val seeds = matched.associate { it.id to 1.0 / (RRF_K + it.number) }
            val ranked = GraphRank.personalized(edges, seeds).map { it.first }.filter { it !in included }
            val adjacent = edges.groupBy({ it.a }, { it.b to it.weight }) + edges.groupBy({ it.b }, { it.a to it.weight })
            for (id in ranked) {
                if (notes.size - matched.size >= CONNECTED_NOTES) break
                val note = store.get(id) ?: continue
                if (note.title.lowercase() in excluded || Pins.TAG in note.tags) continue
                included += id
                notes += ContextNote(notes.size + 1, note.id, note.title, note.tags, emptyList(), emptyList(), FrontMatter.body(note.text), relation(store, note.id, matched, adjacent))
            }
        }
        // Newest first. A pinned note is not also listed as a search result above.
        val pinned = store.withTag(Pins.TAG, PINNED_NOTES).map { Pins.line(it.title.orEmpty(), it.text) }
        val past = ConversationStore(db, brainId).search(query, PAST_MESSAGES, conversationId)
            .map { PastMessage(it.title, it.updatedAt, it.fromUser, it.text) }
        return Result(notes, store.tags().take(TAGS_SHOWN).map { it.first }, pinned, past)
    }

    /** How a connected note relates to the notes the search found, for the label the model reads. */
    private fun relation(
        store: NoteStore,
        id: Long,
        matched: List<ContextNote>,
        adjacent: Map<Long, List<Pair<Long, Double>>>,
    ): String {
        val links = store.linkedFrom(id).map { it.id }.toSet()
        val backlinks = store.backlinks(id).map { it.id }.toSet()
        for (origin in matched) {
            if (origin.id in backlinks) return "linked from [${origin.number}]"
            if (origin.id in links) return "links to [${origin.number}]"
        }
        val tagged = matched.firstOrNull { origin -> adjacent[id].orEmpty().any { it.first == origin.id } }
        if (tagged != null) return "shares tags with [${tagged.number}]"
        return "connected through the graph to [${matched.first().number}]"
    }

    private const val RRF_K = 60
}
