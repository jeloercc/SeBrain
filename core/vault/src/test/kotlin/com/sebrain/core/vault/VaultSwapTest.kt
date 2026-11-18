package com.sebrain.core.vault

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultSwapTest {
    private val root: File = Files.createTempDirectory("vault-swap").toFile()
    private val swap = VaultSwap(root)

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    /** A vault folder whose files say which vault it is. */
    private fun vault(dir: File, name: String) {
        dir.mkdirs()
        File(dir, "vault.db").writeText(name)
        File(dir, "dek.wrapped").writeText("key of $name")
    }

    private fun nameOf(dir: File): String? = File(dir, "vault.db").takeIf { it.exists() }?.readText()

    @Test
    fun aSwapMakesTheIncomingVaultActiveAndKeepsThePreviousOne() {
        vault(swap.active, "old")
        vault(swap.incoming, "restored")

        swap.replaceWithIncoming()

        assertEquals("restored", nameOf(swap.active))
        assertEquals("old", nameOf(swap.previous))
        assertFalse(swap.incoming.exists())
        assertFalse(swap.hasJournal())
    }

    @Test
    fun undoBringsThePreviousVaultBackAndKeepsTheRestoredOne() {
        vault(swap.active, "old")
        vault(swap.incoming, "restored")
        swap.replaceWithIncoming()

        swap.undo()

        assertEquals("old", nameOf(swap.active))
        assertEquals("restored", nameOf(swap.previous))
    }

    @Test
    fun aSecondRestoreReplacesTheOlderPreviousVault() {
        vault(swap.active, "first")
        vault(swap.incoming, "second")
        swap.replaceWithIncoming()
        vault(swap.incoming, "third")

        swap.replaceWithIncoming()

        assertEquals("third", nameOf(swap.active))
        assertEquals("second", nameOf(swap.previous))
    }

    @Test
    fun anIncompleteStageIsRefusedAndNothingMoves() {
        vault(swap.active, "old")
        swap.incoming.mkdirs()
        File(swap.incoming, "vault.db").writeText("restored without its key")

        assertThrows(IllegalStateException::class.java) { swap.replaceWithIncoming() }
        assertEquals("old", nameOf(swap.active))
        assertFalse(swap.hasJournal())
    }

    @Test
    fun aCrashBeforeTheFirstRenameLeavesTheActiveVault() {
        vault(swap.active, "old")
        vault(swap.incoming, "restored")
        swap.replaceWithIncoming(stopAfter = 0)

        assertEquals(VaultSwap.Recovery.NOTHING_MOVED, swap.recover())
        assertEquals("old", nameOf(swap.active))
        assertFalse(swap.hasJournal())
    }

    @Test
    fun aCrashAfterTheActiveVaultMovedOutFinishesTheSwap() {
        vault(swap.active, "old")
        vault(swap.incoming, "restored")
        swap.replaceWithIncoming(stopAfter = 1)
        assertFalse(swap.active.exists())

        assertEquals(VaultSwap.Recovery.FINISHED, swap.recover())
        assertEquals("restored", nameOf(swap.active))
        assertEquals("old", nameOf(swap.previous))
        assertFalse(swap.hasJournal())
    }

    @Test
    fun aCrashAfterTheNewVaultMovedInFinishesTheSwap() {
        vault(swap.active, "old")
        vault(swap.incoming, "restored")
        swap.replaceWithIncoming(stopAfter = 2)

        assertEquals(VaultSwap.Recovery.FINISHED, swap.recover())
        assertEquals("restored", nameOf(swap.active))
        assertEquals("old", nameOf(swap.previous))
    }

    @Test
    fun aCrashDuringAnUndoIsRecoveredToo() {
        vault(swap.active, "old")
        vault(swap.incoming, "restored")
        swap.replaceWithIncoming()
        swap.undo(stopAfter = 1)
        assertFalse(swap.active.exists())

        assertEquals(VaultSwap.Recovery.FINISHED, swap.recover())
        assertEquals("old", nameOf(swap.active))
        assertEquals("restored", nameOf(swap.previous))
    }

    @Test
    fun aStageLeftWithoutAJournalIsDeletedAtStart() {
        vault(swap.active, "old")
        vault(swap.incoming, "half restored")

        assertEquals(VaultSwap.Recovery.STAGE_DELETED, swap.recover())
        assertFalse(swap.incoming.exists())
        assertEquals("old", nameOf(swap.active))
    }

    @Test
    fun aNormalStartChangesNothing() {
        vault(swap.active, "old")
        vault(swap.previous, "older")

        assertEquals(VaultSwap.Recovery.NONE, swap.recover())
        assertEquals("old", nameOf(swap.active))
        assertEquals("older", nameOf(swap.previous))
    }

    @Test
    fun deletingThePreviousVaultLeavesTheActiveOne() {
        vault(swap.active, "old")
        vault(swap.previous, "older")

        swap.deletePrevious()

        assertFalse(swap.previous.exists())
        assertTrue(swap.active.exists())
    }
}
