package com.sebrain.app

import android.app.KeyguardManager
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sebrain.core.crypto.InboxSegmentCodec
import com.sebrain.core.crypto.VaultKeyException
import com.sebrain.ingest.Capture
import com.sebrain.ingest.IngestResult
import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pipeline behavior across a device lock. Run by scripts/run-phone-tests.sh, one phase per
 * process; skipped in a normal test run.
 */
@RunWith(AndroidJUnit4::class)
class LockedPhaseTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val keyguard = context.getSystemService(KeyguardManager::class.java)
    private val vault = TestVault(context, "phase")

    private fun onlyInPhase(name: String) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("phase") == name)
    }

    @Test
    fun phasePrepare() {
        onlyInPhase("prepare")
        assertFalse("start unlocked", keyguard.isDeviceLocked)
        vault.deleteEverything()
        vault.balanced.publicKey()
        vault.strict.publicKey()
        assertTrue(vault.ingestor().ingest(Capture("before lock", "share")) is IngestResult.Stored)
        assertEquals(1, vault.importer.run().imported)
        assertEquals(1L, vault.captureCount())
        vault.session.close()
    }

    @Test
    fun phaseLocked() {
        onlyInPhase("locked")
        assertTrue("the device must be locked", keyguard.isDeviceLocked)

        // Ingestion: K_inbox public key and K_dedup work while locked.
        val ingestor = vault.ingestor()
        assertTrue(ingestor.ingest(Capture("captured while locked 1", "share")) is IngestResult.Stored)
        assertTrue(ingestor.ingest(Capture("captured while locked 2", "share")) is IngestResult.Stored)
        assertEquals(IngestResult.Duplicate, ingestor.ingest(Capture("captured while locked 1", "share")))
        assertEquals(IngestResult.Duplicate, vault.ingestor().ingest(Capture("before lock", "share")))
        assertEquals(2, vault.segments().size)
        Log.i(TAG, "ingestion and deduplication work while locked")

        // The Balanced K_inbox decrypts while locked; the Strict one does not.
        val secret = "locked secret".toByteArray()
        val balancedBlob = InboxSegmentCodec.encrypt(secret, vault.balanced.publicKey(), vault.balanced.keyId, UUID.randomUUID())
        assertArrayEquals(secret, InboxSegmentCodec.decrypt(balancedBlob) { _, peer -> vault.balanced.agree(peer) }.plaintext)
        Log.i(TAG, "balanced K_inbox decrypts while locked")
        val strictBlob = InboxSegmentCodec.encrypt(secret, vault.strict.publicKey(), vault.strict.keyId, UUID.randomUUID())
        assertThrows(VaultKeyException.DeviceLocked::class.java) {
            InboxSegmentCodec.decrypt(strictBlob) { _, peer -> vault.strict.agree(peer) }
        }
        Log.i(TAG, "strict K_inbox refuses while locked")

        // The vault stays closed, and the import leaves the inbox untouched.
        val result = vault.importer.run()
        assertTrue("stopped by ${result.stoppedBy}", result.stoppedBy is VaultKeyException.DeviceLocked)
        assertEquals(0, result.imported)
        assertEquals(2, result.remaining)
        assertEquals(2, vault.segments().size)
        assertFalse(vault.session.isOpen)
        Log.i(TAG, "import refused while locked and the inbox was kept")

        assertTrue("the device was unlocked during the phase", keyguard.isDeviceLocked)
        Log.i(TAG, "LOCKED_PHASE_DONE")
    }

    @Test
    fun phaseAfterUnlock() {
        onlyInPhase("afterUnlock")
        assertFalse("unlock the device first", keyguard.isDeviceLocked)
        val result = vault.importer.run()
        assertNull(result.stoppedBy)
        assertEquals(2, result.imported)
        assertEquals(0, result.remaining)
        assertEquals(3L, vault.captureCount())
        vault.deleteEverything()
        Log.i(TAG, "captures made while locked were imported after unlock")
    }

    private companion object {
        const val TAG = "SEBRAIN_APP_PHASE"
    }
}
