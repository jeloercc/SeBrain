package com.sebrain.core.crypto

import android.security.keystore.KeyProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.security.MessageDigest
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Needs an unlocked device. The locked behavior is covered by the app phase tests. */
@RunWith(AndroidJUnit4::class)
class KeystoreKeysTest {
    private val suffix = "_test_${System.nanoTime()}"
    private val strict = InboxKey(InboxMode.STRICT, suffix)
    private val balanced = InboxKey(InboxMode.BALANCED, suffix)
    private val dedup = DedupKey("test_dedup$suffix")

    @After
    fun tearDown() {
        strict.delete()
        balanced.delete()
        dedup.delete()
    }

    private fun sha256(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())

    @Test
    fun inboxKeysAreStrongBoxAndStable() {
        for (key in listOf(strict, balanced)) {
            assertFalse(key.exists())
            assertNull(key.securityLevel())
            val public = key.publicKey()
            assertTrue(key.exists())
            assertEquals(KeyProperties.SECURITY_LEVEL_STRONGBOX, key.securityLevel())
            assertArrayEquals("the pair must not be regenerated", public.encoded, key.publicKey().encoded)
        }
        assertFalse(strict.publicKey().encoded.contentEquals(balanced.publicKey().encoded))
        assertEquals(InboxMode.STRICT, InboxMode.fromKeyId(strict.keyId))
        assertEquals(InboxMode.BALANCED, InboxMode.fromKeyId(balanced.keyId))
        assertEquals(InboxMode.BALANCED, InboxMode.DEFAULT)
        assertNull(InboxMode.fromKeyId(9))
    }

    @Test
    fun segmentsDecryptWithTheKeystoreKey() {
        for (key in listOf(strict, balanced)) {
            val id = UUID.randomUUID()
            val blob = InboxSegmentCodec.encrypt("secret".toByteArray(), key.publicKey(), key.keyId, id)
            val parsed = InboxSegmentCodec.decrypt(blob) { _, peer -> key.agree(peer) }
            assertEquals(key.keyId, parsed.keyId)
            assertEquals(id, parsed.segmentId)
            assertArrayEquals("secret".toByteArray(), parsed.plaintext)
        }
    }

    @Test
    fun missingOrUnknownInboxKeyIsAClearError() {
        val blob = InboxSegmentCodec.encrypt("secret".toByteArray(), balanced.publicKey(), balanced.keyId, UUID.randomUUID())
        balanced.delete()
        assertThrows(VaultKeyException.InboxKeyMissing::class.java) {
            InboxSegmentCodec.decrypt(blob) { _, peer -> balanced.agree(peer) }
        }
        assertThrows(VaultKeyException.UnknownInboxKey::class.java) { InboxKey.agree(9, strict.publicKey()) }
    }

    @Test
    fun dedupTagsAreKeyedAndStable() {
        val digest = sha256("same text")
        val tag = dedup.tag(digest)
        assertEquals(32, tag.size)
        assertEquals(KeyProperties.SECURITY_LEVEL_STRONGBOX, dedup.securityLevel())
        assertArrayEquals(tag, dedup.tag(digest))
        assertArrayEquals("a new instance uses the same key", tag, DedupKey("test_dedup$suffix").tag(digest))
        assertFalse(tag.contentEquals(dedup.tag(sha256("other text"))))
        assertFalse("the tag must not be the plain digest", tag.contentEquals(digest))

        val other = DedupKey("test_dedup_other$suffix")
        try {
            assertFalse("another key gives another tag", tag.contentEquals(other.tag(digest)))
        } finally {
            other.delete()
        }
        assertThrows(IllegalArgumentException::class.java) { dedup.tag(ByteArray(5)) }
    }
}
