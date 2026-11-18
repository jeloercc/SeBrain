package com.sebrain.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.sebrain.app.SeBrainApp
import com.sebrain.app.privacy.AppPrivacy
import com.sebrain.app.privacy.DexTrackerScanner
import com.sebrain.app.privacy.PrivacyAudit
import kotlin.concurrent.thread

/** The Privacy screen's audit of the other apps: their permissions, data use and trackers. */
class PrivacyAuditState(
    private val app: SeBrainApp,
    private val onMain: (() -> Unit) -> Unit,
) {
    var privacyApps by mutableStateOf(emptyList<AppPrivacy>())
        private set
    var usageAccess by mutableStateOf(false)
        private set

    /** "12 / 80" while the tracker scan runs, null otherwise. */
    var scanProgress by mutableStateOf<String?>(null)
        private set

    private val privacyAudit by lazy { PrivacyAudit(app) }
    @Volatile private var scanning = false

    fun refresh() {
        thread(name = "privacy-audit") {
            val access = privacyAudit.hasUsageAccess()
            val apps = runCatching { sortPrivacy(privacyAudit.listApps()) }.getOrElse { emptyList() }
            onMain {
                usageAccess = access
                if (!scanning) privacyApps = apps
            }
        }
    }

    /** Scans every listed app's APK for trackers, one at a time, off the main thread. */
    fun scanTrackers() {
        if (scanning) return
        val apps = privacyApps
        if (apps.isEmpty()) return
        scanning = true
        scanProgress = "0 / ${apps.size}"
        thread(name = "tracker-scan", priority = Thread.MIN_PRIORITY) {
            val scanner = DexTrackerScanner()
            try {
                apps.forEachIndexed { index, target ->
                    val trackers = runCatching { privacyAudit.scanTrackers(target, scanner) }.getOrElse { emptyList() }
                    onMain {
                        privacyApps = privacyApps.map {
                            if (it.packageName == target.packageName) it.copy(trackers = trackers) else it
                        }
                        scanProgress = "${index + 1} / ${apps.size}"
                    }
                }
            } finally {
                onMain {
                    scanning = false
                    scanProgress = null
                    privacyApps = sortPrivacy(privacyApps)
                }
            }
        }
    }

    fun openUsageAccessSettings() = app.startActivity(privacyAudit.usageAccessSettings())

    fun openAppSettings(packageName: String) = app.startActivity(privacyAudit.appSettings(packageName))

    /** Most exposed first: trackers, then sensitive permissions, then data use. */
    private fun sortPrivacy(apps: List<AppPrivacy>) = apps.sortedWith(
        compareByDescending<AppPrivacy> { it.trackers?.size ?: 0 }
            .thenByDescending { it.sensitive.size }
            .thenByDescending { it.bytes ?: 0L }
            .thenBy { it.label.lowercase() },
    )
}
