package com.sebrain.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.util.Log

/**
 * Keeps a model download going while the phone is locked. Android pauses the network and the CPU
 * of an app with nothing on screen; a foreground service with a partial wake lock keeps both.
 *
 * It does not download anything itself: ModelHubState downloads and starts and stops this service
 * around it. It holds no vault data, and its notification shows only the progress. A model file is
 * public and stored unencrypted, so the vault stays closed while the phone is locked.
 */
class ModelDownloadService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION, notification(this, 0), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SeBrain:model-download")
                .apply { acquire(MAX_HOLD_MS) }
        }
        return START_NOT_STICKY
    }

    /** Android ends a data sync service after its daily allowance; the download then stops and can resume later. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "model download service reached its time limit")
        stopSelf()
    }

    override fun onDestroy() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "SEBRAIN_APP"
        private const val CHANNEL = "model_download"
        private const val NOTIFICATION = 3

        /** Released by then even if a stop is missed; far longer than a 3 GB download takes. */
        private const val MAX_HOLD_MS = 3L * 60 * 60 * 1000

        /** Call while the app is on screen, when the download starts: Android allows it only then. */
        fun start(context: Context) {
            runCatching { context.startForegroundService(Intent(context, ModelDownloadService::class.java)) }
                .onFailure { Log.w(TAG, "the download is not protected while locked: ${it.javaClass.simpleName}") }
        }

        /** Updates the notification; only a number, never content. */
        fun progress(context: Context, percent: Int) {
            runCatching { context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(context, percent)) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ModelDownloadService::class.java))
        }

        private fun notification(context: Context, percent: Int): Notification {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL) == null) {
                manager.createNotificationChannel(NotificationChannel(CHANNEL, "Model downloads", NotificationManager.IMPORTANCE_LOW))
            }
            val open = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE,
            )
            return Notification.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("SeBrain is downloading a model")
                .setContentText("$percent% · continues while the phone is locked")
                .setProgress(100, percent, false)
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build()
        }
    }
}
