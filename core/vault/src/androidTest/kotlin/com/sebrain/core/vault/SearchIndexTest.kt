package com.sebrain.core.vault

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sebrain.core.crypto.WrapKey
import com.sebrain.core.crypto.WrappedDekStore
import java.io.File
import java.util.UUID
import kotlin.math.sqrt
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SearchIndexTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var dir: File
    private lateinit var wrapKey: WrapKey
    private lateinit var session: VaultSession

    @Before
    fun setUp() {
        dir = File(context.noBackupFilesDir, "search-test-${System.nanoTime()}").apply { mkdirs() }
        wrapKey = WrapKey(alias = "test_search_${System.nanoTime()}")
        session = VaultSession(context, File(dir, "vault.db"), WrappedDekStore(File(dir, "dek.wrapped"), wrapKey))
    }

    @After
    fun tearDown() {
        session.close()
        wrapKey.delete()
        dir.deleteRecursively()
    }

    /** A unit vector pointing mostly along one axis, so nearest neighbors are predictable. */
    private fun vector(axis: Int, tilt: Int = (axis + 1) % 256): FloatArray {
        val v = FloatArray(256)
        v[axis] = 1f
        v[tilt] = 0.1f
        val norm = sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
        return FloatArray(256) { v[it] / norm }
    }

    private fun capture(text: String): Long = session.withDatabase { db ->
        CaptureStore(db).insertReturningId(
            CaptureRecord(UUID.randomUUID(), UUID.randomUUID().toString(), "2026-11-10T10:00:00Z", "UTC", "share", null, text),
        )!!
    }

    private fun index(captureId: Long, texts: List<String>, vectors: List<FloatArray>?) = session.withDatabase { db ->
        var offset = 0
        val chunks = texts.mapIndexed { i, t -> Chunk(i, offset, offset + t.length, t).also { offset += t.length } }
        ChunkStore(db).index(captureId, chunks, vectors)
    }

    @Test
    fun oldVaultIsUpgradedToTheSearchSchema() {
        val version = session.withDatabase { db ->
            db.rawQuery("PRAGMA user_version", emptyArray<String>()).use { c -> c.moveToFirst(); c.getInt(0) }
        }
        assertEquals(2, version)
        val id = capture("hello")
        assertEquals(ChunkStore.STATUS_PENDING, session.withDatabase { ChunkStore(it).statusOf(id) })
    }

    @Test
    fun textSearchFindsAccentedWordsAndRanksByRelevance() {
        val a = capture("a")
        index(a, listOf("La reunión con el equipo de diseño es el jueves", "Factura del banco pendiente"), null)
        assertEquals(ChunkStore.STATUS_TEXT, session.withDatabase { ChunkStore(it).statusOf(a) })

        val hits = session.withDatabase { HybridSearch(it).search("reunion diseño", null) }
        assertEquals(1, hits.size)
        assertTrue(hits[0].text.startsWith("La reunión"))
        assertEquals(1, hits[0].textRank)
        assertNull(hits[0].vectorRank)
        assertEquals(a, hits[0].captureId)
    }

    @Test
    fun queryTextIsNeverReadAsFtsSyntax() {
        index(capture("a"), listOf("plain words here"), null)
        session.withDatabase { db ->
            for (query in listOf("\"unbalanced", "words AND (", "NEAR(", "col:value*", "-", "", "   ")) {
                HybridSearch(db).search(query, null)
            }
            assertEquals(1, HybridSearch(db).search("words) OR \"", null).size)
        }
    }

    @Test
    fun vectorSearchFindsTheNearestChunk() {
        val a = capture("a")
        index(a, listOf("uno", "dos", "tres"), listOf(vector(1), vector(2), vector(3)))
        assertEquals(ChunkStore.STATUS_INDEXED, session.withDatabase { ChunkStore(it).statusOf(a) })

        val hits = session.withDatabase { HybridSearch(it).search("", vector(2, tilt = 9)) }
        assertEquals("dos", hits[0].text)
        assertEquals(1, hits[0].vectorRank)
        assertNull(hits[0].textRank)
        assertEquals(3, hits.size)
    }

    @Test
    fun fusionPrefersChunksFoundByBothSearches() {
        index(
            capture("a"),
            listOf("cita con la dentista", "cita en el banco", "receta de cocina"),
            listOf(vector(1), vector(2), vector(3)),
        )
        // Text matches the first two chunks; the vector is nearest to the second.
        val hits = session.withDatabase { HybridSearch(it).search("cita", vector(2, tilt = 9)) }
        assertEquals("cita en el banco", hits[0].text)
        assertTrue(hits[0].textRank != null && hits[0].vectorRank == 1)
        assertTrue(hits[0].score > hits[1].score)
        assertEquals(3, hits.size)
        assertEquals(2, session.withDatabase { HybridSearch(it).search("cita", vector(2, tilt = 9), limit = 2) }.size)
    }

    @Test
    fun vectorsCanBeAddedLater() {
        val a = capture("a")
        index(a, listOf("uno", "dos"), null)
        session.withDatabase { db ->
            val store = ChunkStore(db)
            val pending = store.chunksWithoutVector(10)
            assertEquals(listOf("uno", "dos"), pending.map { it.text })
            store.addVector(pending[0], vector(1))
            assertEquals(ChunkStore.STATUS_TEXT, store.statusOf(a))
            store.addVector(pending[1], vector(2))
            assertEquals(ChunkStore.STATUS_INDEXED, store.statusOf(a))
            assertTrue(store.chunksWithoutVector(10).isEmpty())
        }
    }

    @Test
    fun reindexingReplacesTheOldChunks() {
        val a = capture("a")
        index(a, listOf("viejo texto"), listOf(vector(1)))
        index(a, listOf("nuevo texto"), listOf(vector(2)))
        session.withDatabase { db ->
            assertTrue(HybridSearch(db).search("viejo", null).isEmpty())
            assertEquals(1, HybridSearch(db).search("nuevo", null).size)
            assertEquals(1, HybridSearch(db).search("", vector(1)).size)
        }
    }

    @Test
    fun wrongVectorSizeIsRejected() {
        val a = capture("a")
        assertThrows(IllegalArgumentException::class.java) { index(a, listOf("uno"), listOf(FloatArray(8))) }
        assertEquals("a failed index must leave nothing behind", 0, session.withDatabase { ChunkStore(it).chunksWithoutVector(10).size })
    }
}
