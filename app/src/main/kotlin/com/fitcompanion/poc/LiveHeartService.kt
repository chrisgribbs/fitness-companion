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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.fitcompanion.core.HeartZones
import com.fitcompanion.poc.data.LiveHeart

/** Keeps the live heart-rate connection and zone alerts running while the phone is locked during a workout. */
class LiveHeartService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            runCatching { getSystemService(NotificationManager::class.java).notify(ID, build(this@LiveHeartService)) }
            handler.postDelayed(this, 5000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        channel(this)
        val n = build(this)
        // Only claim the types we actually have permission for (Android 14+ checks this).
        fun has(p: String) = androidx.core.content.ContextCompat.checkSelfPermission(this, p) == android.content.pm.PackageManager.PERMISSION_GRANTED
        var types = 0
        if (LiveHeart.HEART_RATE_ENABLED && (Build.VERSION.SDK_INT < 31 || has(android.Manifest.permission.BLUETOOTH_CONNECT)))
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        if (LiveHeart.gpsOn && has(android.Manifest.permission.ACCESS_FINE_LOCATION)) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        try {
            if (Build.VERSION.SDK_INT >= 29 && types != 0) startForeground(ID, n, types) else startForeground(ID, n)
        } catch (e: Exception) { stopSelf(); return START_NOT_STICKY }
        handler.removeCallbacks(tick); handler.postDelayed(tick, 5000)
        return START_NOT_STICKY
    }

    override fun onDestroy() { handler.removeCallbacks(tick); super.onDestroy() }

    companion object {
        private const val CHANNEL = "live_hr"
        private const val ID = 51

        fun start(context: Context) = runCatching {
            val i = Intent(context, LiveHeartService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
        }
        fun stop(context: Context) = runCatching { context.stopService(Intent(context, LiveHeartService::class.java)) }

        private fun channel(context: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null)
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "Live heart rate", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shows your heart rate during a live workout."
                })
        }

        private fun build(context: Context): Notification {
            val bpm = LiveHeart.bpm
            val zone = bpm?.let { HeartZones.zoneOf(it, LiveHeart.zones) } ?: 0
            val mins = ((System.currentTimeMillis() - LiveHeart.sessionStart) / 60000).coerceAtLeast(0)
            val text = (if (bpm != null) "$bpm bpm" + (if (zone > 0) " · zone $zone" else "") + " · "
                        else if (LiveHeart.HEART_RATE_ENABLED) LiveHeart.statusText + " · " else "") + "Recording" +
                (if (LiveHeart.gpsPoints.size > 1) " · %.2f km".format(LiveHeart.gpsDistance / 1000) else "") +
                " · $mins min" + (LiveHeart.alertText?.let { " · $it" } ?: "")
            val open = PendingIntent.getActivity(context, 1,
                Intent(context, MainActivity::class.java).putExtra("open_live", true).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(context, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(context)
            return b.setSmallIcon(android.R.drawable.ic_menu_compass).setContentTitle("Live workout").setContentText(text)
                .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).build()
        }
    }
}
