package com.sebrain.app

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager
import android.widget.TextView

/** Minimal status screen: counts only. The real UI comes in a later phase. */
class MainActivity : Activity() {
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        status = TextView(this).apply {
            setPadding(48, 160, 48, 48)
            textSize = 18f
        }
        setContentView(status)
    }

    override fun onResume() {
        super.onResume()
        status.text = "Importing…"
        (application as SeBrainApp).requestImport { result ->
            runOnUiThread {
                status.text = buildString {
                    appendLine("Captures in the vault: ${result.captures ?: "unavailable"}")
                    appendLine("Waiting in the inbox: ${if (result.pending < 0) "unknown" else result.pending}")
                    result.problem?.let { appendLine("Last import stopped: $it") }
                }
            }
        }
    }
}
