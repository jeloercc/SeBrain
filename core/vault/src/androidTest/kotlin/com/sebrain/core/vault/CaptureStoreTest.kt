package com.sebrain.core.vault

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sebrain.core.crypto.WrapKey
import com.sebrain.core.crypto.WrappedDekStore
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CaptureStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var dir: File
    private lateinit var wrapKey: WrapKey
    private lateinit var session: VaultSession

    @Before
    fun setUp() {
        dir = File(context.noBackupFilesDir, "store-test-${System.nanoTime()}").apply { mkdirs() }
        wrapKey = WrapKey(alias = "test_store_${System.nanoTime()}")
        session = VaultSession(context, File(dir, "vault.db"), WrappedDekStore(File(dir, "dek.wrapped"), wrapKey))
    }

    @After
    fun tearDown() {
        session.close()
        wrapKey.delete()
        dir.deleteRecursively()
    }

    private fun record(text: String, hash: String = text.hashCode().toString(), subject: String? = null) =
        CaptureRecord(UUID.randomUUID(), hash, "2026-11-09T10:00:00Z", "UTC", "share", subject, text)

    @Test
    fun schemaIsCreatedOnFirstOpenAndKeptOnReopen() {
        val version = { session.withDatabase { db ->
            db.rawQuery("PRAGMA user_version", emptyArray<String>()).use { c -> c.moveToFirst(); c.getInt(0) }
        } }
        assertEquals(2, version())
        session.withDatabase { CaptureStore(it).insert(record("kept")) }
        session.close()
        assertEquals(2, version())
        assertEquals(1L, session.withDatabase { CaptureStore(it).count() })
    }

    @Test
    fun insertsAndReadsBack() {
        val stored = record("Reunión el jueves", subject = "Asunto")
        val plain = record("sin asunto")
        session.withDatabase { db ->
            val store = CaptureStore(db)
            assertTrue(store.insert(stored))
            assertTrue(store.insert(plain))
            assertEquals(2L, store.count())
            assertEquals(stored, store.findBySegmentId(stored.segmentId))
            assertNull(store.findBySegmentId(plain.segmentId)!!.subject)
            assertNull(store.findBySegmentId(UUID.randomUUID()))
        }
    }

    @Test
    fun sameSegmentOrSameContentIsInsertedOnce() {
        val first = record("hello", hash = "h1")
        session.withDatabase { db ->
            val store = CaptureStore(db)
            assertTrue(store.insert(first))
            assertFalse("same segment", store.insert(first.copy(contentSha256 = "h2")))
            assertFalse("same content", store.insert(first.copy(segmentId = UUID.randomUUID())))
            assertEquals(1L, store.count())
            assertEquals("hello", store.findBySegmentId(first.segmentId)!!.text)
        }
    }

    @Test
    fun newCapturesStartAsPendingExtraction() {
        val stored = record("hello")
        session.withDatabase { db ->
            CaptureStore(db).insert(stored)
            db.rawQuery("SELECT extraction_status, extraction_attempts FROM capture", emptyArray<String>()).use { c ->
                c.moveToFirst()
                assertEquals("pending", c.getString(0))
                assertEquals(0, c.getInt(1))
            }
        }
    }
}
