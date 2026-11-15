package com.sebrain.cognition

import com.sebrain.core.vault.ChunkStore
import com.sebrain.core.vault.NoteLink
import com.sebrain.core.vault.NoteProperty
import com.sebrain.core.vault.NoteStore
import java.security.MessageDigest
import net.zetetic.database.sqlcipher.SQLiteDatabase

/**
 * Derives everything searchable from a note's text: chunks, full text, vectors, title, tags
 * and links. It is used for imported captures and for notes edited in the app.
 */
class NoteIndexer(
    /** Embeds texts, or returns null if no embedding model is available. */
    private val embed: (List<String>) -> List<FloatArray>? = { null },
) {
    data class RebuildResult(val notes: Int, val vectors: Int)

    /** Rebuilds the index of one note. Call it inside a transaction with the write that changed the text. */
    fun index(db: SQLiteDatabase, id: Long, text: String, subject: String? = null) {
        // The markup comes first: the chunk headers use the note's stored title.
        markup(db, id, text, subject)
        val parsed = NoteMarkup.parse(text)
        val title = NoteStore(db).get(id)?.title?.takeIf { it.isNotBlank() } ?: NoteMarkup.titleOf(text, subject)
        // "pinned" says how the assistant treats the note, not what the note is about.
        val tags = parsed.tags.filter { it != PINNED_TAG }
        // Each chunk is stored, searched and embedded with its header (see ChunkHeader), so it is
        // found by its note's title and section, and whoever reads it knows where it is from.
        val chunks = Chunker.chunk(text).map { chunk ->
            chunk.copy(text = ChunkHeader.of(text, title, parsed.aliases, tags, chunk.startOffset) + "\n" + chunk.text)
        }
        ChunkStore(db).index(id, chunks, embedOrNull(chunks.map { it.text }))
    }

    /** Reads the title, tags, links, aliases and properties of a note, without touching its search chunks. */
    fun markup(db: SQLiteDatabase, id: Long, text: String, subject: String? = null) {
        val parsed = NoteMarkup.parse(text)
        NoteStore(db).setMarkup(
            id,
            NoteMarkup.titleOf(text, subject),
            parsed.tags,
            parsed.linkDetails.map { NoteLink(it.target, it.anchor, it.embed) },
            parsed.aliases,
            // The lists of tags and aliases have their own tables; the rest are the note's properties.
            parsed.properties.filterNot { it.key.lowercase() in STRUCTURAL }
                .map { NoteProperty(it.key, it.type.name.lowercase(), it.values, it.numbers) },
        )
    }

    /** A failing model must not block a write: the text is still stored and searchable. */
    fun embedOrNull(texts: List<String>): List<FloatArray>? =
        if (texts.isEmpty()) emptyList() else runCatching { embed(texts) }.getOrNull()?.takeIf { it.size == texts.size }

    /** Rebuilds derived search data without changing capture text or provenance. */
    fun rebuildAll(db: SQLiteDatabase, batchSize: Int = 32): RebuildResult {
        require(batchSize > 0) { "Batch size must be positive" }
        var notes = 0
        while (true) {
            val pending = com.sebrain.core.vault.CaptureStore(db).withoutIndex(batchSize)
            if (pending.isEmpty()) break
            pending.forEach { (id, text) ->
                index(db, id, text)
                notes++
            }
        }

        var vectors = 0
        while (true) {
            val pending = ChunkStore(db).chunksWithoutVector(batchSize)
            if (pending.isEmpty()) break
            val embeddings = embedOrNull(pending.map { it.text }) ?: break
            pending.forEachIndexed { index, chunk ->
                ChunkStore(db).addVector(chunk, embeddings[index])
                vectors++
            }
        }
        return RebuildResult(notes, vectors)
    }

    companion object {
        private val STRUCTURAL = setOf("tags", "tag", "aliases", "alias")
        private const val PINNED_TAG = "pinned"

        /**
         * The version of what [index] stores for a note. It changes when the stored chunks change
         * shape, so the app knows to index every note again. 2: chunks carry a header.
         */
        const val INDEX_VERSION = 2

        /** See [NoteStore.contentHash]. */
        fun contentHash(text: String, path: String?): String = NoteStore.contentHash(text, path)

        fun sha256Hex(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}
