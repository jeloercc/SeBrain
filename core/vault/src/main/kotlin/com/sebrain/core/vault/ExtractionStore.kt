package com.sebrain.core.vault

import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.json.JSONArray

/**
 * What the CognitionWorker extracted from captures (schema v7, `extraction` table). Derived data
 * only: the capture's own text is never changed here. Only captures that came from outside are
 * extracted; notes the user wrote or imported already have the structure they gave them.
 */
class ExtractionStore(private val db: SQLiteDatabase) {
    /** A capture waiting for extraction, with what the prompt needs. */
    data class Pending(val id: Long, val text: String, val capturedAt: String, val timeZone: String, val attempts: Int)

    data class Extracted(
        val people: List<String>,
        val dates: List<String>,
        val topics: List<String>,
        val tasks: List<String>,
        val title: String,
        val model: String,
        val extractedAt: String,
    )

    /** Oldest first, so a backlog is worked through in capture order. */
    fun pending(limit: Int): List<Pending> = db.rawQuery(
        "SELECT id, text, captured_at, time_zone, extraction_attempts FROM capture " +
            "WHERE extraction_status = '$STATUS_PENDING' AND source NOT IN ('${NoteStore.SOURCE_NOTE}', '${NoteStore.SOURCE_VAULT}') " +
            "ORDER BY id LIMIT ?",
        limit as Any,
    ).use { c ->
        generateSequence { if (c.moveToNext()) Pending(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getInt(4)) else null }.toList()
    }

    /** Stores a valid extraction; [attempts] 2 means it took the retry. One transaction. */
    fun save(id: Long, fields: Extracted, attempts: Int) {
        db.beginTransaction()
        try {
            db.execSQL(
                "INSERT OR REPLACE INTO extraction(capture_id, people, dates, topics, tasks, title, model, extracted_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any>(id, json(fields.people), json(fields.dates), json(fields.topics), json(fields.tasks), fields.title, fields.model, fields.extractedAt),
            )
            setStatus(id, if (attempts > 1) STATUS_OK_RETRY else STATUS_OK, attempts, null)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Marks a capture whose extraction failed. Only the failure's code is kept, never the reply. */
    fun fail(id: Long, code: String, attempts: Int) = setStatus(id, STATUS_FAILED, attempts, code)

    fun get(id: Long): Extracted? = db.rawQuery(
        "SELECT people, dates, topics, tasks, title, model, extracted_at FROM extraction WHERE capture_id = ?", id as Any,
    ).use { c ->
        if (!c.moveToFirst()) null else Extracted(list(c.getString(0)), list(c.getString(1)), list(c.getString(2)), list(c.getString(3)), c.getString(4), c.getString(5), c.getString(6))
    }

    /** The status and failure code of a capture, for tests and diagnostics. */
    fun statusOf(id: Long): Pair<String, String?>? = db.rawQuery(
        "SELECT extraction_status, extraction_error FROM capture WHERE id = ?", id as Any,
    ).use { c -> if (c.moveToFirst()) c.getString(0) to c.getString(1) else null }

    private fun setStatus(id: Long, status: String, attempts: Int, error: String?) = db.execSQL(
        "UPDATE capture SET extraction_status = ?, extraction_attempts = ?, extraction_error = ? WHERE id = ?",
        arrayOf<Any?>(status, attempts, error, id),
    )

    private fun json(values: List<String>) = JSONArray(values).toString()

    private fun list(json: String): List<String> = JSONArray(json).let { array -> (0 until array.length()).map(array::getString) }

    companion object {
        const val STATUS_PENDING = "pending"
        const val STATUS_OK = "ok"
        const val STATUS_OK_RETRY = "ok_retry"
        const val STATUS_FAILED = "failed"
    }
}
