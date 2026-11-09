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

    private inner class Vault(name: String, val mode: VaultMode, val idleMs: Long = VaultSession.DEFAULT_IDLE_TIMEOUT_MS) {
        val wrapKey = WrapKey(alias = "test_phase_$name")
        val blob = File(dir, "$name.wrapped")
        val blobHash = File(dir, "$name.sha256")
        val session = VaultSession(context, File(dir, "$name.db"), WrappedDekStore(blob, wrapKey), mode, idleMs)

        fun read(): String = session.withDatabase { db ->
            db.rawQuery("SELECT v FROM t", emptyArray<String>()).use { c ->
                assertTrue(c.moveToFirst())
                c.getString(0)
            }
        }
    }

    private fun strict() = Vault("strict", VaultMode.STRICT)
    private fun balanced(idleMs: Long = VaultSession.DEFAULT_IDLE_TIMEOUT_MS) =
        Vault("balanced", VaultMode.BALANCED, idleMs)

    private fun onlyInPhase(name: String) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("phase") == name)
    }

    private fun sha256(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes())

    @Test
    fun phaseCreate() {
        onlyInPhase("create")
        dir.deleteRecursively()
        dir.mkdirs()
        for (vault in listOf(strict(), balanced())) {
            vault.wrapKey.delete()
            vault.session.withDatabase { db ->
                db.execSQL("CREATE TABLE t(v TEXT)")
                db.execSQL("INSERT INTO t VALUES ('persisted')")
            }
            vault.session.close()
            vault.blobHash.writeBytes(sha256(vault.blob))
        }
        pidFile.writeText(Process.myPid().toString())
    }

    /** Must run in a new process: the first phase records its pid. */
    @Test
    fun phaseReopenAfterKill() {
        onlyInPhase("reopen")
        assertNotEquals("the process must have been restarted", pidFile.readText(), Process.myPid().toString())
        for (vault in listOf(strict(), balanced())) {
            assertEquals("persisted", vault.read())
            vault.session.close()
            assertArrayEquals("the wrapped DEK must not change", vault.blobHash.readBytes(), sha256(vault.blob))
        }
    }

    /** The script turns the screen off when it sees the ready line. */
    @Test
    fun phaseLocked() {
        onlyInPhase("locked")
        assertFalse("start this phase unlocked", keyguard.isDeviceLocked)
        val strict = strict()
        val balanced = balanced(idleMs = BALANCED_IDLE_MS)
        assertEquals("persisted", strict.read())
        assertEquals("persisted", balanced.read())
        Log.i(TAG, "READY_FOR_SCREEN_OFF")

        waitUntil(SCREEN_OFF_WAIT_MS) { !strict.session.isOpen }
        assertArrayEquals("Strict: key must be zero", ByteArray(67), strict.session.lastClosedKey)
        Log.i(TAG, "strict closed on screen off")

        waitUntil(SCREEN_OFF_WAIT_MS) { keyguard.isDeviceLocked }
        Log.i(TAG, "device locked")

        assertThrows("Strict must not open while locked", VaultKeyException.DeviceLocked::class.java) {
            strict.read()
        }
        Log.i(TAG, "strict refused while locked")

        assertTrue("Balanced stays open after the screen turns off", balanced.session.isOpen)
        assertEquals("persisted", balanced.read())
        Log.i(TAG, "balanced still usable while locked")

        waitUntil(BALANCED_IDLE_MS + IDLE_MARGIN_MS) { !balanced.session.isOpen }
        assertArrayEquals("Balanced: key must be zero", ByteArray(67), balanced.session.lastClosedKey)
        Log.i(TAG, "balanced closed on idle timeout")
    }

    /** A fresh session in a new process, so the DEK has to be unwrapped while locked. */
    @Test
    fun phaseBalancedCannotOpenLocked() {
        onlyInPhase("balancedLocked")
        assertTrue("the device must be locked", keyguard.isDeviceLocked)
        val balanced = balanced()
        assertThrows(VaultKeyException.DeviceLocked::class.java) { balanced.read() }
        assertFalse(balanced.session.isOpen)
        assertTrue("the wrapped DEK must still exist", balanced.blob.exists())
        Log.i(TAG, "balanced refused to open while locked")
    }

    @Test
    fun phaseAfterUnlock() {
        onlyInPhase("afterUnlock")
        assertFalse("unlock the device first", keyguard.isDeviceLocked)
        for (vault in listOf(strict(), balanced())) {
            assertEquals("persisted", vault.read())
            vault.session.close()
            vault.wrapKey.delete()
        }
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
        const val BALANCED_IDLE_MS = 10_000L
        const val SCREEN_OFF_WAIT_MS = 90_000L
        const val IDLE_MARGIN_MS = 15_000L
    }
}
