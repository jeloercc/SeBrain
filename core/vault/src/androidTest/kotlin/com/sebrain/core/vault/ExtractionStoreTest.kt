package com.sebrain.core.vault

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sebrain.core.crypto.WrapKey
import com.sebrain.core.crypto.WrappedDekStore
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Schema v7: what the CognitionWorker extracts, kept apart from the note's own text. */
@RunWith(AndroidJUnit4::class)
class ExtractionStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var dir: File
    private lateinit var wrapKey: WrapKey
    private lateinit var session: VaultSession

    private val fields = ExtractionStore.Extracted(
        listOf("Laura"), listOf("2026-11-12"), listOf("diseño"), listOf("traer bocetos"), "Reunión de diseño", "model.litertlm", "2026-11-20T10:00:00Z",
    )

    @Before
    fun setUp() {
        dir = File(context.noBackupFilesDir, "extraction-test-${System.nanoTime()}").apply { mkdirs() }
        wrapKey = WrapKey(alias = "test_extraction_${System.nanoTime()}")
        session = VaultSession(context, File(dir, "vault.db"), WrappedDekStore(File(dir, "dek.wrapped"), wrapKey))
    }

    @After
    fun tearDown() {
        session.close()
        wrapKey.delete()
        dir.deleteRecursively()
    }

    private fun capture(text: String, source: String = "share"): Long = session.withDatabase { db ->
        CaptureStore(db).insertReturningId(CaptureRecord(UUID.randomUUID(), text.hashCode().toString(), "2026-11-08T03:24:00Z", "America/Mexico_City", source, null, text))!!
    }

    @Test
    fun onlyCapturesFromOutsideWaitForExtraction() {
        val shared = capture("WhatsApp · Laura: reunión el jueves")
        capture("# Mi nota\n\nEscrita por mí", source = NoteStore.SOURCE_NOTE)
        capture("# Importada\n\nDe Obsidian", source = NoteStore.SOURCE_VAULT)

        val pending = session.withDatabase { ExtractionStore(it).pending(10) }

        assertEquals(listOf(shared), pending.map { it.id })
        assertEquals("America/Mexico_City", pending.single().timeZone)
    }

    @Test
    fun aSavedExtractionIsReadBackAndLeavesThePendingList() {
        val id = capture("WhatsApp · Laura: reunión el jueves")

        session.withDatabase { db ->
            val store = ExtractionStore(db)
            store.save(id, fields, attempts = 2)

            assertEquals(fields, store.get(id))
            assertEquals(ExtractionStore.STATUS_OK_RETRY to null, store.statusOf(id))
            assertEquals(emptyList<ExtractionStore.Pending>(), store.pending(10))
        }
    }

    @Test
    fun aFailureKeepsOnlyItsCode() {
        val id = capture("texto")

        session.withDatabase { db ->
            val store = ExtractionStore(db)
            store.fail(id, "title_empty", attempts = 2)

            assertEquals(ExtractionStore.STATUS_FAILED to "title_empty", store.statusOf(id))
            assertNull(store.get(id))
        }
    }

    @Test
    fun editingTheTextDropsTheOldExtraction() {
        val id = capture("WhatsApp · Laura: reunión el jueves")

        session.withDatabase { db ->
            val store = ExtractionStore(db)
            store.save(id, fields, attempts = 1)
            NoteStore(db).updateText(id, "WhatsApp · Laura: reunión el viernes", "new-hash", "2026-11-20T11:00:00Z")

            assertNull(store.get(id))
            assertEquals(listOf(id), store.pending(10).map { it.id })
        }
    }

    @Test
    fun deletingTheNoteDeletesItsExtraction() {
        val id = capture("WhatsApp · Laura: reunión el jueves")

        session.withDatabase { db ->
            ExtractionStore(db).save(id, fields, attempts = 1)
            NoteStore(db).delete(id)

            val rows = db.rawQuery("SELECT count(*) FROM extraction", emptyArray<String>()).use { c -> c.moveToFirst(); c.getInt(0) }
            assertEquals(0, rows)
        }
    }

    @Test
    fun aVersionSixVaultGainsTheTableAndKeepsItsCaptures() {
        val id = capture("WhatsApp · Laura: reunión el jueves")
        session.withDatabase { db ->
            for (sql in listOf("DROP TABLE extraction", "ALTER TABLE capture DROP COLUMN extraction_error", "PRAGMA user_version = 6")) db.execSQL(sql)
        }
        session.close()

        session.withDatabase { db ->
            val version = db.rawQuery("PRAGMA user_version", emptyArray<String>()).use { c -> c.moveToFirst(); c.getInt(0) }
            assertEquals(7, version)
            assertEquals(listOf(id), ExtractionStore(db).pending(10).map { it.id })
        }
    }
}
