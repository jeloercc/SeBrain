package com.sebrain.core.vault

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sebrain.core.crypto.WrapKey
import com.sebrain.core.crypto.WrappedDekStore
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Schema v6: paths, properties, aliases and links with anchors, on a test vault. */
@RunWith(AndroidJUnit4::class)
class NoteModelTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var dir: File
    private lateinit var wrapKey: WrapKey
    private lateinit var session: VaultSession

    @Before
    fun setUp() {
        dir = File(context.noBackupFilesDir, "model-test-${System.nanoTime()}").apply { mkdirs() }
        wrapKey = WrapKey(alias = "test_model_${System.nanoTime()}")
        session = VaultSession(context, File(dir, "vault.db"), WrappedDekStore(File(dir, "dek.wrapped"), wrapKey))
    }

    @After
    fun tearDown() {
        session.close()
        wrapKey.delete()
        dir.deleteRecursively()
    }

    /** Creates a note with its title set, and optionally a path, aliases, links and indexed text. */
    private fun note(
        title: String,
        text: String = "Texto de $title",
        path: String? = null,
        aliases: List<String> = emptyList(),
        links: List<NoteLink> = emptyList(),
        properties: List<NoteProperty> = emptyList(),
    ): Long = session.withDatabase { db ->
        val store = NoteStore(db)
        val id = store.create(text, NoteStore.contentHash(text, path), "2026-11-12T10:00:00Z", "UTC")!!
        if (path != null) assertTrue(store.setPath(id, path))
        store.setMarkup(id, title, emptyList(), links, aliases, properties)
        ChunkStore(db).index(id, listOf(Chunk(0, 0, text.length, text)), null)
        id
    }

    private fun version() = session.withDatabase { db ->
        db.rawQuery("PRAGMA user_version", emptyArray<String>()).use { c -> c.moveToFirst(); c.getInt(0) }
    }

    @Test
    fun aVaultFromVersionFiveKeepsItsLinks() {
        val coffee = note("Café")
        val morning = note("Mañanas", links = listOf(NoteLink("Café")))
        session.withDatabase { db ->
            // Take the database back to what version 5 looked like.
            for (sql in listOf(
                "DROP TABLE property", "DROP TABLE alias", "DROP TABLE brain", "DROP TABLE brain_pin",
                "DROP INDEX capture_by_path", "ALTER TABLE capture DROP COLUMN path",
                "ALTER TABLE conversation DROP COLUMN brain_id",
                "CREATE TABLE link_old(source_id INTEGER NOT NULL, target_title TEXT NOT NULL COLLATE NOCASE, PRIMARY KEY(source_id, target_title))",
                "INSERT INTO link_old SELECT DISTINCT source_id, target_title FROM link",
                "DROP TABLE link", "ALTER TABLE link_old RENAME TO link", "CREATE INDEX link_by_target ON link(target_title)",
                "PRAGMA user_version = 5",
            )) db.execSQL(sql)
        }
        session.close()

        assertEquals(6, version())
        session.withDatabase { db ->
            val store = NoteStore(db)
            assertEquals(listOf(morning), store.backlinks(coffee).map { it.id })
            assertEquals(listOf(coffee), store.linkedFrom(morning).map { it.id })
            assertNull(store.get(coffee)!!.path)
            // The new tables exist and are empty.
            assertEquals(emptyList<String>(), store.aliasesOf(coffee))
            db.rawQuery("SELECT count(*) FROM brain", emptyArray<String>()).use { c -> c.moveToFirst(); assertEquals(0, c.getInt(0)) }
        }
    }

    @Test
    fun propertiesAndAliasesAreStoredAndReadBack() {
        val properties = listOf(
            NoteProperty("status", "text", listOf("active")),
            NoteProperty("port", "number", listOf("443"), listOf(443.0)),
            NoteProperty("authors", "list", listOf("Ana", "Luis")),
        )
        val id = note("TCP", path = "Redes/Protocolos/TCP", aliases = listOf("Transmission Control Protocol"), properties = properties)
        session.withDatabase { db ->
            val read = NoteStore(db).get(id)!!
            assertEquals("Redes/Protocolos/TCP", read.path)
            assertEquals(listOf("Transmission Control Protocol"), read.aliases)
            assertEquals(properties.map { it.key to it.values }, read.properties.map { it.key to it.values })
            assertEquals(listOf<Double?>(443.0), read.properties.first { it.key == "port" }.numbers)
            // Writing the markup again replaces what was there.
            NoteStore(db).setMarkup(id, "TCP", emptyList(), emptyList(), emptyList(), listOf(NoteProperty("status", "text", listOf("done"))))
            assertEquals(listOf("status" to listOf("done")), NoteStore(db).get(id)!!.properties.map { it.key to it.values })
            assertEquals(emptyList<String>(), NoteStore(db).aliasesOf(id))
        }
    }

    @Test
    fun aLinkFindsItsNoteByTitleThenAliasThenTheEndOfItsPath() {
        val tcp = note("Protocolo TCP", path = "Redes/tcp-notes", aliases = listOf("TCP"))
        val other = note("TCP/IP", path = "Redes/Modelo")
        val byAlias = note("Capa de transporte", links = listOf(NoteLink("tcp", "Detalles")))
        val byFile = note("Índice", links = listOf(NoteLink("tcp-notes", embed = true)))
        session.withDatabase { db ->
            val store = NoteStore(db)
            assertEquals(tcp, store.findByTitle("Protocolo TCP"))
            assertEquals(tcp, store.findByTitle("tcp"))
            assertEquals(tcp, store.findByTitle("TCP-NOTES"))
            assertEquals(tcp, store.findByTitle("Redes/tcp-notes"))
            assertEquals(other, store.findByTitle("TCP/IP"))
            assertNull(store.findByTitle("notes"))
            assertNull(store.findByTitle("  "))

            assertEquals(setOf(byAlias, byFile), store.backlinks(tcp).map { it.id }.toSet())
            assertEquals(listOf(tcp), store.linkedFrom(byAlias).map { it.id })
            assertEquals(listOf(tcp), store.linkedFrom(byFile).map { it.id })

            // In the graph both links reach the same node instead of two unresolved ones.
            val graph = store.graph()
            assertTrue(graph.nodes.none { it.id == null })
            assertEquals(2, graph.edges.size)
        }
    }

    @Test
    fun theSameLinkWithDifferentAnchorsIsKeptOncePerAnchor() {
        val recipes = note("Recetas")
        val menu = note(
            "Menú",
            links = listOf(NoteLink("Recetas"), NoteLink("Recetas", "Postres"), NoteLink("Recetas", "Postres"), NoteLink("Recetas", embed = true)),
        )
        session.withDatabase { db ->
            db.rawQuery("SELECT count(*) FROM link WHERE source_id = ?", menu as Any).use { c -> c.moveToFirst(); assertEquals(3, c.getInt(0)) }
            // Three rows, one linked note.
            assertEquals(listOf(recipes), NoteStore(db).linkedFrom(menu).map { it.id })
            assertEquals(listOf(menu), NoteStore(db).backlinks(recipes).map { it.id })
        }
    }

    @Test
    fun unlinkedMentionsLeaveOutNotesThatAlreadyLink() {
        val tcp = note("Protocolo TCP", aliases = listOf("Transmission Control"))
        val linked = note("Ya enlazada", text = "El protocolo TCP está en [[Protocolo TCP]].", links = listOf(NoteLink("Protocolo TCP")))
        val mention = note("Mención", text = "Aquí se habla del protocolo tcp sin enlazarlo.")
        val byAlias = note("Por alias", text = "Transmission control aparece aquí.")
        note("Nada", text = "Un protocolo distinto, y TCP a solas.")
        session.withDatabase { db ->
            assertEquals(setOf(mention, byAlias), NoteStore(db).unlinkedMentions(tcp).map { it.id }.toSet())
            assertFalse(linked in NoteStore(db).unlinkedMentions(tcp).map { it.id })
            // A title too short to search gives nothing rather than everything.
            assertTrue(NoteStore(db).unlinkedMentions(note("IP")).isEmpty())
        }
    }

    @Test
    fun foldersCountTheirNotesAndSubfolders() {
        val tcp = note("TCP", path = "Redes/Protocolos/TCP")
        val udp = note("UDP", path = "Redes/Protocolos/UDP")
        val osi = note("OSI", path = "Redes/OSI")
        note("Suelta")
        session.withDatabase { db ->
            val store = NoteStore(db)
            assertEquals(listOf("Redes" to 3, "Redes/Protocolos" to 2), store.folders())
            assertEquals(listOf(osi, tcp, udp), store.inFolder("Redes", 10).map { it.id })
            assertEquals(listOf(tcp, udp), store.inFolder("redes/protocolos", 10).map { it.id })
            // "Redes" does not match a folder that only starts with the same letters.
            note("Otra", path = "RedesSociales/X")
            assertEquals(3, store.inFolder("Redes", 10).size)
            assertFalse(store.setPath(osi, "Redes/Protocolos/TCP"))
        }
    }

    @Test
    fun twoFilesWithTheSameTextAreTwoNotesAndTheSameSharedTextIsOne() {
        val a = note("Plantilla", text = "", path = "Diario/2026-11-11")
        val b = note("Plantilla", text = "", path = "Diario/2026-11-12")
        assertTrue(a != b)
        session.withDatabase { db ->
            val store = NoteStore(db)
            // Without a path the text alone decides, as before.
            assertNotNull(store.create("compartido", NoteStore.contentHash("compartido", null), "2026-11-12T10:00:00Z", "UTC"))
            assertNull(store.create("compartido", NoteStore.contentHash("compartido", null), "2026-11-12T10:00:01Z", "UTC"))
            // Editing a note with a path hashes path and text, whatever the caller passed.
            assertTrue(store.updateText(a, "nuevo", NoteStore.contentHash("nuevo", null), "2026-11-12T10:01:00Z"))
            assertTrue(store.updateText(b, "nuevo", NoteStore.contentHash("nuevo", null), "2026-11-12T10:01:00Z"))
            assertEquals("nuevo", store.get(b)!!.text)
        }
    }

    @Test
    fun deletingANoteRemovesItsAliasesAndProperties() {
        val id = note("TCP", aliases = listOf("Transmission"), properties = listOf(NoteProperty("status", "text", listOf("active"))))
        session.withDatabase { db ->
            NoteStore(db).delete(id)
            for (table in listOf("alias", "property")) {
                db.rawQuery("SELECT count(*) FROM $table", emptyArray<String>()).use { c -> c.moveToFirst(); assertEquals(table, 0, c.getInt(0)) }
            }
            assertNull(NoteStore(db).findByTitle("Transmission"))
        }
    }

    @Test
    fun aBackupCarriesThePathOfEachNote() {
        note("TCP", path = "Redes/TCP")
        note("Suelta")
        val snapshot = session.withDatabase { VaultBackupSnapshot.export(it) }
        val otherDir = File(context.noBackupFilesDir, "model-restore-${System.nanoTime()}").apply { mkdirs() }
        val otherKey = WrapKey(alias = "test_model_restore_${System.nanoTime()}")
        val other = VaultSession(context, File(otherDir, "vault.db"), WrappedDekStore(File(otherDir, "dek.wrapped"), otherKey))
        try {
            other.withDatabase { db ->
                assertEquals(2, VaultBackupSnapshot.restore(db, snapshot))
                val store = NoteStore(db)
                assertEquals("Redes/TCP", store.get(store.findByTitle("TCP")!!)!!.path)
                assertNull(store.get(store.findByTitle("Suelta")!!)!!.path)
            }
        } finally {
            other.close()
            otherKey.delete()
            otherDir.deleteRecursively()
        }
    }
}
