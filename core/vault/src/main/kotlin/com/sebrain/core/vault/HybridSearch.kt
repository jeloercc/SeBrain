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
    /**
     * Distance between the chunk and the query vector (L2 over unit vectors: 0 is the same
     * meaning, about 1.41 unrelated), or null if it only matched by text. Unlike the ranks, it
     * says how related a hit is, not only which hit is first.
     */
    val vectorDistance: Double? = null,
)

/** Full-text and vector search fused with Reciprocal Rank Fusion. Use it inside [VaultSession.withDatabase]. */
class HybridSearch(
    private val db: SQLiteDatabase,
    /** Keeps the hits of this brain's notes only; null searches every note. See [NoteStore]. */
    private val brain: String? = null,
) {
    /**
     * @param queryEmbedding the query vector, or null to search by text only.
     */
    fun search(query: String, queryEmbedding: FloatArray?, limit: Int = DEFAULT_LIMIT): List<SearchHit> {
        val textRanks = ftsQuery(query)?.let(::textRanks).orEmpty()
        val vectorHits = queryEmbedding?.let(::vectorHits).orEmpty()
        val vectorRanks = vectorHits.keys.withIndex().associate { (i, id) -> id to i + 1 }
        val scored = (textRanks.keys + vectorRanks.keys).map { id ->
            val score = listOfNotNull(textRanks[id], vectorRanks[id]).sumOf { 1.0 / (RRF_K + it) }
            Triple(id, score, textRanks[id] to vectorRanks[id])
        }.sortedWith(compareByDescending<Triple<Long, Double, Pair<Int?, Int?>>> { it.second }.thenBy { it.first })

        // The brain is applied after the ranking, so a narrow brain may get fewer than [limit]
        // hits from the candidates; the ranking itself is the same.
        val prefix = brain?.let { "$it/" }
        return scored.asSequence().mapNotNull { (id, score, ranks) ->
            db.rawQuery("SELECT ch.capture_id, ch.text, c.path FROM chunk ch JOIN capture c ON c.id = ch.capture_id WHERE ch.id = ?", id as Any).use { c ->
                if (!c.moveToFirst()) return@use null
                val path = if (c.isNull(2)) null else c.getString(2)
                if (prefix != null && (path == null || !path.startsWith(prefix, ignoreCase = true))) return@use null
                SearchHit(id, c.getLong(0), c.getString(1), score, ranks.first, ranks.second, vectorHits[id])
            }
        }.take(limit).toList()
    }

    private fun textRanks(ftsQuery: String): Map<Long, Int> = db.rawQuery(
        "SELECT rowid FROM chunk_fts WHERE chunk_fts MATCH ? ORDER BY bm25(chunk_fts) LIMIT $CANDIDATES",
        ftsQuery as Any,
    ).use { c -> ranks(c) }

    /** Nearest chunks by meaning, closest first, with their distance. */
    private fun vectorHits(embedding: FloatArray): Map<Long, Double> {
        require(embedding.size == VaultSchema.EMBEDDING_DIMENSIONS) { "Expected ${VaultSchema.EMBEDDING_DIMENSIONS} dimensions" }
        return db.rawQuery(
            "SELECT rowid, distance FROM chunk_vec WHERE embedding MATCH ? AND k = $CANDIDATES ORDER BY distance",
            ChunkStore.toBlob(embedding) as Any,
        ).use { c ->
            val hits = LinkedHashMap<Long, Double>()
            while (c.moveToNext()) hits[c.getLong(0)] = c.getDouble(1)
            hits
        }
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
