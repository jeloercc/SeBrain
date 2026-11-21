package com.sebrain.app

import android.app.Application
import android.util.Log
import com.sebrain.app.chat.SemanticRouter
import com.sebrain.cognition.InboxImporter
import com.sebrain.cognition.NoteIndexer
import com.sebrain.core.crypto.WrappedDekStore
import com.sebrain.core.ml.ChatEngine
import com.sebrain.core.ml.ModelFiles
import com.sebrain.core.ml.TextEmbedder
import com.sebrain.core.vault.CaptureStore
import com.sebrain.core.vault.VaultSession
import com.sebrain.core.vault.VaultSwap
import com.sebrain.ingest.ShareActivity
import java.io.File
import java.util.concurrent.Executors
import net.zetetic.database.sqlcipher.SQLiteDatabase

/** Wires ingestion, the vault and the embedding model. All vault and model work runs on one thread. */
class SeBrainApp : Application() {
    /** What the last import did. It contains counts only, never content. */
    data class Status(val pending: Int, val captures: Long?, val problem: String?)

    private val executor = Executors.newSingleThreadExecutor()

    /**
     * The one thread that uses [chat]: the chat screen and the CognitionWorker take turns on it,
     * one reply or one capture at a time, so the model is never loaded twice.
     */
    val chatThread = Executors.newSingleThreadExecutor()

    /** True while the app is on screen. The CognitionWorker does nothing then. */
    @Volatile
    var uiActive = false

    /** The vault folders a restore swaps: `vault/`, `vault-incoming/` and `vault-previous/`. */
    val vaultSwap by lazy { VaultSwap(noBackupFilesDir) }

    val session: VaultSession by lazy {
        // Before the first open: a restore that a crash interrupted is finished or dropped.
        val recovery = runCatching { vaultSwap.recover() }
        Log.i(TAG, "vault swap recovery=${recovery.getOrNull() ?: recovery.exceptionOrNull()?.javaClass?.simpleName}")
        val dir = vaultSwap.active
        VaultSession(
            this,
            File(dir, "vault.db"),
            WrappedDekStore(File(dir, "dek.wrapped")),
            com.sebrain.app.ui.SettingsState.idleTimeoutMs(this),
        )
    }

    val models by lazy { ModelFiles(File(filesDir, "models")) }
    val embedder by lazy { TextEmbedder(this, models) }

    /** The language model. It is used only from the chat screen's own thread. */
    // Speed figures on: the log gets token counts and tok/s per reply (never text), to compare NPU and GPU.
    val chat by lazy { ChatEngine(this, models, benchmark = true) }

    val indexer by lazy {
        NoteIndexer { documents -> if (embedder.isAvailable) embedder.embedTitledDocuments(documents) else null }
    }

    private val importer by lazy { InboxImporter(ShareActivity.inboxDir(this), session, indexer = indexer) }

    /** Called on a background thread after each import, for example to refresh a screen. */
    @Volatile
    var onImported: ((Status) -> Unit)? = null

    override fun onCreate() {
        super.onCreate()
        ShareActivity.onCaptureStored = { requestImport() }
        CognitionWorker.schedule(this)
        executor.execute { com.sebrain.ingest.LegacyNotificationCleanup.run(this) }
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
                if (result.imported > 0) CognitionWorker.runSoon(this)
                Status(result.remaining, captures, problem)
            } catch (e: Exception) {
                Log.e(TAG, "import failed: ${e.javaClass.simpleName}")
                Status(-1, null, e.javaClass.simpleName)
            }
            Log.i(TAG, "status pending=${status.pending} captures=${status.captures} problem=${status.problem}")
            onDone(status)
            onImported?.invoke(status)
        }
    }

    /**
     * Runs a search on the background thread. [block] receives the database and the query
     * vector, which is null if no embedding model is installed or it failed to load.
     */
    fun <T> search(query: String, block: (SQLiteDatabase, FloatArray?) -> T, onResult: (Result<T>) -> Unit) {
        executor.execute {
            val vector = if (query.isNotEmpty() && embedder.isAvailable) {
                runCatching { embedder.embedQuery(query) }.getOrNull()
            } else {
                null
            }
            onResult(runCatching { session.withDatabase { block(it, vector) } })
        }
    }

    /** Runs [block] with the vault on the background thread, for reads and writes. */
    fun <T> vault(block: (SQLiteDatabase) -> T, onResult: (Result<T>) -> Unit = {}) {
        executor.execute { onResult(runCatching { session.withDatabase(block) }) }
    }

    /** Runs model or file work on the background thread. */
    fun <T> background(block: () -> T, onResult: (Result<T>) -> Unit) {
        executor.execute { onResult(runCatching(block)) }
    }

    private var router: SemanticRouter? = null

    /**
     * The chat's semantic router, built the first time from the embedding model and kept: its
     * example vectors outlive [releaseModels]. Null without an embedding model. Call it only on
     * the vault thread, as [search] does.
     */
    fun router(): SemanticRouter? {
        if (router == null && embedder.isAvailable) {
            router = runCatching { SemanticRouter.build(embedder::embedQuery) }
                .onFailure { Log.w(TAG, "router not built: ${it.javaClass.simpleName}") }.getOrNull()
        }
        return router
    }

    /** Frees the embedding model's memory once queued work is done. */
    fun releaseModels() {
        executor.execute { embedder.close() }
    }

    private companion object {
        const val TAG = "SEBRAIN_APP"
    }
}
