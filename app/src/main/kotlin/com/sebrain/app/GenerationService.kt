package com.sebrain.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log

/**
 * Keeps the process running while the assistant writes one reply, so the reply continues when
 * the user switches to another app. Android freezes a process with nothing on screen; a
 * foreground service prevents that.
 *
 * It is not a resident service: it starts with a reply and stops when the reply ends. It uses
 * the `shortService` type, which Android limits to about three minutes; a reply that runs
 * longer loses the protection and may be frozen until the app is opened again. When the screen
 * turns off the reply is stopped, because the vault closes then and nothing may be processed
 * while the phone is locked. Notifications never contain vault content.
 */
class GenerationService : Service() {
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            onScreenOff?.invoke()
        }
    }

    override fun onCreate() {
        super.onCreate()
        registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = Notification.Builder(this, channel(this, CHANNEL_WORKING, "Answering", NotificationManager.IMPORTANCE_LOW))
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("SeBrain is answering")
            .setContentText("The reply continues while you use other apps.")
            .setContentIntent(openApp(this))
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_WORKING, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE)
        return START_NOT_STICKY
    }

    /** Android requires a short service to stop when its time is up. */
    override fun onTimeout(startId: Int) {
        Log.w(TAG, "the reply ran longer than a short service allows; it is no longer protected")
        stopSelf()
    }

    override fun onDestroy() {
        unregisterReceiver(screenOff)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "SEBRAIN_APP"
        private const val CHANNEL_WORKING = "answering"
        private const val CHANNEL_DONE = "answer_ready"
        private const val NOTIFICATION_WORKING = 1
        private const val NOTIFICATION_DONE = 2

        /** Called on the main thread when the screen turns off during a reply. */
        @Volatile
        var onScreenOff: (() -> Unit)? = null

        /** Call while the app is on screen: Android only lets a visible app start this service. */
        fun start(context: Context) {
            runCatching { context.startForegroundService(Intent(context, GenerationService::class.java)) }
                .onFailure { Log.w(TAG, "the reply is not protected in the background: ${it.javaClass.simpleName}") }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, GenerationService::class.java))
        }

        /** Tells the user a reply finished while the app was not on screen. Shown only if notifications are allowed. */
        fun notifyDone(context: Context) {
            val notification = Notification.Builder(context, channel(context, CHANNEL_DONE, "Answer ready", NotificationManager.IMPORTANCE_DEFAULT))
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle("SeBrain")
                .setContentText("Your answer is ready.")
                .setContentIntent(openApp(context))
                .setAutoCancel(true)
                .build()
            runCatching { context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_DONE, notification) }
        }

        fun clearDone(context: Context) {
            context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_DONE)
        }

        private fun channel(context: Context, id: String, name: String, importance: Int): String {
            context.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(id, name, importance))
            return id
        }

        private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
