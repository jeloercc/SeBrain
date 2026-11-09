package com.sebrain.ingest

import java.io.File
import java.io.FileOutputStream
import java.security.PublicKey
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import org.json.JSONObject

/** What a capture contains before it is encrypted. */
data class Capture(val text: String, val source: String, val subject: String? = null)

sealed interface IngestResult {
    data class Stored(val segmentId: UUID) : IngestResult
    data object Duplicate : IngestResult
    data object Empty : IngestResult
    data object TooLarge : IngestResult
}

/** Normalizes, deduplicates and encrypts captures. It only needs the public key, so it works while locked. */
class CaptureIngestor internal constructor(
    private val inboxDir: File,
    private val keyId: Byte,
    private val publicKey: () -> PublicKey,
    private val now: () -> Instant,
    private val zone: () -> ZoneId,
    private val digests: RecentDigests,
) {
    constructor(inboxDir: File, keyId: Byte, publicKey: () -> PublicKey) :
        this(inboxDir, keyId, publicKey, Instant::now, ZoneId::systemDefault, RecentDigests())

    fun ingest(capture: Capture): IngestResult {
        val text = CaptureNormalizer.normalize(capture.text)
        if (text.isEmpty()) return IngestResult.Empty
        if (text.toByteArray(Charsets.UTF_8).size > MAX_TEXT_BYTES) return IngestResult.TooLarge
        val digest = CaptureNormalizer.sha256Hex(text)
        if (!digests.add(digest)) return IngestResult.Duplicate

        val segmentId = UUID.randomUUID()
        val payload = JSONObject()
            .put("schema", 1)
            .put("captured_at", now().toString())
            .put("time_zone", zone().id)
            .put("source", capture.source)
            .put("content_sha256", digest)
            .put("subject", capture.subject ?: JSONObject.NULL)
            .put("text", text)
            .toString()
            .toByteArray(Charsets.UTF_8)
        try {
            write(segmentId, SegmentCodec.encrypt(payload, publicKey(), keyId, segmentId))
        } catch (e: Exception) {
            digests.remove(digest)
            throw e
        } finally {
            payload.fill(0)
        }
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
