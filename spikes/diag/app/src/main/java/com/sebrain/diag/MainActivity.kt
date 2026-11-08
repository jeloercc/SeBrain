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
        // Mediciones con pantalla encendida sin desbloquear el teléfono a mano.
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
            "whitespace" -> scope.launch { WhitespaceProbe(applicationContext).run() }
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

    /** Lote de 10 y luego lote de 100, en serie; cada Worker carga y libera el motor. */
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
        Diag.info("CognitionBenchWorker programado: lotes $sizes")
    }

    /**
     * Worker real en segundo plano: sin FGS, con retraso y requiresCharging.
     * Tras programarlo se cierra la app y se apaga la pantalla desde adb.
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
        Diag.info("Cognition bg programado: lote $n, retraso $delay s, requiresCharging, sin FGS")
        // Sin `show`, la tarea se retira de recientes para que un desbloqueo no reabra la actividad.
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
