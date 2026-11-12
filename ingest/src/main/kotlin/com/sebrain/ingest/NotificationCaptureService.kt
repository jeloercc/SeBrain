package com.sebrain.ingest

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Captures text from explicitly allowed notification packages.
 *
 * The policy starts deny-all and is held in memory until a future settings flow
 * persists a user-approved policy through the encrypted vault.
 */
class NotificationCaptureService : NotificationListenerService() {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    override fun onNotificationPosted(statusBarNotification: StatusBarNotification) {
        val packageName = statusBarNotification.packageName
        val policy = policy ?: run {
            policy = runCatching { IntegrationPolicyStore(applicationContext).load() }
                .getOrElse { IntegrationPolicy.denyAll() }
            policy!!
        }
        if (!policy.allows(packageName)) return

        val extras = statusBarNotification.notification.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val body = notificationBody(extras)
        val text = listOf(title, body).filter(String::isNotEmpty).joinToString("\n").trim()
        if (text.isEmpty()) return

        executor.execute {
            ShareActivity.ingestor(applicationContext).ingest(
                Capture(
                    text = text,
                    source = "notification:$packageName",
                    subject = title.takeIf(String::isNotEmpty),
                ),
            )
        }
    }

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }

    private fun notificationBody(extras: android.os.Bundle): String {
        val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?.mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }
            .orEmpty()
        if (lines.isNotEmpty()) return lines.joinToString("\n")
        return listOf(
            Notification.EXTRA_BIG_TEXT,
            Notification.EXTRA_TEXT,
        ).firstNotNullOfOrNull { key ->
            extras.getCharSequence(key)?.toString()?.trim()?.takeIf(String::isNotEmpty)
        }.orEmpty()
    }

    companion object {
        @Volatile
        private var policy: IntegrationPolicy? = null

        /**
         * Replaces the in-memory policy. An empty set disables every notification source.
         * Persistence and the user-facing permission screen are intentionally separate work.
         */
        fun configurePolicy(packages: Iterable<String>) {
            policy = IntegrationPolicy.fromPackages(packages)
        }

        fun loadPolicy(context: android.content.Context) {
            policy = runCatching { IntegrationPolicyStore(context).load() }
                .getOrElse { IntegrationPolicy.denyAll() }
        }
    }
}
