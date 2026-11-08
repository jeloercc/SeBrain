package com.sebrain.diag

import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.Generation
import kotlinx.coroutines.delay

private const val PROMPT = "Responde en una sola frase: ¿para qué sirve una llave?"

fun statusName(status: Int) = when (status) {
    FeatureStatus.UNAVAILABLE -> "UNAVAILABLE"
    FeatureStatus.DOWNLOADABLE -> "DOWNLOADABLE"
    FeatureStatus.DOWNLOADING -> "DOWNLOADING"
    FeatureStatus.AVAILABLE -> "AVAILABLE"
    else -> "UNKNOWN($status)"
}

fun errorName(code: Int) = when (code) {
    GenAiException.ErrorCode.BACKGROUND_USE_BLOCKED -> "BACKGROUND_USE_BLOCKED"
    GenAiException.ErrorCode.BUSY -> "BUSY"
    GenAiException.ErrorCode.NOT_AVAILABLE -> "NOT_AVAILABLE"
    GenAiException.ErrorCode.NEEDS_SYSTEM_UPDATE -> "NEEDS_SYSTEM_UPDATE"
    GenAiException.ErrorCode.NOT_ENOUGH_DISK_SPACE -> "NOT_ENOUGH_DISK_SPACE"
    GenAiException.ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED -> "PER_APP_BATTERY_USE_QUOTA_EXCEEDED"
    else -> "code=$code"
}

class NanoDiag {
    private val model = Generation.getClient()

    suspend fun run() {
        try {
            val initial = model.checkStatus()
            Diag.result("Gemini Nano checkStatus", "INFO", statusName(initial), "initial status")

            when (initial) {
                FeatureStatus.UNAVAILABLE -> return
                FeatureStatus.DOWNLOADABLE, FeatureStatus.DOWNLOADING -> if (!awaitDownload(initial)) return
            }

            Diag.result("getBaseModelName", "INFO", model.getBaseModelName())
            streamPrompt("cold")
            streamPrompt("second call")
        } catch (t: Throwable) {
            Diag.error("Gemini Nano", t)
            val code = (t as? GenAiException)?.let { " ${errorName(it.errorCode)}" } ?: ""
            Diag.result("Gemini Nano", "FAIL", "-", "$code ${Diag.describe(t)}".trim())
        } finally {
            model.close()
        }
    }

    private suspend fun awaitDownload(initial: Int): Boolean {
        val (ok, ms) = timedMs {
            if (initial == FeatureStatus.DOWNLOADABLE) collectDownload() else pollUntilReady()
        }
        val note = "AICore download triggered by the spike"
        Diag.result("Gemini Nano download", if (ok) "PASS" else "FAIL", "%.0f s".format(ms / 1000), note)
        return ok
    }

    private suspend fun collectDownload(): Boolean {
        var completed = false
        var lastLog = 0L
        model.download().collect { status ->
            when (status) {
                is DownloadStatus.DownloadStarted -> Diag.info("download started: $status")
                is DownloadStatus.DownloadProgress -> {
                    val now = System.currentTimeMillis()
                    if (now - lastLog > 10_000) {
                        lastLog = now
                        Diag.info("download: ${status.totalBytesDownloaded} bytes")
                    }
                }
                DownloadStatus.DownloadCompleted -> {
                    completed = true
                    Diag.info("download complete")
                }
                is DownloadStatus.DownloadFailed -> Diag.error("download", status.e)
            }
        }
        return completed
    }

    private suspend fun pollUntilReady(): Boolean {
        while (true) {
            val status = model.checkStatus()
            Diag.info("waiting for download in progress: ${statusName(status)}")
            when (status) {
                FeatureStatus.AVAILABLE -> return true
                FeatureStatus.UNAVAILABLE -> return false
            }
            delay(15_000)
        }
    }

    private suspend fun streamPrompt(label: String) {
        val start = System.nanoTime()
        var firstMs = -1.0
        var chunks = 0
        val text = StringBuilder()
        model.generateContentStream(PROMPT).collect { chunk ->
            if (firstMs < 0) firstMs = (System.nanoTime() - start) / 1_000_000.0
            chunks++
            text.append(chunk.candidates[0].text)
        }
        val totalMs = (System.nanoTime() - start) / 1_000_000.0
        Diag.result("Prompt time to first token ($label)", "PASS", "%.0f ms".format(firstMs), "first streaming chunk")
        Diag.result("Prompt total time ($label)", "PASS", "%.0f ms".format(totalMs), "$chunks chunks, ${text.length} characters")
        Diag.info("response ($label): ${text.toString().replace('\n', ' ')}")
    }
}
