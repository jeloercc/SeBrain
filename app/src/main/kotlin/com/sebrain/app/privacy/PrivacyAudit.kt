package com.sebrain.app.privacy

import android.Manifest
import android.app.AppOpsManager
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Process
import android.provider.Settings
import java.util.concurrent.ConcurrentHashMap

/** What SeBrain can see about one installed app. Nothing here is written to disk. */
data class AppPrivacy(
    val packageName: String,
    val label: String,
    val system: Boolean,
    val hasInternet: Boolean,
    /** Sensitive permissions the user has granted, as readable names. */
    val sensitive: List<String>,
    /** Wi-Fi plus mobile bytes over the last [AUDIT_DAYS] days; null without usage access. */
    val bytes: Long?,
    /** Trackers found in the APK; null until the app is scanned. */
    val trackers: List<Tracker>?,
    internal val apkPaths: List<String>,
    internal val cacheKey: String,
)

const val AUDIT_DAYS = 30

/**
 * Read-only privacy audit of the apps in the launcher. It uses only what Android exposes to a
 * normal app: package metadata, granted permissions, per-app network byte counts (with the
 * user-granted usage access) and the APK files, which any app can read. It cannot see the
 * content or destination of other apps' traffic.
 */
class PrivacyAudit(private val context: Context) {
    private val packageManager = context.packageManager

    fun hasUsageAccess(): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java)
        return ops.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        ) == AppOpsManager.MODE_ALLOWED
    }

    fun listApps(): List<AppPrivacy> {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val packages = packageManager.queryIntentActivities(launcher, PackageManager.ResolveInfoFlags.of(0))
            .map { it.activityInfo.packageName }
            .filter { it != context.packageName }
            .distinct()
        val traffic = if (hasUsageAccess()) trafficByUid() else null
        return packages.mapNotNull { name ->
            val info = runCatching {
                packageManager.getPackageInfo(
                    name,
                    PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()),
                )
            }.getOrNull() ?: return@mapNotNull null
            val appInfo = info.applicationInfo ?: return@mapNotNull null
            val key = "${info.packageName}:${info.longVersionCode}:${info.lastUpdateTime}"
            AppPrivacy(
                packageName = info.packageName,
                label = packageManager.getApplicationLabel(appInfo).toString(),
                system = appInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                hasInternet = Manifest.permission.INTERNET in info.requestedPermissions.orEmpty(),
                sensitive = grantedSensitive(info),
                bytes = traffic?.let { it[appInfo.uid] ?: 0L },
                trackers = TrackerCache.get(key),
                apkPaths = listOfNotNull(appInfo.sourceDir) + appInfo.splitSourceDirs.orEmpty(),
                cacheKey = key,
            )
        }
    }

    /** Scans one app's APK for trackers. Results are kept in memory for this process. */
    fun scanTrackers(app: AppPrivacy, scanner: DexTrackerScanner): List<Tracker> {
        TrackerCache.get(app.cacheKey)?.let { return it }
        val order = TrackerSignatures.all
        val found = scanner.scanApks(app.apkPaths).sortedBy { order.indexOf(it) }
        TrackerCache.put(app.cacheKey, found)
        return found
    }

    fun usageAccessSettings(): Intent =
        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun appSettings(packageName: String): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun grantedSensitive(info: PackageInfo): List<String> {
        val names = info.requestedPermissions ?: return emptyList()
        val flags = info.requestedPermissionsFlags ?: return emptyList()
        val granted = names.indices
            .filter { flags[it] and PackageInfo.REQUESTED_PERMISSION_GRANTED != 0 }
            .map { names[it] }
            .toSet()
        return SENSITIVE.filter { (_, permissions) -> permissions.any { it in granted } }.map { it.first }
    }

    @Suppress("DEPRECATION") // TYPE_WIFI and TYPE_MOBILE are still the arguments querySummary takes.
    private fun trafficByUid(): Map<Int, Long> {
        val stats = context.getSystemService(NetworkStatsManager::class.java)
        val end = System.currentTimeMillis()
        val start = end - AUDIT_DAYS * 24L * 60 * 60 * 1000
        val totals = HashMap<Int, Long>()
        for (type in intArrayOf(ConnectivityManager.TYPE_WIFI, ConnectivityManager.TYPE_MOBILE)) {
            runCatching {
                stats.querySummary(type, null, start, end).use { summary ->
                    val bucket = NetworkStats.Bucket()
                    while (summary.hasNextBucket()) {
                        summary.getNextBucket(bucket)
                        totals.merge(bucket.uid, bucket.rxBytes + bucket.txBytes, Long::plus)
                    }
                }
            }
        }
        return totals
    }

    private companion object {
        val SENSITIVE: List<Pair<String, List<String>>> = listOf(
            "Location (always)" to listOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
            "Location" to listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            "Microphone" to listOf(Manifest.permission.RECORD_AUDIO),
            "Camera" to listOf(Manifest.permission.CAMERA),
            "Contacts" to listOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS, Manifest.permission.GET_ACCOUNTS),
            "SMS" to listOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS, Manifest.permission.SEND_SMS),
            "Call log" to listOf(Manifest.permission.READ_CALL_LOG, Manifest.permission.WRITE_CALL_LOG),
            "Phone" to listOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_PHONE_NUMBERS, Manifest.permission.CALL_PHONE),
            "Calendar" to listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR),
            "Photos and media" to listOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO,
                Manifest.permission.READ_EXTERNAL_STORAGE,
            ),
            "Physical activity" to listOf(Manifest.permission.ACTIVITY_RECOGNITION),
            "Body sensors" to listOf(Manifest.permission.BODY_SENSORS),
            "Nearby devices" to listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.NEARBY_WIFI_DEVICES,
            ),
        )
    }
}

/** Scan results for the life of the process, keyed by package, version and install time. */
private object TrackerCache {
    private val results = ConcurrentHashMap<String, List<Tracker>>()
    fun get(key: String): List<Tracker>? = results[key]
    fun put(key: String, trackers: List<Tracker>) {
        results[key] = trackers
    }
}
