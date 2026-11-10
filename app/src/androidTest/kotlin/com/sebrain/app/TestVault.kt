package com.sebrain.app

import android.content.Context
import com.sebrain.cognition.InboxImporter
import com.sebrain.core.crypto.DedupKey
import com.sebrain.core.crypto.InboxKey
import com.sebrain.core.crypto.InboxMode
import com.sebrain.core.crypto.WrapKey
import com.sebrain.core.crypto.WrappedDekStore
import com.sebrain.core.vault.CaptureStore
import com.sebrain.core.vault.VaultSession
import com.sebrain.ingest.CaptureIngestor
import com.sebrain.ingest.DedupIndex
import java.io.File

/** The whole pipeline with its own directory and Keystore aliases, apart from the real app data. */
class TestVault(context: Context, name: String) {
    val dir = File(context.noBackupFilesDir, name)
    val inbox = File(dir, "inbox")
    val indexFile = File(inbox, "dedup.idx")
    val databaseFile = File(dir, "vault.db")
    val balanced = InboxKey(InboxMode.BALANCED, "_$name")
    val strict = InboxKey(InboxMode.STRICT, "_$name")
    private val dedupKey = DedupKey("test_dedup_$name")
    private val wrapKey = WrapKey("test_wrap_$name")
    val session = VaultSession(context, databaseFile, WrappedDekStore(File(dir, "dek.wrapped"), wrapKey))
    val importer = InboxImporter(
        inbox,
        session,
        agree = { keyId, peer -> (if (keyId == strict.keyId) strict else balanced).agree(peer) },
    )

    /** A new instance each time, like a process restart. */
    fun ingestor(key: InboxKey = balanced) =
        CaptureIngestor(inbox, key.keyId, key::publicKey, DedupIndex(indexFile, dedupKey::tag))

    fun segments(): List<File> = inbox.listFiles { f -> f.name.endsWith(".seg") }.orEmpty().toList()

    fun captureCount(): Long = session.withDatabase { CaptureStore(it).count() }

    fun deleteEverything() {
        session.close()
        balanced.delete()
        strict.delete()
        dedupKey.delete()
        wrapKey.delete()
        dir.deleteRecursively()
    }
}
