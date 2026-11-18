package com.sebrain.app.ui

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.sebrain.app.SeBrainApp
import com.sebrain.app.VaultRestore
import com.sebrain.core.crypto.BackupMetadata
import com.sebrain.core.crypto.EncryptedBackupCodec
import com.sebrain.core.vault.CaptureStore
import com.sebrain.core.vault.VaultBackupSnapshot
import com.sebrain.ingest.ShareActivity
import java.time.Instant

/**
 * The Backup section: encrypted export, and replacing the vault with a backup (checked first,
 * then replaced on confirmation, with undo). The screen sees counts and messages only.
 */
class BackupState(
    private val app: SeBrainApp,
    private val onMain: (() -> Unit) -> Unit,
    /** After the active vault changed: what the rest of the app shows must be read again. */
    private val onVaultReplaced: () -> Unit,
) {
    var backupStatus by mutableStateOf<String?>(null)
        private set

    /** A checked backup waiting for the user to replace the vault with it: counts only. */
    var pendingRestore by mutableStateOf<VaultRestore.Pending?>(null)
        private set

    /** Whether the vault from before the last restore is kept, so the restore can be undone. */
    var hasPreviousVault by mutableStateOf(false)
        private set

    private val vaultRestore by lazy {
        VaultRestore(app, app.session, app.vaultSwap, app.indexer) { ShareActivity.forgetDuplicates(app) }
    }

    fun refresh() {
        hasPreviousVault = vaultRestore.hasPrevious
    }

    fun exportBackup(uri: Uri, passphrase: CharArray) {
        app.background({
            try {
                val encrypted = app.session.withDatabase { db ->
                    val snapshot = VaultBackupSnapshot.export(db)
                    EncryptedBackupCodec.encrypt(
                        snapshot,
                        passphrase,
                        BackupMetadata(1, Instant.now().toString(), CaptureStore(db).count()),
                    )
                }
                app.contentResolver.openOutputStream(uri)?.use { it.write(encrypted) }
                    ?: error("Could not open backup destination")
                "Backup exported"
            } finally {
                passphrase.fill('\u0000')
            }
        }) { result ->
            onMain { backupStatus = result.fold({ it }, { "Backup failed (${it.javaClass.simpleName})" }) }
        }
    }

    /** Restores the backup into a new vault next to the active one and checks it; the active vault is unchanged. */
    fun restoreBackup(uri: Uri, passphrase: CharArray) {
        app.background({
            try {
                val encrypted = app.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Could not open backup")
                try {
                    vaultRestore.stage(encrypted, passphrase)
                } finally {
                    encrypted.fill(0)
                }
            } finally {
                passphrase.fill('\u0000')
            }
        }) { result ->
            onMain {
                pendingRestore = result.getOrNull()
                backupStatus = result.fold(
                    { p ->
                        "Backup checked: ${p.notes} note(s), ${p.conversations} conversation(s), ${p.brains} brain(s). " +
                            "The vault is unchanged until you replace it." +
                            if (p.brainsMadePrivate > 0) " This backup did not save which brains are private, so all ${p.brainsMadePrivate} will be." else ""
                    },
                    { "Restore failed (${it.javaClass.simpleName})" },
                )
            }
        }
    }

    fun replaceVault() {
        app.background({ vaultRestore.replace() }) { result ->
            onMain { afterSwap(result, "Vault replaced. The previous one is kept: UNDO RESTORE brings it back.") }
        }
    }

    fun undoRestore() {
        app.background({ vaultRestore.undo() }) { result -> onMain { afterSwap(result, "The previous vault is back.") } }
    }

    fun discardRestore() {
        app.background({ vaultRestore.discard() }) {
            onMain {
                pendingRestore = null
                backupStatus = "Restore discarded; the vault is unchanged"
            }
        }
    }

    fun deletePreviousVault() {
        app.background({ vaultRestore.deletePrevious() }) { result ->
            onMain {
                hasPreviousVault = vaultRestore.hasPrevious
                backupStatus = result.fold({ "Previous vault deleted" }, { "Could not delete the previous vault (${it.javaClass.simpleName})" })
            }
        }
    }

    private fun afterSwap(result: Result<Unit>, done: String) {
        pendingRestore = null
        hasPreviousVault = vaultRestore.hasPrevious
        backupStatus = result.fold({ done }, { "Could not swap the vault (${it.javaClass.simpleName}); try again" })
        if (result.isSuccess) onVaultReplaced()
    }
}
