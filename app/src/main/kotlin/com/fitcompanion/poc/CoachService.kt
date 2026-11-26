package com.fitcompanion.poc

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * Keeps the app allowed to use the internet while the coach is writing an answer, even if you switch to
 * another app or lock the phone (many phones cut off background apps). Shows a small notification while it
 * runs and stops as soon as the answer is in.
 */
class CoachService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = notification(this, "Coach is working on your answer…", ongoing = true)
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID_WORKING, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(ID_WORKING, n)
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "coach"
        const val ID_WORKING = 41
        const val ID_DONE = 42

        fun start(context: Context) = runCatching {
            channel(context)
            val i = Intent(context, CoachService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
        }

        fun stop(context: Context) = runCatching { context.stopService(Intent(context, CoachService::class.java)) }

        /** "Your coach replied" when the answer lands while you're elsewhere. */
        fun notifyDone(context: Context, preview: String) = runCatching {
            channel(context)
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.notify(ID_DONE, notification(context, preview.ifBlank { "Your coach replied." }, ongoing = false, title = "Your coach replied"))
        }

        fun clearDone(context: Context) = runCatching { context.getSystemService(NotificationManager::class.java).cancel(ID_DONE) }

        private fun channel(context: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null)
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "Coach answers", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Shows while the AI coach is answering, and when the answer is ready."
                    setSound(null, null)
                })
        }

        private fun notification(context: Context, text: String, ongoing: Boolean, title: String = "Fitness Companion"): Notification {
            val open = PendingIntent.getActivity(context, 0,
                Intent(context, MainActivity::class.java).putExtra("open_tab", 2).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(context, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(context)
            return b.setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle(title).setContentText(text.take(140))
                .setStyle(Notification.BigTextStyle().bigText(text.take(400)))
                .setContentIntent(open).setOngoing(ongoing).setAutoCancel(!ongoing)
                .setOnlyAlertOnce(true).build()
        }
    }
}
