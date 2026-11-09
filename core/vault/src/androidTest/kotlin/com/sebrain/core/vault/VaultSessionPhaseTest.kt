package com.sebrain.core.vault

import android.app.KeyguardManager
import android.os.Process
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sebrain.core.crypto.VaultKeyException
import com.sebrain.core.crypto.WrapKey
import com.sebrain.core.crypto.WrappedDekStore
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Checks that span process restarts or the device being locked.
 * Run by core/vault/scripts/run-session-phases.sh; skipped in a normal test run.
 */
@RunWith(AndroidJUnit4::class)
class VaultSessionPhaseTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(context.noBackupFilesDir, "vault-phases")
    private val pidFile = File(dir, "pid")
    private val keyguard = context.getSystemService(KeyguardManager::class.java)

    private val wrapKey = WrapKey(alias = "test_phase_wrap")
    private val blob = File(dir, "vault.wrapped")
    private val blobHash = File(dir, "vault.sha256")

    private fun session() = VaultSession(context, File(dir, "vault.db"), WrappedDekStore(blob, wrapKey))

    private fun read(session: VaultSession): String = session.withDatabase { db ->
        db.rawQuery("SELECT v FROM t", emptyArray<String>()).use { c ->
            assertTrue(c.moveToFirst())
            c.getString(0)
        }
    }

    private fun onlyInPhase(name: String) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("phase") == name)
    }

    private fun sha256(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes())

    @Test
    fun phaseCreate() {
        onlyInPhase("create")
        dir.deleteRecursively()
        dir.mkdirs()
        wrapKey.delete()
        session().use { s ->
            s.withDatabase { db ->
                db.execSQL("CREATE TABLE t(v TEXT)")
                db.execSQL("INSERT INTO t VALUES ('persisted')")
            }
        }
        blobHash.writeBytes(sha256(blob))
        pidFile.writeText(Process.myPid().toString())
    }

    /** Must run in a new process: the first phase records its pid. */
    @Test
    fun phaseReopenAfterKill() {
        onlyInPhase("reopen")
        assertNotEquals("the process must have been restarted", pidFile.readText(), Process.myPid().toString())
        session().use { s -> assertEquals("persisted", read(s)) }
        assertArrayEquals("the wrapped DEK must not change", blobHash.readBytes(), sha256(blob))
    }

    /** The script turns the screen off when it sees the ready line. */
    @Test
    fun phaseClosesOnLockAndCannotOpen() {
        onlyInPhase("locked")
        assertFalse("start this phase unlocked", keyguard.isDeviceLocked)
        val open = session()
        assertEquals("persisted", read(open))
        Log.i(TAG, "READY_FOR_SCREEN_OFF")

        waitUntil(SCREEN_OFF_WAIT_MS) { !open.isOpen }
        assertArrayEquals("the key must be zero", ByteArray(67), open.lastClosedKey)
        Log.i(TAG, "closed on screen off")

        waitUntil(SCREEN_OFF_WAIT_MS) { keyguard.isDeviceLocked }
        Log.i(TAG, "device locked")

        assertThrows(VaultKeyException.DeviceLocked::class.java) { read(open) }
        val fresh = session()
        assertThrows(VaultKeyException.DeviceLocked::class.java) { read(fresh) }
        assertFalse(fresh.isOpen)
        assertTrue("the wrapped DEK must still exist", blob.exists())
        Log.i(TAG, "refused to open while locked")
    }

    @Test
    fun phaseAfterUnlock() {
        onlyInPhase("afterUnlock")
        assertFalse("unlock the device first", keyguard.isDeviceLocked)
        session().use { s -> assertEquals("persisted", read(s)) }
        wrapKey.delete()
        dir.deleteRecursively()
    }

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            assertTrue("timed out", System.currentTimeMillis() < deadline)
            Thread.sleep(100)
        }
    }

    private companion object {
        const val TAG = "SEBRAIN_VAULT_PHASE"
        const val SCREEN_OFF_WAIT_MS = 90_000L
    }
}
