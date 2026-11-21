package com.sebrain.app

import android.content.Context
import android.os.PowerManager
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.sebrain.app.profile.DeviceSpec
import com.sebrain.app.profile.ModelAdvisor
import com.sebrain.cognition.CognitionBudget
import com.sebrain.cognition.Extraction
import com.sebrain.core.crypto.VaultKeyException
import com.sebrain.core.vault.ExtractionStore
import com.sebrain.core.vault.VaultSessionClosingException
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Extracts people, dates, topics, tasks and a title from captures that came from outside, into
 * the vault's `extraction` table (docs/design-cognition-worker.md, with the decisions of
 * docs/proposal-cognition-worker-gaps.md, approved 2026-11-20).
 *
 * - Runs only while charging with the battery not low, and only while the vault can open
 *   (screen on, unlocked): the vault key is never unwrapped while locked. Otherwise it ends at
 *   once, with nothing done.
 * - Makes way for the user: it does nothing while the app is on screen, and opening the app
 *   stops the capture in progress, which stays pending.
 * - Each capture is one unit, committed alone, so a run can stop anywhere. [CognitionBudget]
 *   ends a run before it would pass 8 minutes or 25 captures; the rest go to a chained run.
 * - It logs counts and times only, never text.
 */
class CognitionWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    private val app = applicationContext as SeBrainApp

    /** How one capture ended. */
    private enum class Step { DONE, EMPTY, VAULT_CLOSED, YIELDED, NO_MODEL }

    private class Counts {
        var ok = 0
        var okRetry = 0
        var failed = 0
    }

    override fun doWork(): Result {
        if (!running.compareAndSet(false, true)) return Result.success()
        try {
            if (!app.uiActive) run()
        } finally {
            running.set(false)
        }
        return Result.success()
    }

    /** §2: a stop (timeout, charger out) cancels the capture in progress; it stays pending. */
    override fun onStopped() {
        app.chat.cancelStructured()
    }

    private fun run() {
        val start = System.nanoTime()
        val counts = Counts()
        val times = mutableListOf<Long>()
        var budget = CognitionBudget()
        var maxThermal = 0
        // Only a model this run loaded is released at the end: one the chat left loaded is its own.
        val loadedBefore = app.chatThread.submit<Boolean> { app.chat.loaded != null }.get()
        val ending: String
        while (true) {
            if (isStopped) {
                ending = "stopped reason=$stopReason"
                break
            }
            val stop = budget.stopBefore(elapsedMs(start))
            if (stop != null) {
                ending = stop.name
                break
            }
            val segmentStart = System.nanoTime()
            val step = app.chatThread.submit<Step> { extractOne(counts) }.get()
            if (step != Step.DONE) {
                ending = step.name
                break
            }
            val ms = elapsedMs(segmentStart)
            times += ms
            budget = budget.record(ms)
            maxThermal = maxOf(maxThermal, thermalStatus())
        }
        if (!loadedBefore) app.chatThread.submit { if (!app.uiActive) app.chat.close() }.get()
        val sorted = times.sorted()
        Log.i(
            TAG,
            "cognition run captures=${times.size} ok=${counts.ok} okRetry=${counts.okRetry} failed=${counts.failed} ending=$ending " +
                "mean=${if (sorted.isEmpty()) 0 else sorted.average().toLong()} ms p95=${sorted.getOrNull(((sorted.size - 1) * P95).toInt()) ?: 0} ms " +
                "maxThermal=$maxThermal total=${elapsedMs(start)} ms",
        )
        // More may be waiting after a run cut by its budget: the next run follows this one.
        if (ending in BUDGET_ENDINGS) chain(applicationContext)
    }

    /** One capture, on the chat thread. An engine error or the user's return leaves it pending. */
    private fun extractOne(counts: Counts): Step {
        if (app.uiActive) return Step.YIELDED
        val pending = vault { ExtractionStore(it).pending(1) }?.firstOrNull()
            ?: return if (vault { } == null) Step.VAULT_CLOSED else Step.EMPTY
        val model = ensureModel() ?: return Step.NO_MODEL
        val result = try {
            Extraction.extract(pending.text, Instant.parse(pending.capturedAt), zone(pending.timeZone)) { message ->
                val reply = app.chat.structured(Extraction.SYSTEM, message, Extraction.SCHEMA, Extraction.MAX_TOKENS, Extraction::inWhitespaceLoop)
                if (reply.cancelled) throw Yield()
                Extraction.Reply(reply.text, reply.cut)
            }
        } catch (e: Yield) {
            return Step.YIELDED
        } catch (e: Exception) {
            // §4: an engine error is not an extraction failure. The capture stays pending.
            Log.w(TAG, "cognition engine error ${e.javaClass.simpleName}")
            return Step.YIELDED
        }
        val saved = vault { db ->
            val store = ExtractionStore(db)
            when (val outcome = result.outcome) {
                is Extraction.Outcome.Valid -> {
                    val f = outcome.fields
                    store.save(pending.id, ExtractionStore.Extracted(f.people, f.dates, f.topics, f.tasks, f.title, model, Instant.now().toString()), result.attempts)
                    if (result.attempts == 1) counts.ok++ else counts.okRetry++
                }
                is Extraction.Outcome.Invalid -> {
                    store.fail(pending.id, outcome.failure.code, result.attempts)
                    counts.failed++
                }
            }
        }
        return if (saved == null) Step.VAULT_CLOSED else Step.DONE
    }

    /** Runs [block] with the vault, or returns null if it cannot open now (locked, screen off, closing). */
    private fun <T> vault(block: (net.zetetic.database.sqlcipher.SQLiteDatabase) -> T): T? = try {
        app.session.withDatabase(block)
    } catch (e: VaultKeyException) {
        null
    } catch (e: VaultSessionClosingException) {
        null
    }

    /** The loaded model, or the one recommended for this phone; its file name, or null if none loads. */
    private fun ensureModel(): String? {
        app.chat.loaded?.let { return it.fileName }
        for (model in ModelAdvisor.preferred(DeviceSpec.current(app), app.chat.languageModels())) {
            if (runCatching { app.chat.load(model) }.isSuccess) return model.fileName
        }
        return null
    }

    private fun zone(id: String): ZoneId = runCatching { ZoneId.of(id) }.getOrDefault(ZoneId.of("UTC"))

    private fun thermalStatus(): Int = app.getSystemService(PowerManager::class.java).currentThermalStatus

    private fun elapsedMs(since: Long) = (System.nanoTime() - since) / NANOS_PER_MS

    /** The user opened the app during an extraction. */
    private class Yield : RuntimeException()

    companion object {
        private const val TAG = "SEBRAIN_COGNITION"
        private const val PERIODIC = "cognition-periodic"
        private const val ONCE = "cognition"
        private const val PERIOD_HOURS = 1L
        private const val NANOS_PER_MS = 1_000_000L
        private const val P95 = 0.95
        private val BUDGET_ENDINGS = CognitionBudget.Stop.entries.map { it.name }.toSet()

        /** Two workers must not take the same capture: one run at a time in this process. */
        private val running = AtomicBoolean(false)

        private val constraints = Constraints.Builder().setRequiresCharging(true).setRequiresBatteryNotLow(true).build()

        /** An hourly run while charging. Kept if it is already scheduled. */
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<CognitionWorker>(PERIOD_HOURS, TimeUnit.HOURS).setConstraints(constraints).build(),
            )
        }

        /** A run soon, after new captures arrive. Kept if one is already waiting or running. */
        fun runSoon(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                ONCE, ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<CognitionWorker>().setConstraints(constraints).build(),
            )
        }

        /** The next run after this one ends: appended, never replacing a run in progress. */
        private fun chain(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                ONCE, ExistingWorkPolicy.APPEND_OR_REPLACE, OneTimeWorkRequestBuilder<CognitionWorker>().setConstraints(constraints).build(),
            )
        }
    }
}
