package com.sebrain.core.vault

import java.nio.ByteBuffer
import java.nio.ByteOrder
import net.zetetic.database.sqlcipher.SQLiteDatabase

/** A piece of a capture's text. Offsets are character positions in the capture text. */
data class Chunk(val position: Int, val startOffset: Int, val endOffset: Int, val text: String)

/** A chunk that still needs its vector. */
data class PendingChunk(val id: Long, val captureId: Long, val text: String)

/** Writes the search index. Use it inside [VaultSession.withDatabase]. */
class ChunkStore(private val db: SQLiteDatabase) {
    /**
     * Stores the chunks of a capture with their full-text entries and, if given, their vectors,
     * in one transaction. [embeddings] must match [chunks] one to one.
     */
    fun index(captureId: Long, chunks: List<Chunk>, embeddings: List<FloatArray>?) {
        require(embeddings == null || embeddings.size == chunks.size) { "One embedding per chunk is required" }
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM chunk_vec WHERE rowid IN (SELECT id FROM chunk WHERE capture_id = ?)", arrayOf<Any>(captureId))
            db.execSQL(
                "INSERT INTO chunk_fts(chunk_fts, rowid, text) SELECT 'delete', id, text FROM chunk WHERE capture_id = ?",
                arrayOf<Any>(captureId),
            )
            db.execSQL("DELETE FROM chunk WHERE capture_id = ?", arrayOf<Any>(captureId))
            for ((i, chunk) in chunks.withIndex()) {
                val id = db.compileStatement(
                    "INSERT INTO chunk(capture_id, position, start_offset, end_offset, text) VALUES (?, ?, ?, ?, ?)",
                ).use { statement ->
                    statement.bindLong(1, captureId)
                    statement.bindLong(2, chunk.position.toLong())
                    statement.bindLong(3, chunk.startOffset.toLong())
                    statement.bindLong(4, chunk.endOffset.toLong())
                    statement.bindString(5, chunk.text)
                    statement.executeInsert()
                }
                db.execSQL("INSERT INTO chunk_fts(rowid, text) VALUES (?, ?)", arrayOf<Any>(id, chunk.text))
                if (embeddings != null) insertVector(id, embeddings[i])
            }
            setStatus(captureId, if (embeddings != null) STATUS_INDEXED else STATUS_TEXT)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Chunks stored without a vector, oldest first. */
    fun chunksWithoutVector(limit: Int): List<PendingChunk> = db.rawQuery(
        "SELECT id, capture_id, text FROM chunk WHERE id NOT IN (SELECT rowid FROM chunk_vec) ORDER BY id LIMIT $limit",
        emptyArray<String>(),
    ).use { c -> generateSequence { if (c.moveToNext()) PendingChunk(c.getLong(0), c.getLong(1), c.getString(2)) else null }.toList() }

    /** Adds the vector of a stored chunk and marks its capture as indexed once all its chunks have one. */
    fun addVector(chunk: PendingChunk, embedding: FloatArray) {
        db.beginTransaction()
        try {
            insertVector(chunk.id, embedding)
            val missing = db.rawQuery(
                "SELECT count(*) FROM chunk WHERE capture_id = ? AND id NOT IN (SELECT rowid FROM chunk_vec)",
                chunk.captureId as Any,
            ).use { c -> c.moveToFirst(); c.getLong(0) }
            if (missing == 0L) setStatus(chunk.captureId, STATUS_INDEXED)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun statusOf(captureId: Long): String? = db.rawQuery(
        "SELECT index_status FROM capture WHERE id = ?", captureId as Any,
    ).use { c -> if (c.moveToFirst()) c.getString(0) else null }

    private fun insertVector(chunkId: Long, embedding: FloatArray) {
        require(embedding.size == VaultSchema.EMBEDDING_DIMENSIONS) { "Expected ${VaultSchema.EMBEDDING_DIMENSIONS} dimensions" }
        db.execSQL("INSERT INTO chunk_vec(rowid, embedding) VALUES (?, ?)", arrayOf<Any>(chunkId, toBlob(embedding)))
    }

    private fun setStatus(captureId: Long, status: String) =
        db.execSQL("UPDATE capture SET index_status = ? WHERE id = ?", arrayOf<Any>(status, captureId))

    companion object {
        const val STATUS_PENDING = "pending"
        const val STATUS_TEXT = "text"
        const val STATUS_INDEXED = "indexed"

        internal fun toBlob(vector: FloatArray): ByteArray =
            ByteBuffer.allocate(vector.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
                vector.forEach { putFloat(it) }
            }.array()
    }
}
