package com.sebrain.cognition

import com.sebrain.core.crypto.CapturePayload
import com.sebrain.core.crypto.InboxKey
import com.sebrain.core.crypto.InboxSegmentCodec
import com.sebrain.core.crypto.VaultKeyException
import com.sebrain.core.vault.CaptureRecord
import com.sebrain.core.vault.CaptureStore
import com.sebrain.core.vault.VaultSession
import com.sebrain.core.vault.VaultSessionClosingException
import java.io.File
import java.security.GeneralSecurityException
import java.security.PublicKey

/**
 * Moves inbox segments into the vault, without AI. A segment is deleted only after its capture
 * is committed, so the import can stop at any point and be repeated.
 */
class InboxImporter(
    private val inboxDir: File,
    private val session: VaultSession,
    private val agree: (Byte, PublicKey) -> ByteArray = InboxKey::agree,
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
        if (pending.isEmpty()) return Result(0, 0, 0, 0, null)

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
                if (session.withDatabase { CaptureStore(it).insert(record) }) imported++ else duplicates++
                file.delete()
            }
        } catch (e: VaultKeyException) {
            stoppedBy = e
        } catch (e: VaultSessionClosingException) {
            stoppedBy = e
        }
        return Result(imported, duplicates, quarantined, pending().size, stoppedBy)
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
}
