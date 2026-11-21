package com.sebrain.core.vault

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sebrain.core.crypto.WrapKey
import com.sebrain.core.crypto.WrappedDekStore
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The agent's long-term memory by time, by property, and its templates, on SQLCipher. */
@RunWith(AndroidJUnit4::class)
class MemoryStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var dir: File
    private lateinit var wrapKey: WrapKey
    private lateinit var session: VaultSession

    @Before
    fun setUp() {
        dir = File(context.noBackupFilesDir, "memory-test-${System.nanoTime()}").apply { mkdirs() }
        wrapKey = WrapKey(alias = "test_memory_${System.nanoTime()}")
        session = VaultSession(context, File(dir, "vault.db"), WrappedDekStore(File(dir, "dek.wrapped"), wrapKey))
    }

    @After
    fun tearDown() {
        session.close()
        wrapKey.delete()
        dir.deleteRecursively()
    }

    private fun note(title: String, capturedAt: String, path: String? = null, properties: List<NoteProperty> = emptyList()): Long =
        session.withDatabase { db ->
            val store = NoteStore(db)
            val text = "# $title\n\nAbout $title."
            val id = store.create(text, NoteStore.contentHash(text, path), capturedAt, "UTC")!!
            if (path != null) store.setPath(id, path)
            store.setMarkup(id, title, emptyList(), emptyList(), properties = properties)
            id
        }

    @Test
    fun notesFromAPeriodIncludeDailyNotesAndDateProperties() {
        val written = note("Router", "2026-11-17T09:00:00Z")
        val daily = note("2026-11-16", "2026-11-18T08:00:00Z", path = "Daily/2026-11-16")
        val dated = note("Trip", "2026-11-01T08:00:00Z", properties = listOf(NoteProperty("date", "date", listOf("2026-11-15"))))
        note("Older", "2026-10-01T08:00:00Z")
        note("Meeting template", "2026-11-17T09:00:00Z", path = "Templates/Meeting")
        session.withDatabase { db ->
            val found = NoteStore(db).writtenBetween("2026-11-15", "2026-11-17", 10).map { it.id }.toSet()
            // Written then, a daily note of that day (written later), and a note dated by its property.
            assertEquals(setOf(written, daily, dated), found)
        }
    }

    @Test
    fun notesByPropertyIgnoreCaseAndLeaveDatesOut() {
        val pending = note("Pay rent", "2026-11-10T08:00:00Z", properties = listOf(NoteProperty("status", "text", listOf("pending"))))
        val alsoPending = note("Call Ana", "2026-11-11T08:00:00Z", properties = listOf(NoteProperty("status", "text", listOf("Pending"))))
        note("Done thing", "2026-11-12T08:00:00Z", properties = listOf(NoteProperty("status", "text", listOf("done")), NoteProperty("due", "date", listOf("2026-11-20"))))
        session.withDatabase { db ->
            val store = NoteStore(db)
            val values = store.propertyValues()
            // "pending" and "Pending" are one value; either spelling may come back.
            val first = values.first()
            assertEquals(Triple("status", "pending", 2), Triple(first.key, first.value.lowercase(), first.notes))
            assertFalse("dates are recalled by time, not as values", values.any { it.key == "due" })
            assertEquals(setOf(pending, alsoPending), store.withProperty("Status", "PENDING", 10).map { it.id }.toSet())
        }
    }

    @Test
    fun numbersAndDatesAreComparedWithTheExactTotal() {
        note("Fix roof", "2026-11-10T08:00:00Z", properties = listOf(NoteProperty("priority", "number", listOf("3"), listOf(3.0)), NoteProperty("due", "date", listOf("2026-11-19"))))
        note("Paint door", "2026-11-11T08:00:00Z", properties = listOf(NoteProperty("priority", "number", listOf("1"), listOf(1.0)), NoteProperty("due", "date", listOf("2026-11-25"))))
        note("Clean car", "2026-11-12T08:00:00Z", properties = listOf(NoteProperty("priority", "number", listOf("5"), listOf(5.0))))
        session.withDatabase { db ->
            val store = NoteStore(db)
            assertEquals(setOf("priority" to "number", "due" to "date"), store.propertyKeys().map { it.key to it.type }.toSet())
            val (above, highNotes) = store.compared("priority", ">", "2", false, 10)
            assertEquals(2, above)
            assertEquals(setOf("Fix roof", "Clean car"), highNotes.map { it.title }.toSet())
            val (dueSoon, soonNotes) = store.compared("due", "<", "2026-11-20", true, 10)
            assertEquals(1 to listOf("Fix roof"), dueSoon to soonNotes.map { it.title })
        }
    }

    @Test
    fun templatesAreNeitherABrainNorMemory() {
        note("Meeting", "2026-11-10T08:00:00Z", path = "Templates/Meeting", properties = listOf(NoteProperty("status", "text", listOf("pending"))))
        note("TCP", "2026-11-10T08:00:00Z", path = "Redes/TCP")
        session.withDatabase { db ->
            val store = NoteStore(db)
            assertEquals(listOf("Meeting"), store.templates().map { it.first })
            assertEquals(listOf("Redes"), BrainRegistry(db).list("2026-11-10T08:00:00Z").map { it.name })
            assertEquals(emptyList<NoteStore.PropertyValue>(), store.propertyValues())
            assertEquals(1, store.writtenBetween("2026-11-10", "2026-11-10", 10).size)
        }
    }
}
