package com.sebrain.core.crypto

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.spec.ECGenParameterSpec
import java.util.UUID
import javax.crypto.AEADBadTagException
import javax.crypto.KeyAgreement
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class InboxSegmentCodecTest {
    private val pair: KeyPair = KeyPairGenerator.getInstance("EC")
        .apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    private val agree = { _: Byte, peer: PublicKey ->
        KeyAgreement.getInstance("ECDH").run { init(pair.private); doPhase(peer, true); generateSecret() }
    }

    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun hkdfMatchesRfc5869FirstBlock() {
        val okm = InboxSegmentCodec.hkdfSha256(
            ByteArray(22) { 0x0b },
            hex("000102030405060708090a0b0c"),
            hex("f0f1f2f3f4f5f6f7f8f9"),
        )
        assertArrayEquals(hex("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf"), okm)
    }

    @Test
    fun roundTripKeepsKeyIdAndSegmentId() {
        val id = UUID.randomUUID()
        val parsed = InboxSegmentCodec.decrypt(InboxSegmentCodec.encrypt("secret".toByteArray(), pair.public, 2, id), agree)
        assertEquals(2.toByte(), parsed.keyId)
        assertEquals(id, parsed.segmentId)
        assertArrayEquals("secret".toByteArray(), parsed.plaintext)
    }

    @Test
    fun tamperedHeaderOrCiphertextIsRejected() {
        val blob = InboxSegmentCodec.encrypt("secret".toByteArray(), pair.public, 2, UUID.randomUUID())
        for (index in listOf(5, 10, blob.size - 1)) {
            val tampered = blob.copyOf().also { it[index] = (it[index].toInt() xor 1).toByte() }
            assertThrows(AEADBadTagException::class.java) { InboxSegmentCodec.decrypt(tampered, agree) }
        }
    }

    @Test
    fun malformedSegmentsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { InboxSegmentCodec.decrypt(ByteArray(10), agree) }
        val blob = InboxSegmentCodec.encrypt("secret".toByteArray(), pair.public, 2, UUID.randomUUID())
        assertThrows(IllegalArgumentException::class.java) {
            InboxSegmentCodec.decrypt(blob.copyOf().also { it[0] = 0 }, agree)
        }
    }

    @Test
    fun payloadRoundTrip() {
        val payload = CapturePayload("2026-11-09T10:00:00Z", "UTC", "share", "ab".repeat(32), null, "Reunión")
        val decoded = CapturePayload.decode(payload.encode())
        assertEquals(payload, decoded)
        assertNull(decoded.subject)
        assertThrows(IllegalArgumentException::class.java) { CapturePayload.decode("{}".toByteArray()) }
        assertThrows(IllegalArgumentException::class.java) { CapturePayload.decode("not json".toByteArray()) }
    }
}
