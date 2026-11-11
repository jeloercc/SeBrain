package com.sebrain.cognition

import com.sebrain.core.vault.ChunkStore
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
    /** Rebuilds the index of one note. Call it inside a transaction with the write that changed the text. */
    fun index(db: SQLiteDatabase, id: Long, text: String, subject: String? = null) {
        val chunks = Chunker.chunk(text)
        ChunkStore(db).index(id, chunks, embedOrNull(chunks.map { it.text }))
        markup(db, id, text, subject)
    }

    /** Extracts title, tags and links only. */
    fun markup(db: SQLiteDatabase, id: Long, text: String, subject: String? = null) {
        val parsed = NoteMarkup.parse(text)
        NoteStore(db).setMarkup(id, NoteMarkup.titleOf(text, subject), parsed.tags, parsed.links)
    }

    /** A failing model must not block a write: the text is still stored and searchable. */
    fun embedOrNull(texts: List<String>): List<FloatArray>? =
        if (texts.isEmpty()) emptyList() else runCatching { embed(texts) }.getOrNull()?.takeIf { it.size == texts.size }

    companion object {
        fun sha256Hex(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}
