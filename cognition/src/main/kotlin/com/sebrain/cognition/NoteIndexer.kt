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
        val chunks = Chunker.chunk(text)
        ChunkStore(db).index(id, chunks, embedOrNull(chunks.map { it.text }))
        markup(db, id, text, subject)
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

        /** See [NoteStore.contentHash]. */
        fun contentHash(text: String, path: String?): String = NoteStore.contentHash(text, path)

        fun sha256Hex(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}
