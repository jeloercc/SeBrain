package com.sebrain.core.crypto

import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Inbox segment: ECIES with one ephemeral key per segment. Written by ingestion with the
 * public key only, and read by cognition with K_inbox.
 *
 * Format: magic "SBSG" (4) | version (1) | key id (1) | segment id (16) | ephemeral public key,
 * X.509 (91) | nonce (12) | AES-256-GCM ciphertext + tag. Everything before the ciphertext is AAD.
 */
object InboxSegmentCodec {
    private val MAGIC = "SBSG".toByteArray(Charsets.US_ASCII)
    private const val VERSION: Byte = 1
    private const val EPHEMERAL_BYTES = 91
    private const val NONCE_BYTES = 12
    private const val HEADER_BYTES = 4 + 1 + 1 + 16 + EPHEMERAL_BYTES + NONCE_BYTES
    private const val TAG_BITS = 128

    class Parsed(val keyId: Byte, val segmentId: UUID, val plaintext: ByteArray)

    fun encrypt(plaintext: ByteArray, recipient: PublicKey, keyId: Byte, segmentId: UUID): ByteArray {
        val ephemeral = KeyPairGenerator.getInstance("EC")
            .apply { initialize(ECGenParameterSpec("secp256r1")) }
            .generateKeyPair()
        val shared = KeyAgreement.getInstance("ECDH").run {
            init(ephemeral.private)
            doPhase(recipient, true)
            generateSecret()
        }
        val key = deriveKey(shared, keyId, segmentId)
        shared.fill(0)
        val nonce = ByteArray(NONCE_BYTES).also { SecureRandom().nextBytes(it) }
        val encoded = ephemeral.public.encoded
        require(encoded.size == EPHEMERAL_BYTES) { "Unexpected public key size: ${encoded.size}" }
        val header = ByteBuffer.allocate(HEADER_BYTES)
            .put(MAGIC).put(VERSION).put(keyId)
            .putLong(segmentId.mostSignificantBits).putLong(segmentId.leastSignificantBits)
            .put(encoded).put(nonce)
            .array()
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            cipher.updateAAD(header)
            return header + cipher.doFinal(plaintext)
        } finally {
            key.fill(0)
        }
    }

    /** [agree] performs ECDH with the private key selected by the key id. */
    fun decrypt(blob: ByteArray, agree: (Byte, PublicKey) -> ByteArray): Parsed {
        require(blob.size > HEADER_BYTES) { "Segment too short" }
        require(blob.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) { "Bad magic" }
        val buffer = ByteBuffer.wrap(blob, MAGIC.size, HEADER_BYTES - MAGIC.size)
        require(buffer.get() == VERSION) { "Unsupported version" }
        val keyId = buffer.get()
        val segmentId = UUID(buffer.long, buffer.long)
        val encoded = ByteArray(EPHEMERAL_BYTES).also { buffer.get(it) }
        val nonce = ByteArray(NONCE_BYTES).also { buffer.get(it) }
        val ephemeral = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(encoded))
        val shared = agree(keyId, ephemeral)
        val key = deriveKey(shared, keyId, segmentId)
        shared.fill(0)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            cipher.updateAAD(blob, 0, HEADER_BYTES)
            return Parsed(keyId, segmentId, cipher.doFinal(blob, HEADER_BYTES, blob.size - HEADER_BYTES))
        } finally {
            key.fill(0)
        }
    }

    private fun deriveKey(shared: ByteArray, keyId: Byte, segmentId: UUID): ByteArray {
        val info = ByteBuffer.allocate(INFO_PREFIX.size + 1 + 16)
            .put(INFO_PREFIX).put(keyId)
            .putLong(segmentId.mostSignificantBits).putLong(segmentId.leastSignificantBits)
            .array()
        return hkdfSha256(shared, ByteArray(32), info)
    }

    /** HKDF-SHA256 (RFC 5869) with an output of 32 bytes. */
    fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        mac.update(info)
        mac.update(1)
        return mac.doFinal().also { prk.fill(0) }
    }

    private val INFO_PREFIX = "sebrain/seg/v1".toByteArray(Charsets.US_ASCII)
}
