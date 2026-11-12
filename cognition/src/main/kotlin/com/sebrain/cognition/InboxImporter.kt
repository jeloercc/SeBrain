package com.sebrain.cognition

import com.sebrain.core.crypto.CapturePayload
import com.sebrain.core.crypto.InboxKey
import com.sebrain.core.crypto.InboxSegmentCodec
import com.sebrain.core.crypto.VaultKeyException
import com.sebrain.core.vault.CaptureRecord
import com.sebrain.core.vault.CaptureStore
import com.sebrain.core.vault.ChunkStore
import com.sebrain.core.vault.VaultSession
import com.sebrain.core.vault.VaultSessionClosingException
import java.io.File
import java.security.GeneralSecurityException
import java.security.PublicKey
import net.zetetic.database.sqlcipher.SQLiteDatabase

/**
 * Moves inbox segments into the vault, without AI. A segment is deleted only after its capture
 * is committed, so the import can stop at any point and be repeated.
 */
class InboxImporter(
    private val inboxDir: File,
    private val session: VaultSession,
    private val agree: (Byte, PublicKey) -> ByteArray = InboxKey::agree,
    private val indexer: NoteIndexer = NoteIndexer(),
) {
    data class Result(
        val imported: Int,
        val duplicates: Int,
        val quarantined: Int,
        val remaining: Int,
        /** Why the import stopped early, or null if it went through the whole inbox. */
        val stoppedBy: Exception?,
    )

    @Synchronized
    fun run(): Result {
        inboxDir.listFiles { f -> f.name.endsWith(".tmp") }?.forEach { it.delete() }
        val pending = pending()
        if (pending.isEmpty()) {
            val problem = try {
                indexPending()
                null
            } catch (e: VaultKeyException) {
                e
            } catch (e: VaultSessionClosingException) {
                e
            }
            return Result(0, 0, 0, 0, problem)
        }

        var imported = 0
        var duplicates = 0
        var quarantined = 0
        var stoppedBy: Exception? = null
        try {
            // Checked before decrypting anything, so no plaintext is produced if the vault is unavailable.
            session.withDatabase { }
            for (file in pending) {
                val record = try {
                    read(file)
                } catch (e: VaultKeyException.InboxKeyMissing) {
                    null
                } catch (e: VaultKeyException.UnknownInboxKey) {
                    null
                } catch (e: GeneralSecurityException) {
                    null
                } catch (e: IllegalArgumentException) {
                    null
                }
                if (record == null) {
                    quarantine(file)
                    quarantined++
                    continue
                }
                if (session.withDatabase { db -> store(db, record) }) imported++ else duplicates++
                file.delete()
            }
            indexPending()
        } catch (e: VaultKeyException) {
            stoppedBy = e
        } catch (e: VaultSessionClosingException) {
            stoppedBy = e
        }
        return Result(imported, duplicates, quarantined, pending().size, stoppedBy)
    }

    /** Inserts the capture and its full-text index in one transaction. Vectors are added later. */
    private fun store(db: SQLiteDatabase, record: CaptureRecord): Boolean {
        db.beginTransaction()
        try {
            val id = CaptureStore(db).insertReturningId(record)
            if (id != null) indexer.index(db, id, record.text, record.subject)
            db.setTransactionSuccessful()
            return id != null
        } finally {
            db.endTransaction()
        }
    }

    /** Brings older notes up to date: search index, missing vectors, and title, tags and links. */
    private fun indexPending() {
        session.withDatabase { db ->
            val captures = CaptureStore(db)
            val chunks = ChunkStore(db)
            for ((id, text) in captures.withoutIndex(INDEX_BATCH)) indexer.index(db, id, text)
            for ((id, text) in captures.withoutTitle(INDEX_BATCH)) indexer.markup(db, id, text)
            val missing = chunks.chunksWithoutVector(INDEX_BATCH)
            val vectors = if (missing.isEmpty()) null else indexer.embedOrNull(missing.map { it.text })
            if (vectors != null) missing.forEachIndexed { i, chunk -> chunks.addVector(chunk, vectors[i]) }
        }
    }

    private fun pending(): List<File> =
        inboxDir.listFiles { f -> f.name.endsWith(".seg") }.orEmpty().sortedWith(compareBy({ it.lastModified() }, { it.name }))

    private fun read(file: File): CaptureRecord {
        val parsed = InboxSegmentCodec.decrypt(file.readBytes(), agree)
        try {
            val payload = CapturePayload.decode(parsed.plaintext)
            return CaptureRecord(
                segmentId = parsed.segmentId,
                contentSha256 = payload.contentSha256,
                capturedAt = payload.capturedAt,
                timeZone = payload.timeZone,
                source = payload.source,
                subject = payload.subject,
                text = payload.text,
            )
        } finally {
            parsed.plaintext.fill(0)
        }
    }

    /** Segments that can never be read are kept apart, still encrypted, instead of being deleted. */
    private fun quarantine(file: File) {
        val dir = File(inboxDir, "quarantine").apply { mkdirs() }
        if (!file.renameTo(File(dir, file.name))) file.delete()
    }

    private companion object {
        const val INDEX_BATCH = 200
    }
}
