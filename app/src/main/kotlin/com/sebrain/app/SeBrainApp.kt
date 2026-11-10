package com.sebrain.app

import android.app.Application
import android.util.Log
import com.sebrain.cognition.InboxImporter
import com.sebrain.core.crypto.WrappedDekStore
import com.sebrain.core.vault.CaptureStore
import com.sebrain.core.vault.VaultSession
import com.sebrain.ingest.ShareActivity
import java.io.File
import java.util.concurrent.Executors

/** Wires ingestion to the vault: a stored segment triggers an import of the inbox. */
class SeBrainApp : Application() {
    /** What the last import did. It contains counts only, never content. */
    data class Status(val pending: Int, val captures: Long?, val problem: String?)

    private val executor = Executors.newSingleThreadExecutor()

    val session: VaultSession by lazy {
        val dir = File(noBackupFilesDir, "vault")
        VaultSession(this, File(dir, "vault.db"), WrappedDekStore(File(dir, "dek.wrapped")))
    }

    private val importer by lazy { InboxImporter(ShareActivity.inboxDir(this), session) }

    override fun onCreate() {
        super.onCreate()
        ShareActivity.onCaptureStored = { requestImport() }
    }

    /** Imports the inbox in the background and reports counts. Safe to call at any time. */
    fun requestImport(onDone: (Status) -> Unit = {}) {
        executor.execute {
            val status = try {
                val result = importer.run()
                val problem = result.stoppedBy?.javaClass?.simpleName
                Log.i(
                    TAG,
                    "import imported=${result.imported} duplicates=${result.duplicates} " +
                        "quarantined=${result.quarantined} remaining=${result.remaining} stoppedBy=$problem",
                )
                val captures = if (problem == null) runCatching { session.withDatabase { CaptureStore(it).count() } }.getOrNull() else null
                Status(result.remaining, captures, problem)
            } catch (e: Exception) {
                Log.e(TAG, "import failed: ${e.javaClass.simpleName}")
                Status(-1, null, e.javaClass.simpleName)
            }
            Log.i(TAG, "status pending=${status.pending} captures=${status.captures} problem=${status.problem}")
            onDone(status)
        }
    }

    private companion object {
        const val TAG = "SEBRAIN_APP"
    }
}
