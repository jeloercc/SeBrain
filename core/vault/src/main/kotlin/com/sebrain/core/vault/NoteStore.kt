package com.sebrain.core.vault

import java.util.UUID
import net.zetetic.database.sqlcipher.SQLiteDatabase

data class Note(
    val id: Long,
    val title: String,
    val text: String,
    val capturedAt: String,
    val source: String,
    val tags: List<String>,
)

/** Notes, tags and links. Every capture is a note. Use it inside [VaultSession.withDatabase]. */
class NoteStore(private val db: SQLiteDatabase) {
    /** Creates a note written in the app. Returns null if a note with the same content exists. */
    fun create(text: String, contentSha256: String, now: String, timeZone: String): Long? =
        CaptureStore(db).insertReturningId(
            CaptureRecord(UUID.randomUUID(), contentSha256, now, timeZone, SOURCE_NOTE, null, text),
        )

    /** Replaces the text of a note. Returns false if another note already has that content. */
    fun updateText(id: Long, text: String, contentSha256: String, now: String): Boolean {
        val taken = db.rawQuery(
            "SELECT count(*) FROM capture WHERE content_sha256 = ? AND id != ?", contentSha256 as Any, id as Any,
        ).use { c -> c.moveToFirst(); c.getLong(0) > 0 }
        if (taken) return false
        // The title is cleared so it is derived again from the new text.
        db.execSQL(
            "UPDATE capture SET text = ?, content_sha256 = ?, updated_at = ?, title = NULL WHERE id = ?",
            arrayOf<Any>(text, contentSha256, now, id),
        )
        return true
    }

    /** Stores what was extracted from a note's text: its title if it has none, its tags and its links. */
    fun setMarkup(id: Long, title: String, tags: List<String>, links: List<String>) {
        db.beginTransaction()
        try {
            db.execSQL("UPDATE capture SET title = COALESCE(title, ?) WHERE id = ?", arrayOf<Any>(title, id))
            db.execSQL("DELETE FROM capture_tag WHERE capture_id = ?", arrayOf<Any>(id))
            for (tag in tags.distinctBy { it.lowercase() }) {
                db.execSQL("INSERT OR IGNORE INTO tag(name) VALUES (?)", arrayOf<Any>(tag))
                db.execSQL(
                    "INSERT OR IGNORE INTO capture_tag(capture_id, tag_id) SELECT ?, id FROM tag WHERE name = ?",
                    arrayOf<Any>(id, tag),
                )
            }
            db.execSQL("DELETE FROM tag WHERE id NOT IN (SELECT tag_id FROM capture_tag)")
            db.execSQL("DELETE FROM link WHERE source_id = ?", arrayOf<Any>(id))
            for (target in links.distinctBy { it.lowercase() }) {
                db.execSQL("INSERT OR IGNORE INTO link(source_id, target_title) VALUES (?, ?)", arrayOf<Any>(id, target))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun get(id: Long): Note? = db.rawQuery(
        "SELECT id, COALESCE(title, ''), text, captured_at, source FROM capture WHERE id = ?", id as Any,
    ).use { c ->
        if (!c.moveToFirst()) return null
        Note(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), tagsOf(id))
    }

    fun tagsOf(id: Long): List<String> = db.rawQuery(
        "SELECT t.name FROM tag t JOIN capture_tag ct ON ct.tag_id = t.id WHERE ct.capture_id = ? ORDER BY t.name",
        id as Any,
    ).use { c -> strings(c) }

    /** Every tag with the number of notes that use it, most used first. */
    fun tags(): List<Pair<String, Int>> = db.rawQuery(
        "SELECT t.name, count(*) FROM tag t JOIN capture_tag ct ON ct.tag_id = t.id " +
            "GROUP BY t.id ORDER BY count(*) DESC, t.name",
        emptyArray<String>(),
    ).use { c -> generateSequence { if (c.moveToNext()) c.getString(0) to c.getInt(1) else null }.toList() }

    fun withTag(tag: String, limit: Int): List<CaptureSummary> = summaries(
        "SELECT c.id, c.captured_at, c.source, c.text, c.title FROM capture c " +
            "JOIN capture_tag ct ON ct.capture_id = c.id JOIN tag t ON t.id = ct.tag_id " +
            "WHERE t.name = ? ORDER BY COALESCE(c.updated_at, c.captured_at) DESC LIMIT $limit",
        tag,
    )

    /** Notes that link to this note's title. */
    fun backlinks(id: Long): List<CaptureSummary> = summaries(
        "SELECT c.id, c.captured_at, c.source, c.text, c.title FROM capture c " +
            "JOIN link l ON l.source_id = c.id JOIN capture target ON target.id = ? " +
            "WHERE l.target_title = target.title AND c.id != target.id ORDER BY c.id DESC",
        id,
    )

    /** Notes this note links to, for the links whose target exists. */
    fun linkedFrom(id: Long): List<CaptureSummary> = summaries(
        "SELECT c.id, c.captured_at, c.source, c.text, c.title FROM capture c " +
            "JOIN link l ON l.target_title = c.title WHERE l.source_id = ? AND c.id != l.source_id ORDER BY c.title",
        id,
    )

    fun findByTitle(title: String): Long? = db.rawQuery(
        "SELECT id FROM capture WHERE title = ? COLLATE NOCASE ORDER BY id LIMIT 1", title as Any,
    ).use { c -> if (c.moveToFirst()) c.getLong(0) else null }

    /** Deletes a note with its tags, links and search index. */
    fun delete(id: Long) {
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM chunk_vec WHERE rowid IN (SELECT id FROM chunk WHERE capture_id = ?)", arrayOf<Any>(id))
            db.execSQL(
                "INSERT INTO chunk_fts(chunk_fts, rowid, text) SELECT 'delete', id, text FROM chunk WHERE capture_id = ?",
                arrayOf<Any>(id),
            )
            db.execSQL("DELETE FROM chunk WHERE capture_id = ?", arrayOf<Any>(id))
            db.execSQL("DELETE FROM capture_tag WHERE capture_id = ?", arrayOf<Any>(id))
            db.execSQL("DELETE FROM tag WHERE id NOT IN (SELECT tag_id FROM capture_tag)")
            db.execSQL("DELETE FROM link WHERE source_id = ?", arrayOf<Any>(id))
            db.execSQL("DELETE FROM capture WHERE id = ?", arrayOf<Any>(id))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun summaries(sql: String, argument: Any): List<CaptureSummary> = db.rawQuery(sql, argument).use { c ->
        generateSequence {
            if (c.moveToNext()) CaptureSummary(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4)) else null
        }.toList()
    }

    private fun strings(c: android.database.Cursor): List<String> =
        generateSequence { if (c.moveToNext()) c.getString(0) else null }.toList()

    companion object {
        const val SOURCE_NOTE = "note"
    }
}
