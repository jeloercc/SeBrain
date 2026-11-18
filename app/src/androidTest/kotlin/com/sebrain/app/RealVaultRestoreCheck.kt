package com.sebrain.app

import android.util.Log
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
import java.security.SecureRandom
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The by-hand restore check, done on a COPY of the real vault: export, add a note, restore,
 * replace, undo, delete the previous vault. It uses the real data and the real K_wrap, which
 * the other restore tests do not, but never opens the active vault: SeBrain must be stopped
 * (the script does it) so the copy is consistent. Only counts are logged. K_wrap is never
 * deleted; the copy is.
 *
 * Opt-in: `-e eval realRestore`.
 */
@RunWith(AndroidJUnit4::class)
class RealVaultRestoreCheck {
    private val arguments = InstrumentationRegistry.getArguments()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(context.noBackupFilesDir, "real-restore-check-${System.nanoTime()}")
    private var session: VaultSession? = null

    @After
    fun tearDown() {
        session?.close()
        root.deleteRecursively()
    }

    @Test
    fun restoreWorksOnACopyOfTheRealVault() {
        assumeTrue("opt-in: pass -e eval realRestore", arguments.getString("eval") == "realRestore")
        val real = File(context.noBackupFilesDir, "vault")
        assumeTrue("no real vault", File(real, "vault.db").isFile && File(real, "dek.wrapped").isFile)
        val swap = VaultSwap(root)
        // Every file of the folder, so a WAL file goes with its database.
        real.copyRecursively(swap.active)
        val copy = VaultSession(context, File(swap.active, "vault.db"), WrappedDekStore(File(swap.active, "dek.wrapped"), WrapKey()))
        session = copy
        val restore = VaultRestore(context, copy, swap, NoteIndexer(), WrapKey()) {}
        val now = java.time.Instant.now().toString()

        val (notes, privateBrains) = copy.withDatabase { db -> CaptureStore(db).count() to BrainRegistry(db).privateNames().size }
        val passphrase = CharArray(PASSPHRASE_CHARS) { "abcdefghijkmnpqrstuvwxyz23456789"[SecureRandom().nextInt(32)] }
        val backup = copy.withDatabase { db ->
            EncryptedBackupCodec.encrypt(VaultBackupSnapshot.export(db), passphrase.copyOf(), BackupMetadata(1, now, CaptureStore(db).count()))
        }
        copy.withDatabase { db ->
            val text = "# Restore check\n\nWritten after the backup."
            NoteStore(db).create(text, NoteIndexer.sha256Hex(text), now, "UTC")
        }
        log("start notes=$notes privateBrains=$privateBrains backupBytes=${backup.size}")

        val pending = restore.stage(backup, passphrase.copyOf())
        log("staged backupNotes=${pending.notes} conversations=${pending.conversations} brains=${pending.brains} madePrivate=${pending.brainsMadePrivate} currentNotes=${pending.currentNotes}")
        assertEquals(notes.toInt(), pending.notes)
        assertEquals(notes + 1, pending.currentNotes)
        assertEquals(0, pending.brainsMadePrivate)

        restore.replace()
        val replaced = copy.withDatabase { db -> CaptureStore(db).count() to BrainRegistry(db).privateNames().size }
        log("replaced notes=${replaced.first} privateBrains=${replaced.second}")
        assertEquals(notes to privateBrains, replaced)

        restore.undo()
        val undone = copy.withDatabase { CaptureStore(it).count() }
        log("undone notes=$undone")
        assertEquals(notes + 1, undone)

        restore.deletePrevious()
        assertFalse(restore.hasPrevious)
        passphrase.fill('\u0000')
        log("RESULT ok")
    }

    private fun log(line: String) {
        Log.i("SEBRAIN_EVAL", "realRestore $line")
    }

    private companion object {
        const val PASSPHRASE_CHARS = 24
    }
}
