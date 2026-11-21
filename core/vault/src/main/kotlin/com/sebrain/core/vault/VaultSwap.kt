package com.sebrain.core.vault

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Replaces the active vault folder with another one, so that a crash at any point leaves a
 * vault that opens: each step is one atomic folder rename, and a journal says which steps are
 * done. Whole folders move, so `vault.db`, its wrapped DEK and any SQLite journal files stay
 * together. The caller must have closed the active vault (`VaultSession.replaceWith`).
 * See docs/design-vault-restore.md.
 *
 * Folders under [root]: `vault/` (active), `vault-incoming/` (a validated restore),
 * `vault-previous/` (the vault before the last swap, kept so the swap can be undone).
 */
class VaultSwap(private val root: File) {
    val active = File(root, "vault")
    val incoming = File(root, "vault-incoming")
    val previous = File(root, "vault-previous")
    private val swappedOut = File(root, "vault-swapped-out")
    private val journal = File(root, "vault-swap.journal")

    enum class Recovery { NONE, STAGE_DELETED, NOTHING_MOVED, FINISHED }

    fun hasJournal(): Boolean = journal.exists()

    /** Makes the validated restore in [incoming] the active vault; the active one becomes [previous]. */
    @Synchronized
    fun replaceWithIncoming(stopAfter: Int = STEPS) = swapIn(incoming, stopAfter)

    /** Makes [previous] the active vault again; the active one becomes [previous], so undo can be undone. */
    @Synchronized
    fun undo(stopAfter: Int = STEPS) = swapIn(previous, stopAfter)

    @Synchronized
    fun deletePrevious() {
        check(!journal.exists()) { "A vault swap is unfinished" }
        previous.deleteRecursively()
    }

    /**
     * Run before the vault is first opened. Finishes a swap a crash interrupted, or drops a
     * restore that was never swapped in. It never deletes the active vault or [previous].
     */
    @Synchronized
    fun recover(): Recovery {
        if (!journal.exists()) {
            if (!incoming.exists()) return Recovery.NONE
            incoming.deleteRecursively()
            return Recovery.STAGE_DELETED
        }
        val source = sourceInJournal()
        return when {
            // Before the first rename: nothing moved.
            !swappedOut.exists() && active.exists() && source.exists() -> {
                journal.delete()
                Recovery.NOTHING_MOVED
            }
            // After the active vault moved out: move the new one in, then finish.
            swappedOut.exists() && !active.exists() && source.exists() -> {
                move(source, active)
                finish()
                Recovery.FINISHED
            }
            // After the new vault moved in, or during the last step: finish.
            else -> {
                finish()
                Recovery.FINISHED
            }
        }
    }

    /** The three steps; [stopAfter] exists so tests can stop where a crash would. */
    private fun swapIn(source: File, stopAfter: Int) {
        check(isComplete(source)) { "${source.name} is not a complete vault" }
        check(!journal.exists()) { "A vault swap is unfinished" }
        writeJournal(source.name)
        if (stopAfter < 1) return
        if (active.exists()) move(active, swappedOut)
        if (stopAfter < 2) return
        move(source, active)
        if (stopAfter < 3) return
        finish()
    }

    /** The vault that moved out becomes [previous], replacing an older one; then the journal goes. */
    private fun finish() {
        if (swappedOut.exists()) {
            previous.deleteRecursively()
            move(swappedOut, previous)
        }
        journal.delete()
    }

    private fun sourceInJournal(): File {
        val name = journal.readText().trim()
        check(name == incoming.name || name == previous.name) { "Unknown vault swap journal" }
        return File(root, name)
    }

    /** Written to a temporary file, synced, then renamed, so the journal is never half written. */
    private fun writeJournal(sourceName: String) {
        val temporary = File(root, "${journal.name}.tmp")
        FileOutputStream(temporary).use { output ->
            output.write(sourceName.toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        move(temporary, journal)
    }

    private fun move(from: File, to: File) {
        Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE)
    }

    private companion object {
        const val STEPS = 3

        fun isComplete(dir: File) = File(dir, "vault.db").isFile && File(dir, "dek.wrapped").isFile
    }
}
