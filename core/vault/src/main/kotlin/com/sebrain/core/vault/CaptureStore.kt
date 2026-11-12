package com.sebrain.core.vault

import java.util.UUID
import net.zetetic.database.sqlcipher.SQLiteDatabase

data class CaptureRecord(
    val segmentId: UUID,
    val contentSha256: String,
    val capturedAt: String,
    val timeZone: String,
    val source: String,
    val subject: String?,
    val text: String,
)

data class CaptureSummary(val id: Long, val capturedAt: String, val source: String, val text: String, val title: String? = null)

/** Reads and writes captures. Use it inside [VaultSession.withDatabase]. */
class CaptureStore(private val db: SQLiteDatabase) {
    /**
     * Inserts the capture. Returns false, without changing anything, if the segment was already
     * imported or the same content already exists.
     */
    fun insert(record: CaptureRecord): Boolean = insertReturningId(record) != null

    /** Like [insert], returning the new row id, or null if nothing was inserted. */
    fun insertReturningId(record: CaptureRecord): Long? =
        db.compileStatement(
            "INSERT OR IGNORE INTO capture" +
                "(segment_id, content_sha256, captured_at, time_zone, source, subject, text) VALUES (?, ?, ?, ?, ?, ?, ?)",
        ).use { statement ->
            statement.bindString(1, record.segmentId.toString())
            statement.bindString(2, record.contentSha256)
            statement.bindString(3, record.capturedAt)
            statement.bindString(4, record.timeZone)
            statement.bindString(5, record.source)
            if (record.subject == null) statement.bindNull(6) else statement.bindString(6, record.subject)
            statement.bindString(7, record.text)
            statement.executeInsert().takeIf { it != -1L }
        }

    /** Captures that have no search index yet, oldest first. */
    fun withoutIndex(limit: Int): List<Pair<Long, String>> = db.rawQuery(
        "SELECT id, text FROM capture WHERE index_status = 'pending' ORDER BY id LIMIT $limit",
        emptyArray<String>(),
    ).use { c -> generateSequence { if (c.moveToNext()) c.getLong(0) to c.getString(1) else null }.toList() }

    /** Notes whose title, tags and links were never extracted, oldest first. */
    fun withoutTitle(limit: Int): List<Pair<Long, String>> = db.rawQuery(
        "SELECT id, text FROM capture WHERE title IS NULL ORDER BY id LIMIT $limit",
        emptyArray<String>(),
    ).use { c -> generateSequence { if (c.moveToNext()) c.getLong(0) to c.getString(1) else null }.toList() }

    /** The newest captures, for display. */
    fun recent(limit: Int): List<CaptureSummary> = db.rawQuery(
        "SELECT id, captured_at, source, text, title FROM capture ORDER BY COALESCE(updated_at, captured_at) DESC, id DESC LIMIT $limit",
        emptyArray<String>(),
    ).use { c ->
        generateSequence {
            if (c.moveToNext()) CaptureSummary(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4)) else null
        }.toList()
    }

    fun summaryOf(captureId: Long): CaptureSummary? = db.rawQuery(
        "SELECT id, captured_at, source, text, title FROM capture WHERE id = ?", captureId as Any,
    ).use { c -> if (c.moveToFirst()) CaptureSummary(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4)) else null }

    fun count(): Long = db.rawQuery("SELECT count(*) FROM capture", emptyArray<String>()).use { c ->
        c.moveToFirst()
        c.getLong(0)
    }

    fun findBySegmentId(segmentId: UUID): CaptureRecord? = db.rawQuery(
        "SELECT segment_id, content_sha256, captured_at, time_zone, source, subject, text " +
            "FROM capture WHERE segment_id = ?",
        arrayOf(segmentId.toString()),
    ).use { c ->
        if (!c.moveToFirst()) return null
        CaptureRecord(
            segmentId = UUID.fromString(c.getString(0)),
            contentSha256 = c.getString(1),
            capturedAt = c.getString(2),
            timeZone = c.getString(3),
            source = c.getString(4),
            subject = if (c.isNull(5)) null else c.getString(5),
            text = c.getString(6),
        )
    }
}
