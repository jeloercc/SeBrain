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
 * Modos, vía `adb shell am start -n com.sebrain.diag/.MainActivity --es mode <modo>`:
 * - all (por defecto): Keystore y Gemini Nano en primer plano.
 * - bg: programa el Worker con retraso; enviar la app a segundo plano antes de que dispare.
 * - idle: no ejecuta nada; sirve para ver qué hace ML Kit por sí solo al arrancar el proceso.
 */
class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val text = TextView(this).apply {
            setPadding(32, 96, 32, 32)
            textSize = 12f
            setTextIsSelectable(true)
        }
        setContentView(ScrollView(this).apply { addView(text) })
        Diag.sink = { line -> runOnUiThread { text.append(line + "\n") } }

        when (intent.getStringExtra("mode") ?: "all") {
            "bg" -> scheduleBackgroundTest()
            "idle" -> Diag.info("modo idle: sin llamadas a Keystore ni a ML Kit")
            else -> scope.launch {
                Diag.info("=== INICIO ===")
                KeystoreDiag(applicationContext).run()
                NanoDiag().run()
                Diag.info("=== FIN ===")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Diag.info("lifecycle: onResume (app en primer plano)")
    }

    override fun onPause() {
        Diag.info("lifecycle: onPause (la app deja el primer plano)")
        super.onPause()
    }

    private fun scheduleBackgroundTest() {
        val request = OneTimeWorkRequestBuilder<BackgroundNanoWorker>()
            .setInitialDelay(BG_DELAY_SECONDS, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(this)
            .enqueueUniqueWork("bg_nano", ExistingWorkPolicy.REPLACE, request)
        Diag.info("Worker programado en $BG_DELAY_SECONDS s")
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
