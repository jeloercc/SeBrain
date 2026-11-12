package com.sebrain.ingest

import com.sebrain.core.crypto.CapturePayload
import com.sebrain.core.crypto.InboxSegmentCodec
import java.io.File
import java.nio.file.Files
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.time.ZoneId
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class IngestTest {
    private val pair: KeyPair = KeyPairGenerator.getInstance("EC")
        .apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private val inbox: File = Files.createTempDirectory("inbox").toFile()
    private val indexFile = File(inbox, "dedup.idx")

    /** Software stand-in for K_dedup. */
    private val tagger = { digest: ByteArray ->
        Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(ByteArray(32) { 7 }, "HmacSHA256")); doFinal(digest) }
    }

    private fun ingestor(publicKey: () -> PublicKey = { pair.public }) = CaptureIngestor(
        inbox, 2, publicKey, DedupIndex(indexFile, tagger),
        { Instant.parse("2026-11-09T10:00:00Z") }, { ZoneId.of("America/Mexico_City") },
    )

    private val agree = { _: Byte, peer: PublicKey ->
        KeyAgreement.getInstance("ECDH").run { init(pair.private); doPhase(peer, true); generateSecret() }
    }

    private fun segments() = inbox.listFiles { f -> f.name.endsWith(".seg") }!!

    @Test
    fun normalizesLineEndingsSpacesAndUnicode() {
        assertEquals("café\nb", CaptureNormalizer.normalize("  café  \r\nb \n"))
    }

    @Test
    fun removesTrackingParametersAndKeepsFunctionalQueryAndFragment() {
        val input = "Read https://example.com/article?id=42&utm_source=mail&gclid=abc#details."
        assertEquals("Read https://example.com/article?id=42#details.", CaptureNormalizer.normalize(input))
    }

    @Test
    fun removesTrackingParametersFromMarkdownTextWithoutRemovingPunctuation() {
        val input = "[Article](https://example.com/a?fbclid=abc&utm_campaign=spring), then continue."
        assertEquals("[Article](https://example.com/a), then continue.", CaptureNormalizer.normalize(input))
    }

    @Test
    fun preservesUnknownParametersAndNonHttpText() {
        val input = "ref=utm_source&x=1 https://example.com/a?session=keep&x=2"
        assertEquals("ref=utm_source&x=1 https://example.com/a?session=keep&x=2", CaptureNormalizer.normalize(input))
    }

    @Test
    fun storesEncryptedSegmentWithCapturedAt() {
        val result = ingestor().ingest(Capture("Reunión el jueves", "share", "Asunto")) as IngestResult.Stored
        val file = File(inbox, "${result.segmentId}.seg")
        assertFalse(String(file.readBytes(), Charsets.ISO_8859_1).contains("jueves"))
        assertEquals(1, segments().size)

        val payload = CapturePayload.decode(InboxSegmentCodec.decrypt(file.readBytes(), agree).plaintext)
        assertEquals("Reunión el jueves", payload.text)
        assertEquals("2026-11-09T10:00:00Z", payload.capturedAt)
        assertEquals("America/Mexico_City", payload.timeZone)
        assertEquals("share", payload.source)
        assertEquals("Asunto", payload.subject)
        assertEquals(64, payload.contentSha256.length)
    }

    @Test
    fun duplicatesAndEmptyTextAreNotStored() {
        val ingestor = ingestor()
        assertTrue(ingestor.ingest(Capture("hello", "share")) is IngestResult.Stored)
        assertEquals(IngestResult.Duplicate, ingestor.ingest(Capture("  hello \r\n", "share")))
        assertEquals(IngestResult.Empty, ingestor.ingest(Capture(" \n ", "share")))
        assertEquals(1, segments().size)
    }

    @Test
    fun deduplicationSurvivesARestart() {
        assertTrue(ingestor().ingest(Capture("hello", "share")) is IngestResult.Stored)
        assertEquals(IngestResult.Duplicate, ingestor().ingest(Capture("hello", "share")))
        assertTrue(ingestor().ingest(Capture("other", "share")) is IngestResult.Stored)
        assertEquals(64L, indexFile.length())
    }

    @Test
    fun indexHoldsKeyedTagsNotPlainHashes() {
        ingestor().ingest(Capture("hello", "share"))
        val digest = CaptureNormalizer.sha256("hello")
        val stored = indexFile.readBytes()
        assertEquals(32, stored.size)
        assertFalse("the plain SHA-256 must not be on disk", stored.contentEquals(digest))
        assertTrue(stored.contentEquals(tagger(digest)))
    }

    @Test
    fun tornIndexRecordIsIgnored() {
        ingestor().ingest(Capture("hello", "share"))
        indexFile.appendBytes(ByteArray(10))
        assertEquals(IngestResult.Duplicate, ingestor().ingest(Capture("hello", "share")))
    }

    @Test
    fun oversizedTextIsRejected() {
        assertEquals(IngestResult.TooLarge, ingestor().ingest(Capture("a".repeat(1024 * 1024 + 1), "share")))
        assertEquals(0, segments().size)
    }

    @Test
    fun failedWriteIsNotRememberedAsDuplicate() {
        assertThrows(IllegalStateException::class.java) {
            ingestor { error("no key") }.ingest(Capture("retry", "share"))
        }
        assertTrue(ingestor().ingest(Capture("retry", "share")) is IngestResult.Stored)
    }

    @Test
    fun integrationPolicyDeniesPackagesByDefaultAndAllowsOnlyExactNames() {
        val policy = IntegrationPolicy.fromPackages(listOf("com.example.mail", " com.example.calendar "))

        assertTrue(policy.allows("com.example.mail"))
        assertTrue(policy.allows("com.example.calendar"))
        assertFalse(policy.allows("com.example.browser"))
        assertFalse(IntegrationPolicy.denyAll().allows("com.example.mail"))
    }

    @Test
    fun integrationPolicyRejectsWildcardsAndMalformedNames() {
        assertThrows(IllegalArgumentException::class.java) {
            IntegrationPolicy.fromPackages(listOf("*"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            IntegrationPolicy.fromPackages(listOf("com"))
        }
    }
}
