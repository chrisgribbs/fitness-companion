package com.fitcompanion.poc.data

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.fitcompanion.poc.MainActivity
import com.google.android.gms.fitness.FitnessLocal
import com.google.android.gms.fitness.data.LocalDataType
import com.google.android.gms.fitness.request.LocalDataReadRequest
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity
import kotlinx.coroutines.suspendCancellableCoroutine
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Notices when you start walking or running (Android's activity recognition, which uses the motion sensors, not
 * GPS) and asks, with a notification, whether to track it: "Track" or "Not now". Off until you turn it on.
 * Nothing leaves the phone. Android's detector usually needs about a minute of walking before it's sure, which
 * is the "walking for a minute" you asked for.
 */
object WalkDetector {
    const val CHANNEL = "walk_detect"
    private const val NOTIF_ID = 4711
    const val ACTION_TRANSITION = "com.fitcompanion.poc.WALK_TRANSITION"
    const val ACTION_NOT_NOW = "com.fitcompanion.poc.WALK_NOT_NOW"
    const val EXTRA_START = "auto_track_start"
    /** After "Not now", stay quiet this long. */
    private const val SNOOZE_MS = 45 * 60_000L
    /** Never ask again within this long of the last question. */
    private const val MIN_GAP_MS = 20 * 60_000L

    private fun prefs(c: Context) = c.getSharedPreferences("walk_detect", Context.MODE_PRIVATE)
    fun enabled(c: Context) = prefs(c).getBoolean("on", false)
    fun hasPermission(c: Context) = Build.VERSION.SDK_INT < 29 ||
        ContextCompat.checkSelfPermission(c, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

    fun setEnabled(c: Context, on: Boolean) {
        prefs(c).edit().putBoolean("on", on).apply()
        if (on) register(c) else unregister(c)
    }

    private fun pending(c: Context) = PendingIntent.getBroadcast(c, 41,
        Intent(c, WalkDetectReceiver::class.java).setAction(ACTION_TRANSITION),
        PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0))

    @SuppressLint("MissingPermission")
    fun register(c: Context) {
        if (!enabled(c) || !hasPermission(c)) return
        val transitions = listOf(DetectedActivity.WALKING, DetectedActivity.RUNNING).flatMap { type ->
            listOf(ActivityTransition.ACTIVITY_TRANSITION_ENTER, ActivityTransition.ACTIVITY_TRANSITION_EXIT).map { t ->
                ActivityTransition.Builder().setActivityType(type).setActivityTransition(t).build()
            }
        }
        runCatching { ActivityRecognition.getClient(c).requestActivityTransitionUpdates(ActivityTransitionRequest(transitions), pending(c)) }
        StepHistory.subscribe(c)
    }

    @SuppressLint("MissingPermission")
    fun unregister(c: Context) {
        runCatching { ActivityRecognition.getClient(c).removeActivityTransitionUpdates(pending(c)) }
        cancel(c)
    }

    fun cancel(c: Context) = c.getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
    fun snooze(c: Context) { prefs(c).edit().putLong("snoozeUntil", System.currentTimeMillis() + SNOOZE_MS).apply(); cancel(c) }

    fun onTransition(c: Context, activity: Int, transition: Int, eventElapsedNanos: Long) {
        if (transition == ActivityTransition.ACTIVITY_TRANSITION_EXIT) { cancel(c); return } // stopped before answering
        val now = System.currentTimeMillis()
        val p = prefs(c)
        if (LiveHeart.recording) return
        if (now < p.getLong("snoozeUntil", 0) || now - p.getLong("lastAsked", 0) < MIN_GAP_MS) return
        // When you actually started (the detector reports it a little after the fact).
        val started = now - (SystemClock.elapsedRealtimeNanos() - eventElapsedNanos) / 1_000_000
        p.edit().putLong("lastAsked", now).apply()
        notify(c, started, activity == DetectedActivity.RUNNING)
    }

    private fun notify(c: Context, started: Long, running: Boolean) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Walk detection", NotificationManager.IMPORTANCE_HIGH)
            .apply { description = "Asks if you'd like to track a walk or run it noticed" })
        val track = PendingIntent.getActivity(c, 42,
            Intent(c, MainActivity::class.java).putExtra(EXTRA_START, started).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notNow = PendingIntent.getBroadcast(c, 43, Intent(c, WalkDetectReceiver::class.java).setAction(ACTION_NOT_NOW),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val what = if (running) "run" else "walk"
        val since = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(started))
        val n = NotificationCompat.Builder(c, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_directions)
            .setContentTitle("Out for a $what?")
            .setContentText("You've been on the move since $since. Track it with GPS? Steps since then are counted too.")
            .setStyle(NotificationCompat.BigTextStyle().bigText("You've been on the move since $since. Track it with GPS? Steps since then are counted too."))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setContentIntent(track)
            .addAction(0, "TRACK", track)
            .addAction(0, "NOT NOW", notNow)
            .setAutoCancel(true)
            .setTimeoutAfter(10 * 60_000L)
            .build()
        runCatching { nm.notify(NOTIF_ID, n) }
    }
}

/** Transition results and the "Not now" button. Not reachable by other apps. */
class WalkDetectReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        LiveHeart.init(c)
        when (intent.action) {
            WalkDetector.ACTION_NOT_NOW -> WalkDetector.snooze(c)
            WalkDetector.ACTION_TRANSITION -> if (ActivityTransitionResult.hasResult(intent)) {
                ActivityTransitionResult.extractResult(intent)?.transitionEvents?.lastOrNull()?.let { e ->
                    WalkDetector.onTransition(c, e.activityType, e.transitionType, e.elapsedRealTimeNanos)
                }
            }
        }
    }
}

/** Android forgets activity-recognition requests after a restart or update; this puts it back. */
class WalkBootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) WalkDetector.register(c)
    }
}

/**
 * Steps from Google's on-phone step history (the Recording API: kept on the phone, no Google account or upload).
 * Lets a tracked walk include the steps taken before you tapped Track. Needs recent Google Play services;
 * returns null if it isn't available.
 */
object StepHistory {
    fun subscribe(c: Context) {
        runCatching { FitnessLocal.getLocalRecordingClient(c).subscribe(LocalDataType.TYPE_STEP_COUNT_DELTA) }
    }

    suspend fun stepsBetween(c: Context, startMs: Long, endMs: Long): Int? = runCatching {
        if (endMs - startMs < 1000) return 0
        val req = LocalDataReadRequest.Builder()
            .aggregate(LocalDataType.TYPE_STEP_COUNT_DELTA)
            .bucketByTime(1, TimeUnit.DAYS)
            .setTimeRange(startMs / 1000, endMs / 1000, TimeUnit.SECONDS)
            .build()
        suspendCancellableCoroutine<Int?> { cont ->
            FitnessLocal.getLocalRecordingClient(c).readData(req)
                .addOnSuccessListener { r ->
                    var total = 0
                    r.buckets.flatMap { it.dataSets }.forEach { ds -> ds.dataPoints.forEach { dp -> dp.dataType.fields.forEach { f -> total += dp.getValue(f).asInt() } } }
                    if (cont.isActive) cont.resume(total)
                }
                .addOnFailureListener { if (cont.isActive) cont.resume(null) }
        }
    }.getOrNull()
}
