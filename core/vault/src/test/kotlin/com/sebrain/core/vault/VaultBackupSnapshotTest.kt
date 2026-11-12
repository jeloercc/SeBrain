package com.sebrain.core.vault

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Test

class VaultBackupSnapshotTest {
    @Test
    fun logicalSnapshotRoundTrips() {
        val record = VaultBackupSnapshot.Record(
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

        val restored = VaultBackupSnapshot.decode(VaultBackupSnapshot.encode(listOf(record)))

        assertEquals(listOf(record), restored)
    }
}
