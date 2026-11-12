package com.sebrain.core.vault

import net.zetetic.database.sqlcipher.SQLiteDatabase

data class SearchHit(
    val chunkId: Long,
    val captureId: Long,
    val text: String,
    val score: Double,
    /** 1-based rank in the full-text results, or null if it only matched by vector. */
    val textRank: Int?,
    /** 1-based rank in the vector results, or null if it only matched by text. */
    val vectorRank: Int?,
)

/** Full-text and vector search fused with Reciprocal Rank Fusion. Use it inside [VaultSession.withDatabase]. */
class HybridSearch(private val db: SQLiteDatabase) {
    /**
     * @param queryEmbedding the query vector, or null to search by text only.
     */
    fun search(query: String, queryEmbedding: FloatArray?, limit: Int = DEFAULT_LIMIT): List<SearchHit> {
        val textRanks = ftsQuery(query)?.let(::textRanks).orEmpty()
        val vectorRanks = queryEmbedding?.let(::vectorRanks).orEmpty()
        val scored = (textRanks.keys + vectorRanks.keys).map { id ->
            val score = listOfNotNull(textRanks[id], vectorRanks[id]).sumOf { 1.0 / (RRF_K + it) }
            Triple(id, score, textRanks[id] to vectorRanks[id])
        }.sortedWith(compareByDescending<Triple<Long, Double, Pair<Int?, Int?>>> { it.second }.thenBy { it.first }).take(limit)

        return scored.mapNotNull { (id, score, ranks) ->
            db.rawQuery("SELECT capture_id, text FROM chunk WHERE id = ?", id as Any).use { c ->
                if (c.moveToFirst()) SearchHit(id, c.getLong(0), c.getString(1), score, ranks.first, ranks.second) else null
            }
        }
    }

    private fun textRanks(ftsQuery: String): Map<Long, Int> = db.rawQuery(
        "SELECT rowid FROM chunk_fts WHERE chunk_fts MATCH ? ORDER BY bm25(chunk_fts) LIMIT $CANDIDATES",
        ftsQuery as Any,
    ).use { c -> ranks(c) }

    private fun vectorRanks(embedding: FloatArray): Map<Long, Int> {
        require(embedding.size == VaultSchema.EMBEDDING_DIMENSIONS) { "Expected ${VaultSchema.EMBEDDING_DIMENSIONS} dimensions" }
        return db.rawQuery(
            "SELECT rowid FROM chunk_vec WHERE embedding MATCH ? AND k = $CANDIDATES ORDER BY distance",
            ChunkStore.toBlob(embedding) as Any,
        ).use { c -> ranks(c) }
    }

    private fun ranks(c: android.database.Cursor): Map<Long, Int> {
        val ranks = LinkedHashMap<Long, Int>()
        while (c.moveToNext()) ranks[c.getLong(0)] = ranks.size + 1
        return ranks
    }

    companion object {
        const val DEFAULT_LIMIT = 8
        private const val RRF_K = 60
        private const val CANDIDATES = 50
        private val WORD = Regex("[\\p{L}\\p{N}]+")

        /**
         * Turns free text into an FTS5 query: each word quoted, joined with OR, so user input can
         * never be read as FTS5 syntax. Returns null if there is no word to search.
         */
        internal fun ftsQuery(text: String): String? =
            WORD.findAll(text).map { "\"${it.value}\"" }.toList().takeIf { it.isNotEmpty() }?.joinToString(" OR ")
    }
}
