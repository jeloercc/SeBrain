package com.sebrain.core.vault

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class VaultBackupSnapshotTest {
    private val record = VaultBackupSnapshot.Record(
        CaptureRecord(
            UUID.randomUUID(),
            "sha",
            "2026-11-11T22:00:00Z",
            "America/Chicago",
            "note",
            "subject",
            "body",
        ),
        "Title",
        "2026-11-11T22:01:00Z",
        listOf("tag"),
        listOf("Target"),
    )

    private val conversation = VaultBackupSnapshot.Conversation(
        "Kernels",
        "2026-11-12T10:00:00Z",
        "2026-11-12T10:05:00Z",
        "They listed kernel types.",
        2,
        listOf(
            VaultBackupSnapshot.Message(true, "List the kernel types", "2026-11-12T10:00:00Z"),
            VaultBackupSnapshot.Message(false, "Monolithic, microkernel.", "2026-11-12T10:00:09Z"),
        ),
    )

    @Test
    fun logicalSnapshotRoundTrips() {
        val restored = VaultBackupSnapshot.decode(VaultBackupSnapshot.encode(listOf(record), listOf(conversation)))

        assertEquals(listOf(record), restored.records)
        assertEquals(listOf(conversation), restored.conversations)
    }

    @Test
    fun aVersionOneSnapshotIsStillReadAndHasNoConversations() {
        val old = VaultBackupSnapshot.encode(listOf(record), version = 1)
        val restored = VaultBackupSnapshot.decode(old)

        assertEquals(listOf(record), restored.records)
        assertEquals(emptyList<VaultBackupSnapshot.Conversation>(), restored.conversations)
    }

    @Test
    fun aSnapshotFromANewerAppIsRejected() {
        val bytes = VaultBackupSnapshot.encode(listOf(record))
        bytes[7] = 9 // The version is the int after the 4-byte magic.
        assertThrows(IllegalArgumentException::class.java) { VaultBackupSnapshot.decode(bytes) }
    }
}
