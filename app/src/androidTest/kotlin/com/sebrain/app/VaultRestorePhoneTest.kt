package com.sebrain.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sebrain.cognition.NoteIndexer
import com.sebrain.core.crypto.BackupMetadata
import com.sebrain.core.crypto.EncryptedBackupCodec
import com.sebrain.core.crypto.WrapKey
import com.sebrain.core.crypto.WrappedDekStore
import com.sebrain.core.vault.BrainRegistry
import com.sebrain.core.vault.CaptureStore
import com.sebrain.core.vault.NoteStore
import com.sebrain.core.vault.VaultBackupSnapshot
import com.sebrain.core.vault.VaultSession
import com.sebrain.core.vault.VaultSwap
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Replacing a vault with a backup, end to end on SQLCipher, in its own folder and with its own key: the real vault is never touched. */
@RunWith(AndroidJUnit4::class)
class VaultRestorePhoneTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(context.noBackupFilesDir, "restore-test-${System.nanoTime()}").apply { mkdirs() }
    private val wrapKey = WrapKey("test_restore_${System.nanoTime()}")
    private val swap = VaultSwap(root)
    private val active = VaultSession(context, File(swap.active, "vault.db"), WrappedDekStore(File(swap.active, "dek.wrapped"), wrapKey))
    private var forgotten = 0
    private val restore = VaultRestore(context, active, swap, NoteIndexer(), wrapKey) { forgotten++ }

    @After
    fun tearDown() {
        active.close()
        wrapKey.delete()
        root.deleteRecursively()
    }

    private fun note(text: String, path: String?) {
        active.withDatabase { db ->
            val store = NoteStore(db)
            val id = store.create(text, NoteIndexer.sha256Hex(text), NOW, "UTC")!!
            if (path != null) store.setPath(id, path)
        }
    }

    private fun count(): Long = active.withDatabase { CaptureStore(it).count() }

    /** A backup of the active vault, with the Personal brain made private first. */
    private fun backup(): ByteArray = active.withDatabase { db ->
        val registry = BrainRegistry(db)
        registry.list(NOW)
        registry.find("Personal")?.let { registry.setPrivate(it.id, true, NOW) }
        EncryptedBackupCodec.encrypt(VaultBackupSnapshot.export(db), PASSPHRASE.toCharArray(), BackupMetadata(1, NOW, CaptureStore(db).count()))
    }

    @Test
    fun aBackupReplacesTheVaultAndTheReplaceCanBeUndone() {
        note("# Diario\n\nUn día.", "Personal/Diario")
        note("# TCP\n\nFiable.", "Redes/TCP")
        val backup = backup()
        note("# After\n\nWritten after the backup.", null)

        val pending = restore.stage(backup, PASSPHRASE.toCharArray())
        assertEquals(2, pending.notes)
        assertEquals(2, pending.brains)
        assertEquals(0, pending.brainsMadePrivate)
        assertEquals(3L, pending.currentNotes)
        // Staging only reads the active vault.
        assertEquals(3L, count())

        restore.replace()
        assertEquals(2L, count())
        assertEquals(setOf("personal"), active.withDatabase { BrainRegistry(it).privateNames() })
        assertTrue(restore.hasPrevious)
        assertEquals(1, forgotten)

        restore.undo()
        assertEquals(3L, count())
        assertEquals(2, forgotten)

        restore.deletePrevious()
        assertFalse(restore.hasPrevious)
        assertEquals(3L, count())
    }

    @Test
    fun aWrongPassphraseLeavesNoStageAndTheVaultUntouched() {
        note("# TCP\n\nFiable.", "Redes/TCP")
        val backup = backup()

        assertThrows(Exception::class.java) { restore.stage(backup, "wrong passphrase".toCharArray()) }

        assertFalse(swap.incoming.exists())
        assertEquals(1L, count())
    }

    private companion object {
        const val NOW = "2026-11-17T10:00:00Z"
        const val PASSPHRASE = "correct horse battery staple"
    }
}
