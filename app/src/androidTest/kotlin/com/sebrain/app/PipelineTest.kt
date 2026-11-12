package com.sebrain.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sebrain.core.vault.CaptureStore
import com.sebrain.ingest.Capture
import com.sebrain.ingest.IngestResult
import java.io.File
import java.security.MessageDigest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Share text to vault, end to end, with real Keystore keys. Needs an unlocked device with the screen on. */
@RunWith(AndroidJUnit4::class)
class PipelineTest {
    private lateinit var vault: TestVault

    @Before
    fun setUp() {
        vault = TestVault(InstrumentationRegistry.getInstrumentation().targetContext, "pipeline${System.nanoTime()}")
    }

    @After
    fun tearDown() = vault.deleteEverything()

    private fun stored(result: IngestResult) = (result as IngestResult.Stored).segmentId

    @Test
    fun capturesGoFromInboxToVault() {
        val ingestor = vault.ingestor()
        val first = stored(ingestor.ingest(Capture("Reunión el jueves con Laura", "share", "Asunto")))
        stored(ingestor.ingest(Capture("Factura pendiente", "share")))
        stored(ingestor.ingest(Capture("Comprar la tarta", "share")))
        assertEquals(IngestResult.Duplicate, ingestor.ingest(Capture("  Factura pendiente \r\n", "share")))
        assertEquals(3, vault.segments().size)

        val result = vault.importer.run()
        assertEquals(3, result.imported)
        assertEquals(0, result.duplicates)
        assertEquals(0, result.quarantined)
        assertEquals(0, result.remaining)
        assertNull(result.stoppedBy)
        assertEquals(0, vault.segments().size)
        assertEquals(3L, vault.captureCount())

        val record = vault.session.withDatabase { CaptureStore(it).findBySegmentId(first) }!!
        assertEquals("Reunión el jueves con Laura", record.text)
        assertEquals("Asunto", record.subject)
        assertEquals("share", record.source)
        assertEquals(64, record.contentSha256.length)
    }

    @Test
    fun nothingOnDiskIsPlaintext() {
        val marker = "SEBRAIN_PLAINTEXT_MARKER_91c4"
        vault.ingestor().ingest(Capture("note $marker", "share"))
        val segment = vault.segments().single().readBytes()
        assertFalse(contains(segment, marker.toByteArray()))

        vault.importer.run()
        vault.session.close()
        val digest = MessageDigest.getInstance("SHA-256").digest("note $marker".toByteArray())
        val hex = digest.joinToString("") { "%02x".format(it) }
        vault.dir.walkTopDown().filter { it.isFile }.forEach { file ->
            val bytes = file.readBytes()
            assertFalse("${file.name} has the plaintext", contains(bytes, marker.toByteArray()))
            assertFalse("${file.name} has the plain digest", contains(bytes, digest))
            assertFalse("${file.name} has the plain digest as hex", contains(bytes, hex.toByteArray()))
        }
        assertEquals(32L, vault.indexFile.length())
    }

    @Test
    fun deduplicationSurvivesARestart() {
        stored(vault.ingestor().ingest(Capture("hello", "share")))
        assertEquals(IngestResult.Duplicate, vault.ingestor().ingest(Capture("hello", "share")))
        vault.importer.run()
        assertEquals(IngestResult.Duplicate, vault.ingestor().ingest(Capture("hello", "share")))
        assertEquals(1L, vault.captureCount())
    }

    @Test
    fun corruptedSegmentIsQuarantinedAndTheRestImported() {
        val ingestor = vault.ingestor()
        val broken = stored(ingestor.ingest(Capture("one", "share")))
        stored(ingestor.ingest(Capture("two", "share")))
        val file = File(vault.inbox, "$broken.seg")
        file.writeBytes(file.readBytes().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() })

        val result = vault.importer.run()
        assertEquals(1, result.imported)
        assertEquals(1, result.quarantined)
        assertEquals(0, result.remaining)
        assertTrue(File(vault.inbox, "quarantine/$broken.seg").exists())
        assertEquals(1L, vault.captureCount())
    }

    @Test
    fun importingTheSameSegmentTwiceInsertsOnce() {
        val id = stored(vault.ingestor().ingest(Capture("once", "share")))
        val file = File(vault.inbox, "$id.seg")
        val copy = file.readBytes()
        assertEquals(1, vault.importer.run().imported)
        file.writeBytes(copy)
        val again = vault.importer.run()
        assertEquals(0, again.imported)
        assertEquals(1, again.duplicates)
        assertEquals(0, again.remaining)
        assertEquals(1L, vault.captureCount())
    }

    @Test
    fun strictAndBalancedSegmentsAreBothImported() {
        stored(vault.ingestor(vault.strict).ingest(Capture("strict capture", "share")))
        stored(vault.ingestor(vault.balanced).ingest(Capture("balanced capture", "share")))
        assertEquals(2, vault.importer.run().imported)
    }

    private fun contains(haystack: ByteArray, needle: ByteArray): Boolean {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return true
        }
        return false
    }
}
