package com.sebrain.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sebrain.app.chat.VaultContext
import com.sebrain.cognition.NoteIndexer
import com.sebrain.core.vault.CaptureStore
import com.sebrain.core.vault.ChunkStore
import com.sebrain.core.vault.HybridSearch
import com.sebrain.core.vault.NoteStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the assistant is given to read, on a test vault with its own keys: chunks that carry
 * their note's title and section, and a note named in the message read whole. No embedding
 * model is used, so this is the words half of the hybrid search.
 */
@RunWith(AndroidJUnit4::class)
class VaultContextTest {
    private lateinit var vault: TestVault
    private val indexer = NoteIndexer()

    private val ssh = "# Protocolo SSH\n\nPermite administrar otra máquina de forma segura. " +
        "Cifra toda la sesión, de modo que nadie en la red puede leerla. ".repeat(14) +
        "\n\n## Detalles\n\n### Puertos\nEscucha en el 22 por defecto.\n\n#redes #seguridad\n"

    @Before
    fun setUp() {
        vault = TestVault(InstrumentationRegistry.getInstrumentation().targetContext, "context_${System.nanoTime()}")
        note(ssh)
        note("# Café\n\nLo tomo sin azúcar por la mañana.\n\n#habitos\n")
        note("# Jellyfin\n\nServidor multimedia. Corre en el servidor casero.\n")
    }

    @After
    fun tearDown() = vault.deleteEverything()

    private fun note(text: String): Long = vault.session.withDatabase { db ->
        val id = NoteStore(db).create(text, NoteIndexer.sha256Hex(text), "2026-11-15T10:00:00Z", "UTC")!!
        indexer.index(db, id, text)
        id
    }

    @Test
    fun everyChunkCarriesItsNoteAndSection() {
        vault.session.withDatabase { db ->
            val chunks = db.rawQuery("SELECT text FROM chunk ORDER BY id", emptyArray<String>()).use { c ->
                generateSequence { if (c.moveToNext()) c.getString(0) else null }.toList()
            }
            val heads = chunks.joinToString(" | ") { it.take(70).replace("\n", "⏎") }
            assertTrue(heads, chunks.all { it.startsWith("[") })
            // Every heading starts a chunk; its own heading is in its text, the ones above it in its header.
            assertTrue(heads, chunks.any { it.startsWith("[Protocolo SSH · #redes #seguridad]\n## Detalles") })
            assertTrue(heads, chunks.any { it.startsWith("[Protocolo SSH · Detalles · #redes #seguridad]\n### Puertos\nEscucha en el 22") })
            assertTrue(heads, chunks.any { it.startsWith("[Café · #habitos]\n") })
        }
    }

    @Test
    fun aChunkIsFoundByItsNotesTitleEvenIfItsOwnTextDoesNotSayIt() {
        vault.session.withDatabase { db ->
            // "Escucha en el 22 por defecto" never says SSH; its header does.
            val hit = HybridSearch(db).search("SSH puertos", null).first()
            assertTrue(hit.text, hit.text.contains("Escucha en el 22 por defecto"))
        }
    }

    @Test
    fun aNoteNamedInTheMessageIsReadFirstAndWhole() {
        vault.session.withDatabase { db ->
            val message = "¿qué dice la nota protocolo ssh sobre los puertos?"
            val context = VaultContext.build(db, message, null, message = message)
            val first = context.notes.first()
            assertTrue(context.notes.joinToString { "${it.title} focus=${it.focus}" }, first.focus)
            assertEquals("Protocolo SSH", first.title)
            // The whole note: its first sentence and its last section.
            assertTrue(first.text.contains("Permite administrar otra máquina"))
            assertTrue(first.text.contains("Escucha en el 22 por defecto"))
            assertEquals(1, context.notes.count { it.title == "Protocolo SSH" })

            // A message that names no note has no focused note; one that names "Café" does.
            assertFalse(VaultContext.build(db, "¿cuánto azúcar lleva lo que tomo por la mañana?", null).notes.any { it.focus })
            assertEquals("Café", VaultContext.build(db, "¿cómo tomo el café?", null).notes.first { it.focus }.title)
        }
    }

    @Test
    fun markingEverythingPendingLetsTheVaultBeIndexedAgain() {
        vault.session.withDatabase { db ->
            val captures = CaptureStore(db)
            assertTrue(captures.withoutIndex(10).isEmpty())
            captures.markAllPending()
            val pending = captures.withoutIndex(10)
            assertEquals(3, pending.size)
            pending.forEach { (id, text) -> indexer.index(db, id, text) }
            assertTrue(captures.withoutIndex(10).isEmpty())
            assertEquals(ChunkStore.STATUS_TEXT, ChunkStore(db).statusOf(pending.first().first))
        }
    }
}
