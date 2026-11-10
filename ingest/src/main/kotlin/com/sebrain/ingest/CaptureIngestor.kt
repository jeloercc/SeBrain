package com.sebrain.ingest

import com.sebrain.core.crypto.CapturePayload
import com.sebrain.core.crypto.InboxSegmentCodec
import java.io.File
import java.io.FileOutputStream
import java.security.PublicKey
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** What a capture contains before it is encrypted. */
data class Capture(val text: String, val source: String, val subject: String? = null)

sealed interface IngestResult {
    data class Stored(val segmentId: UUID) : IngestResult
    data object Duplicate : IngestResult
    data object Empty : IngestResult
    data object TooLarge : IngestResult
}

/**
 * Normalizes, deduplicates and encrypts captures into inbox segments.
 * It needs only the K_inbox public key and K_dedup, so it works while the device is locked.
 */
class CaptureIngestor(
    private val inboxDir: File,
    private val keyId: Byte,
    private val publicKey: () -> PublicKey,
    private val dedup: DedupIndex,
    private val now: () -> Instant = Instant::now,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    @Synchronized
    fun ingest(capture: Capture): IngestResult {
        val text = CaptureNormalizer.normalize(capture.text)
        if (text.isEmpty()) return IngestResult.Empty
        if (text.toByteArray(Charsets.UTF_8).size > MAX_TEXT_BYTES) return IngestResult.TooLarge
        val digest = CaptureNormalizer.sha256(text)
        val tag = dedup.tagOf(digest)
        if (dedup.contains(tag)) return IngestResult.Duplicate

        val segmentId = UUID.randomUUID()
        val payload = CapturePayload(
            capturedAt = now().toString(),
            timeZone = zone().id,
            source = capture.source,
            contentSha256 = digest.joinToString("") { "%02x".format(it) },
            subject = capture.subject,
            text = text,
        ).encode()
        try {
            write(segmentId, InboxSegmentCodec.encrypt(payload, publicKey(), keyId, segmentId))
        } finally {
            payload.fill(0)
        }
        // After the segment is durable. A crash in between lets one duplicate through,
        // which the vault rejects by content hash.
        dedup.remember(tag)
        return IngestResult.Stored(segmentId)
    }

    private fun write(segmentId: UUID, blob: ByteArray) {
        inboxDir.mkdirs()
        val target = File(inboxDir, "$segmentId.seg")
        val temporary = File(inboxDir, "$segmentId.tmp")
        FileOutputStream(temporary).use { output ->
            output.write(blob)
            output.fd.sync()
        }
        check(temporary.renameTo(target)) { "Could not store segment $segmentId" }
    }

    private companion object {
        const val MAX_TEXT_BYTES = 1024 * 1024
    }
}
