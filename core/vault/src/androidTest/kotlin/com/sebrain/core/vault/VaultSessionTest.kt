package com.sebrain.core.vault

import android.security.keystore.KeyProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sebrain.core.crypto.VaultKeyException
import com.sebrain.core.crypto.WrapKey
import com.sebrain.core.crypto.WrappedDekStore
import java.io.File
import java.security.MessageDigest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Session checks that need an unlocked device and a single process. */
@RunWith(AndroidJUnit4::class)
class VaultSessionTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var dir: File
    private lateinit var databaseFile: File
    private lateinit var blobFile: File
    private lateinit var wrapKey: WrapKey

    @Before
    fun setUp() {
        dir = File(context.noBackupFilesDir, "session-test-${System.nanoTime()}").apply { mkdirs() }
        databaseFile = File(dir, "vault.db")
        blobFile = File(dir, "dek.wrapped")
        wrapKey = WrapKey(alias = "test_wrap_${System.nanoTime()}")
    }

    @After
    fun tearDown() {
        wrapKey.delete()
        dir.deleteRecursively()
    }

    private fun session(
        idleTimeoutMs: Long = VaultSession.DEFAULT_IDLE_TIMEOUT_MS,
        key: WrapKey = wrapKey,
    ) = VaultSession(context, databaseFile, WrappedDekStore(blobFile, key), VaultMode.BALANCED, idleTimeoutMs)

    private fun createVault() = session().use { s ->
        s.withDatabase { db ->
            db.execSQL("CREATE TABLE t(v TEXT)")
            db.execSQL("INSERT INTO t VALUES ('kept')")
        }
    }

    private fun readValue(s: VaultSession): String = s.withDatabase { db ->
        db.rawQuery("SELECT v FROM t", emptyArray<String>()).use { c ->
            assertTrue(c.moveToFirst())
            c.getString(0)
        }
    }

    private fun sha256(file: File): ByteArray = MessageDigest.getInstance("SHA-256").digest(file.readBytes())

    @Test
    fun firstOpenCreatesAndWrapsDek() {
        assertFalse(blobFile.exists())
        session().use { s ->
            s.withDatabase { it.execSQL("CREATE TABLE t(v TEXT)") }
            assertTrue(s.isOpen)
        }
        assertEquals(65L, blobFile.length())
        assertEquals("SBDK", String(blobFile.readBytes(), 0, 4, Charsets.US_ASCII))
        assertEquals(KeyProperties.SECURITY_LEVEL_STRONGBOX, wrapKey.securityLevel())
        val header = databaseFile.readBytes().copyOf(16)
        assertFalse(header.contentEquals("SQLite format 3\u0000".toByteArray()))
    }

    @Test
    fun secondSessionUnwrapsTheSameDek() {
        createVault()
        val blob = sha256(blobFile)
        session().use { s -> assertEquals("kept", readValue(s)) }
        assertArrayEquals("the wrapped DEK must not change", blob, sha256(blobFile))
    }

    @Test
    fun wrapKeyWorksWithoutStrongBox() {
        val tee = WrapKey(alias = "test_tee_${System.nanoTime()}", preferStrongBox = false)
        try {
            session(key = tee).use { s ->
                s.withDatabase { it.execSQL("CREATE TABLE t(v TEXT)") }
            }
            assertEquals(KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT, tee.securityLevel())
        } finally {
            tee.delete()
        }
    }

    @Test
    fun idleTimeoutClosesAndZeroesTheKey() {
        createVault()
        val s = session(idleTimeoutMs = 400)
        assertEquals("kept", readValue(s))
        assertTrue(s.isOpen)
        waitUntil(5_000) { !s.isOpen }
        val key = s.lastClosedKey
        assertEquals(67, key?.size)
        assertArrayEquals("raw key must be zero", ByteArray(67), key)
        assertEquals("the session reopens on demand", "kept", readValue(s))
        s.close()
    }

    @Test
    fun idleTimeoutDoesNotCloseDuringWork() {
        createVault()
        val s = session(idleTimeoutMs = 300)
        s.withDatabase {
            Thread.sleep(900)
            assertTrue(s.isOpen)
        }
        waitUntil(5_000) { !s.isOpen }
    }

    @Test
    fun closeZeroesTheKey() {
        createVault()
        val s = session()
        readValue(s)
        s.close()
        assertFalse(s.isOpen)
        assertArrayEquals(ByteArray(67), s.lastClosedKey)
    }

    @Test
    fun missingBlobIsAClearErrorAndKeepsTheDatabase() {
        createVault()
        val database = sha256(databaseFile)
        val blob = blobFile.readBytes()
        assertTrue(blobFile.delete())
        val error = assertThrows(VaultKeyException.WrappedDekMissing::class.java) {
            session().use { readValue(it) }
        }
        assertTrue(error.message, error.message.orEmpty().contains("not modified"))
        assertFalse("no new DEK may be created for an existing database", blobFile.exists())
        assertArrayEquals(database, sha256(databaseFile))
        blobFile.writeBytes(blob)
        session().use { s -> assertEquals("kept", readValue(s)) }
    }

    @Test
    fun corruptedBlobIsAClearErrorAndKeepsTheDatabase() {
        createVault()
        val database = sha256(databaseFile)
        val original = blobFile.readBytes()
        val corruptions = mapOf<String, (ByteArray) -> ByteArray>(
            "truncated" to { it.copyOf(40) },
            "extended" to { it + 0 },
            "magic" to { b -> b.copyOf().also { it[0] = 0 } },
            "version" to { b -> b.copyOf().also { it[4] = 9 } },
            "iv" to { b -> b.copyOf().also { it[6] = (it[6].toInt() xor 1).toByte() } },
            "ciphertext" to { b -> b.copyOf().also { it[20] = (it[20].toInt() xor 1).toByte() } },
            "tag" to { b -> b.copyOf().also { it[64] = (it[64].toInt() xor 1).toByte() } },
        )
        for ((name, corrupt) in corruptions) {
            blobFile.writeBytes(corrupt(original))
            val error = assertThrows(name, VaultKeyException.WrappedDekCorrupted::class.java) {
                session().use { readValue(it) }
            }
            assertTrue(error.message, error.message.orEmpty().contains("not modified"))
            assertArrayEquals(name, database, sha256(databaseFile))
        }
        blobFile.writeBytes(original)
        session().use { s -> assertEquals("kept", readValue(s)) }
    }

    @Test
    fun missingWrapKeyIsAClearErrorAndKeepsTheDatabase() {
        createVault()
        val database = sha256(databaseFile)
        wrapKey.delete()
        assertThrows(VaultKeyException.WrapKeyMissing::class.java) {
            session().use { readValue(it) }
        }
        assertArrayEquals(database, sha256(databaseFile))
    }

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            assertTrue("timed out", System.currentTimeMillis() < deadline)
            Thread.sleep(50)
        }
    }
}
