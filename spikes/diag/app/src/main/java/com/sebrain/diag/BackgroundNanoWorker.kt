package com.sebrain.diag

import android.app.ActivityManager
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.Generation

/** Intenta inferencia desde un Worker. Registra la importancia del proceso como evidencia. */
class BackgroundNanoWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val state = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(state)
        val foreground =
            state.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        val context = "process importance=${state.importance} foreground=$foreground"
        Diag.info("Worker iniciado: $context")

        val model = Generation.getClient()
        try {
            val (response, ms) = timedMs { model.generateContent("Di hola.") }
            Diag.result(
                "Worker background generateContent",
                "SIN BLOQUEO",
                "%.0f ms".format(ms),
                "$context; respuesta de ${response.candidates[0].text.length} caracteres",
            )
        } catch (e: GenAiException) {
            Diag.error("Worker background generateContent", e)
            val blocked = e.errorCode == GenAiException.ErrorCode.BACKGROUND_USE_BLOCKED
            Diag.result(
                "Worker background generateContent",
                if (blocked) "PASS (bloqueado)" else "FAIL",
                errorName(e.errorCode),
                "$context; errorCode=${e.errorCode}; ${Diag.describe(e)}",
            )
        } catch (t: Throwable) {
            Diag.error("Worker background generateContent", t)
            Diag.result("Worker background generateContent", "FAIL", "-", "$context; ${Diag.describe(t)}")
        } finally {
            model.close()
        }
        return Result.success()
    }
}
