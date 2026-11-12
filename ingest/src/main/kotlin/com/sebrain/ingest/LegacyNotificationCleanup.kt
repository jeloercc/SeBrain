package com.sebrain.ingest

import android.content.Context
import java.io.File
import java.security.KeyStore

/**
 * Notification capture was removed on 2026-11-12. This deletes what it left behind: the
 * encrypted package allowlist and the Keystore key that encrypted it. Notes already captured
 * stay in the vault.
 */
object LegacyNotificationCleanup {
    private const val FILE_NAME = "integration-policy.bin"
    private const val KEY_ALIAS = "sebrain.integration.policy"

    fun run(context: Context) {
        runCatching { File(context.noBackupFilesDir, FILE_NAME).delete() }
        runCatching {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (store.containsAlias(KEY_ALIAS)) store.deleteEntry(KEY_ALIAS)
        }
    }
}
