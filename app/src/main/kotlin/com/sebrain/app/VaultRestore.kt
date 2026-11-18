package com.sebrain.app

import android.content.Context
import com.sebrain.cognition.NoteIndexer
import com.sebrain.core.crypto.EncryptedBackupCodec
import com.sebrain.core.crypto.WrapKey
import com.sebrain.core.crypto.WrappedDekStore
import com.sebrain.core.vault.CaptureStore
import com.sebrain.core.vault.VaultBackupSnapshot
import com.sebrain.core.vault.VaultSession
import com.sebrain.core.vault.VaultSwap
import java.io.File

/**
 * Replaces the active vault with an encrypted backup, in two steps the user confirms apart:
 * [stage] restores and checks the backup in its own new vault next to the active one, then
 * [replace] swaps it in, keeping the old vault so [undo] can bring it back. Only counts leave
 * this class, never note content. See docs/design-vault-restore.md.
 *
 * The folders, the active session and the wrapping key are given, so a test can use its own.
 */
class VaultRestore(
    private val context: Context,
    private val active: VaultSession,
    private val swap: VaultSwap,
    private val indexer: NoteIndexer,
    private val wrapKey: WrapKey = WrapKey(),
    /** Called after every swap: the capture dedup index no longer matches the vault. */
    private val forgetDuplicates: () -> Unit,
) {
    /** A validated restore waiting for confirmation: counts only. */
    data class Pending(
        val notes: Int,
        val conversations: Int,
        val brains: Int,
        /** Brains of an old backup, which did not save their settings, restored as private. */
        val brainsMadePrivate: Int,
        val backupCreatedAt: String,
        val currentNotes: Long,
        val currentConversations: Long,
    )

    class NotEnoughStorage : IllegalStateException("Not enough free storage for a second vault")

    val hasPrevious: Boolean get() = swap.previous.exists()

    /**
     * Decrypts [encrypted] and restores it into `vault-incoming/`, with a new DEK wrapped by
     * K_wrap, rebuilds its indexes and checks its counts against the backup's manifest. The
     * active vault is only read, for its counts. On any failure the stage is deleted.
     */
    fun stage(encrypted: ByteArray, passphrase: CharArray): Pending {
        swap.incoming.deleteRecursively()
        if (swap.incoming.parentFile!!.usableSpace < encrypted.size.toLong() * SPACE_FACTOR) throw NotEnoughStorage()
        var payload: ByteArray? = null
        try {
            val decrypted = EncryptedBackupCodec.decrypt(encrypted, passphrase)
            payload = decrypted.payload
            val staging = VaultSession(context, File(swap.incoming, "vault.db"), WrappedDekStore(File(swap.incoming, "dek.wrapped"), wrapKey))
            val restored = try {
                staging.withDatabase { db ->
                    VaultBackupSnapshot.restore(db, decrypted.payload).also { restored ->
                        check(indexer.rebuildAll(db).notes == restored.notes) { "Restore index count mismatch" }
                        check(restored.notes.toLong() == decrypted.metadata.recordCount) { "Restore does not match the backup's manifest" }
                    }
                }
            } finally {
                staging.close()
            }
            val (notes, conversations) = active.withDatabase { db -> CaptureStore(db).count() to conversationCount(db) }
            return Pending(
                restored.notes, restored.conversations, restored.brains, restored.brainsMadePrivate,
                decrypted.metadata.createdAt, notes, conversations,
            )
        } catch (e: Throwable) {
            swap.incoming.deleteRecursively()
            throw e
        } finally {
            payload?.fill(0)
        }
    }

    /** Makes the staged vault the active one; the active one is kept for [undo]. */
    fun replace() = swapWhileClosed { swap.replaceWithIncoming() }

    /** Brings back the vault from before the last [replace]; the restored one is kept in its place. */
    fun undo() = swapWhileClosed { swap.undo() }

    /**
     * A rename that fails halfway (storage full, for one) is finished or undone by the journal
     * before the lock is released, so the session never opens, or creates, a vault where the
     * active one is missing.
     */
    private fun swapWhileClosed(move: () -> Unit) {
        active.whileClosed {
            try {
                move()
            } catch (e: Throwable) {
                runCatching { swap.recover() }
                throw e
            }
        }
        forgetDuplicates()
    }

    fun discard() {
        swap.incoming.deleteRecursively()
    }

    fun deletePrevious() = swap.deletePrevious()

    private fun conversationCount(db: net.zetetic.database.sqlcipher.SQLiteDatabase): Long =
        db.rawQuery("SELECT count(*) FROM conversation", emptyArray<String>()).use { c -> if (c.moveToFirst()) c.getLong(0) else 0 }

    private companion object {
        /** Room for the restored vault and its indexes, next to the active one. */
        const val SPACE_FACTOR = 3
    }
}
