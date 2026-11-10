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

/** Reads and writes captures. Use it inside [VaultSession.withDatabase]. */
class CaptureStore(private val db: SQLiteDatabase) {
    /**
     * Inserts the capture. Returns false, without changing anything, if the segment was already
     * imported or the same content already exists.
     */
    fun insert(record: CaptureRecord): Boolean =
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
            statement.executeInsert() != -1L
        }

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
