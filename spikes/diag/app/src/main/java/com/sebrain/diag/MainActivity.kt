package com.sebrain.diag

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager
import android.widget.ScrollView
import android.widget.TextView
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Modes, via `adb shell am start -n com.sebrain.diag/.MainActivity --es mode <mode>`:
 * - all (default): Keystore and Gemini Nano in the foreground.
 * - bg: schedules the Worker with a delay; send the app to the background before it fires.
 * - idle: runs nothing; shows what ML Kit does on its own when the process starts.
 */
class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Screen-on measurements without unlocking the phone by hand.
        if (intent.getBooleanExtra("show", false)) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }

        val text = TextView(this).apply {
            setPadding(32, 96, 32, 32)
            textSize = 12f
            setTextIsSelectable(true)
        }
        setContentView(ScrollView(this).apply { addView(text) })
        Diag.sink = { line -> runOnUiThread { text.append(line + "\n") } }

        when (intent.getStringExtra("mode") ?: "all") {
            "bg" -> scheduleBackgroundTest()
            "cognition" -> scheduleCognitionBench()
            "cognition-bg" -> scheduleBackgroundCognition()
            "schema" -> scope.launch { SchemaProbe(applicationContext).run() }
            "whitespace" -> scope.launch {
                WhitespaceProbe(applicationContext, intent.getStringExtra("set") ?: "whitespace").run()
            }
            "idle" -> Diag.info("idle mode: no calls to Keystore or ML Kit")
            else -> scope.launch {
                Diag.info("=== START ===")
                KeystoreDiag(applicationContext).run()
                NanoDiag().run()
                Diag.info("=== END ===")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Diag.info("lifecycle: onResume (app in the foreground)")
    }

    override fun onPause() {
        Diag.info("lifecycle: onPause (app leaves the foreground)")
        super.onPause()
    }

    private fun scheduleBackgroundTest() {
        val request = OneTimeWorkRequestBuilder<BackgroundNanoWorker>()
            .setInitialDelay(BG_DELAY_SECONDS, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(this)
            .enqueueUniqueWork("bg_nano", ExistingWorkPolicy.REPLACE, request)
        Diag.info("Worker scheduled in $BG_DELAY_SECONDS s")
    }

    /** Batch of 10 and then batch of 100, in series; each Worker loads and releases the engine. */
    private fun scheduleCognitionBench() {
        val sizes = (intent.getStringExtra("batches") ?: "10,100").split(",").map { it.trim().toInt() }
        val requests = sizes.map {
            OneTimeWorkRequestBuilder<CognitionBenchWorker>()
                .setInputData(CognitionBenchWorker.input(it))
                .build()
        }
        var chain = WorkManager.getInstance(this)
            .beginUniqueWork("cognition_bench", ExistingWorkPolicy.REPLACE, requests.first())
        requests.drop(1).forEach { chain = chain.then(it) }
        chain.enqueue()
        Diag.info("CognitionBenchWorker scheduled: batches $sizes")
    }

    /**
     * Real background Worker: no FGS, with a delay and requiresCharging.
     * After scheduling it, the app is closed and the screen is turned off from adb.
     */
    private fun scheduleBackgroundCognition() {
        val n = intent.getIntExtra("n", 10)
        val delay = intent.getIntExtra("delay", 90).toLong()
        val request = OneTimeWorkRequestBuilder<CognitionBenchWorker>()
            .setInputData(
                CognitionBenchWorker.input(
                    n, fg = false,
                    tag = intent.getStringExtra("tag"),
                    reuse = intent.getBooleanExtra("reuse", false),
                    detail = intent.getBooleanExtra("detail", false),
                ),
            )
            .setInitialDelay(delay, TimeUnit.SECONDS)
            .setConstraints(androidx.work.Constraints.Builder().setRequiresCharging(true).build())
            .build()
        WorkManager.getInstance(this)
            .enqueueUniqueWork("cognition_bg", ExistingWorkPolicy.REPLACE, request)
        Diag.info("Cognition bg scheduled: batch $n, delay $delay s, requiresCharging, no FGS")
        // Without `show`, the task is removed from recents so an unlock cannot reopen the activity.
        if (!intent.getBooleanExtra("show", false)) finishAndRemoveTask()
    }

    override fun onDestroy() {
        Diag.sink = null
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val BG_DELAY_SECONDS = 20L
    }
}
