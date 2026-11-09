package com.sebrain.ingest

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import com.sebrain.core.crypto.InboxKey
import com.sebrain.core.crypto.InboxMode
import java.io.File

/** Receives shared text, stores it encrypted and closes. It shows no content. */
class ShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent.takeIf { it.action == Intent.ACTION_SEND }?.getStringExtra(Intent.EXTRA_TEXT)
        if (text == null) {
            finishWith("Nothing to save")
            return
        }
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)
        Thread {
            val message = try {
                when (ingestor(this).ingest(Capture(text, SOURCE, subject))) {
                    is IngestResult.Stored -> "Saved"
                    IngestResult.Duplicate -> "Already saved"
                    IngestResult.Empty -> "Nothing to save"
                    IngestResult.TooLarge -> "Text too large"
                }
            } catch (e: Exception) {
                Log.e(TAG, "Could not store the capture: ${e.javaClass.simpleName}")
                "Could not save"
            }
            runOnUiThread { finishWith(message) }
        }.start()
    }

    private fun finishWith(message: String) {
        Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
        finish()
    }

    private companion object {
        const val TAG = "SEBRAIN_INGEST"
        const val SOURCE = "share"

        /** Provisional until the default mode is decided. */
        val DEFAULT_MODE = InboxMode.STRICT

        // One instance per process, so the in-memory digests survive between shares.
        @Volatile
        private var shared: CaptureIngestor? = null

        fun ingestor(activity: Activity): CaptureIngestor = shared ?: synchronized(this) {
            shared ?: InboxKey(DEFAULT_MODE).let { key ->
                CaptureIngestor(File(activity.noBackupFilesDir, "inbox"), key.keyId, key::publicKey)
            }.also { shared = it }
        }
    }
}
