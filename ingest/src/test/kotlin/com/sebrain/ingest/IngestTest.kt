package com.sebrain.ingest

import java.io.File
import java.nio.file.Files
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import javax.crypto.AEADBadTagException
import javax.crypto.KeyAgreement
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class IngestTest {
    private val pair: KeyPair = KeyPairGenerator.getInstance("EC")
        .apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private val inbox: File = Files.createTempDirectory("inbox").toFile()
    private val ingestor = CaptureIngestor(
        inbox, 2, { pair.public }, { Instant.parse("2026-11-09T10:00:00Z") }, { ZoneId.of("America/Mexico_City") }, RecentDigests(),
    )

    private fun agree(key: KeyPair) = { _: Byte, peer: java.security.PublicKey ->
        KeyAgreement.getInstance("ECDH").run { init(key.private); doPhase(peer, true); generateSecret() }
    }

    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun hkdfMatchesRfc5869FirstBlock() {
        val okm = SegmentCodec.hkdfSha256(
            ByteArray(22) { 0x0b },
            hex("000102030405060708090a0b0c"),
            hex("f0f1f2f3f4f5f6f7f8f9"),
        )
        assertArrayEquals(hex("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf"), okm)
    }

    @Test
    fun normalizesLineEndingsSpacesAndUnicode() {
        assertEquals("café\nb", CaptureNormalizer.normalize("  café  \r\nb \n"))
    }

    @Test
    fun segmentRoundTripKeepsKeyIdAndId() {
        val id = UUID.randomUUID()
        val blob = SegmentCodec.encrypt("secret".toByteArray(), pair.public, 2, id)
        val parsed = SegmentCodec.decrypt(blob, agree(pair))
        assertEquals(2.toByte(), parsed.keyId)
        assertEquals(id, parsed.segmentId)
        assertArrayEquals("secret".toByteArray(), parsed.plaintext)
    }

    @Test
    fun tamperedHeaderOrCiphertextIsRejected() {
        val blob = SegmentCodec.encrypt("secret".toByteArray(), pair.public, 2, UUID.randomUUID())
        for (index in listOf(10, blob.size - 1)) {
            val tampered = blob.copyOf().also { it[index] = (it[index].toInt() xor 1).toByte() }
            assertThrows(AEADBadTagException::class.java) { SegmentCodec.decrypt(tampered, agree(pair)) }
        }
    }

    @Test
    fun storesEncryptedSegmentWithCapturedAt() {
        val result = ingestor.ingest(Capture("Reunión el jueves", "share", "Asunto")) as IngestResult.Stored
        val file = File(inbox, "${result.segmentId}.seg")
        assertFalse(String(file.readBytes(), Charsets.ISO_8859_1).contains("jueves"))
        assertEquals(1, inbox.listFiles()!!.size)

        val payload = JSONObject(String(SegmentCodec.decrypt(file.readBytes(), agree(pair)).plaintext))
        assertEquals("Reunión el jueves", payload.getString("text"))
        assertEquals("2026-11-09T10:00:00Z", payload.getString("captured_at"))
        assertEquals("America/Mexico_City", payload.getString("time_zone"))
        assertEquals("share", payload.getString("source"))
        assertEquals(64, payload.getString("content_sha256").length)
    }

    @Test
    fun duplicatesAndEmptyTextAreNotStored() {
        assertTrue(ingestor.ingest(Capture("hello", "share")) is IngestResult.Stored)
        assertEquals(IngestResult.Duplicate, ingestor.ingest(Capture("  hello \r\n", "share")))
        assertEquals(IngestResult.Empty, ingestor.ingest(Capture(" \n ", "share")))
        assertEquals(1, inbox.listFiles()!!.size)
    }

    @Test
    fun oversizedTextIsRejected() {
        assertEquals(IngestResult.TooLarge, ingestor.ingest(Capture("a".repeat(1024 * 1024 + 1), "share")))
        assertEquals(0, inbox.listFiles()?.size ?: 0)
    }

    @Test
    fun failedWriteCanBeRetried() {
        val failing = CaptureIngestor(
            inbox, 2, { error("no key") }, { Instant.EPOCH }, { ZoneId.of("UTC") }, RecentDigests(),
        )
        assertThrows(IllegalStateException::class.java) { failing.ingest(Capture("retry", "share")) }
        assertThrows("not treated as a duplicate", IllegalStateException::class.java) {
            failing.ingest(Capture("retry", "share"))
        }
    }
}
