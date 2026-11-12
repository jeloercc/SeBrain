package com.sebrain.ingest

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import com.sebrain.core.crypto.DedupKey
import com.sebrain.core.crypto.InboxKey
import com.sebrain.core.crypto.InboxMode
import java.io.File

/** Receives shared text, stores it encrypted and closes. It shows no content and logs none. */
class ShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent.takeIf { it.action == Intent.ACTION_SEND }?.getStringExtra(Intent.EXTRA_TEXT)
        if (text == null) {
            finishWith("Nothing to save")
            return
        }
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)
        val appContext = applicationContext
        Thread {
            val message = try {
                val result = ingestor(appContext).ingest(Capture(text, SOURCE, subject))
                Log.i(TAG, "share result=${result.javaClass.simpleName}")
                if (result is IngestResult.Stored) onCaptureStored?.invoke()
                when (result) {
                    is IngestResult.Stored -> "Saved"
                    IngestResult.Duplicate -> "Already saved"
                    IngestResult.Empty -> "Nothing to save"
                    IngestResult.TooLarge -> "Text too large"
                }
            } catch (e: Exception) {
                Log.e(TAG, "share failed: ${e.javaClass.simpleName}")
                "Could not save"
            }
            runOnUiThread { finishWith(message) }
        }.start()
    }

    private fun finishWith(message: String) {
        Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
        finish()
    }

    companion object {
        private const val TAG = "SEBRAIN_INGEST"
        private const val SOURCE = "share"

        /** Set by the app to react to a new segment, for example to import the inbox. */
        @Volatile
        var onCaptureStored: (() -> Unit)? = null

        fun inboxDir(context: Context) = File(context.noBackupFilesDir, "inbox")

        @Volatile
        private var shared: CaptureIngestor? = null

        /** One instance per process, so writes to the inbox and the tag index are serialized. */
        fun ingestor(context: Context): CaptureIngestor = shared ?: synchronized(this) {
            shared ?: run {
                val key = InboxKey(InboxMode.DEFAULT)
                val inbox = inboxDir(context)
                CaptureIngestor(inbox, key.keyId, key::publicKey, DedupIndex(File(inbox, "dedup.idx"), DedupKey()::tag))
            }.also { shared = it }
        }
    }
}
