package com.fitcompanion.poc

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.ExerciseRoute
import androidx.health.connect.client.records.ExerciseRouteResult
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.fitcompanion.core.ElevationCalculator
import com.fitcompanion.core.Splits
import com.fitcompanion.core.TrackPoint
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.reflect.KClass

/**
 * Reads from Health Connect (on the phone) and reports what Google Health has actually written there.
 * Everything here is read-only and stays on the device.
 */
class HealthConnectProbe(private val context: Context) {

    companion object {
        const val PROVIDER_PACKAGE = "com.google.android.apps.healthdata"
        // Literal strings so this compiles regardless of which constants a client version exposes.
        const val PERM_ROUTES = "android.permission.health.READ_EXERCISE_ROUTES"
        const val PERM_HISTORY = "android.permission.health.READ_HEALTH_DATA_HISTORY"
        const val PERM_BACKGROUND = "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND"

        val RECORD_TYPES: List<KClass<out Record>> = listOf(
            ExerciseSessionRecord::class, DistanceRecord::class, SpeedRecord::class,
            ElevationGainedRecord::class, HeartRateRecord::class, StepsRecord::class,
            SleepSessionRecord::class, RestingHeartRateRecord::class, HeartRateVariabilityRmssdRecord::class,
        )

        val PERMISSIONS: Set<String> =
            RECORD_TYPES.map { HealthPermission.getReadPermission(it) }.toSet() +
                setOf(PERM_ROUTES, PERM_HISTORY, PERM_BACKGROUND)
    }

    private val src = Source.HEALTH_CONNECT

    /** SDK_AVAILABLE, or a reason it isn't. */
    fun availability(): Int = HealthConnectClient.getSdkStatus(context, PROVIDER_PACKAGE)

    private val client by lazy { HealthConnectClient.getOrCreate(context) }

    suspend fun grantedPermissions(): Set<String> = client.permissionController.getGrantedPermissions()

    /** The most recent run or walk with a route, used for the route-consent button. */
    var latestSessionId: String? = null
        private set

    suspend fun run(): List<ProbeResult> {
        val out = mutableListOf<ProbeResult>()
        val granted = grantedPermissions()
        fun has(p: String) = p in granted
        fun hasRead(t: KClass<out Record>) = has(HealthPermission.getReadPermission(t))

        val now = Instant.now()
        val zone = ZoneId.systemDefault()
        val last30 = TimeRangeFilter.between(now.minus(30, ChronoUnit.DAYS), now)

        // ---- Exercise sessions -------------------------------------------------------------
        if (!hasRead(ExerciseSessionRecord::class)) {
            out += ProbeResult(src, "Recent run + summary", ProbeStatus.SUPPORTED_NOT_VERIFIED, "Exercise permission not granted.")
        } else {
            val sessions = client.readRecords(
                ReadRecordsRequest(ExerciseSessionRecord::class, last30, ascendingOrder = false, pageSize = 100),
            ).records
            val runs = sessions.filter { it.exerciseType == ExerciseSessionRecord.EXERCISE_TYPE_RUNNING }
            val walks = sessions.filter { it.exerciseType == ExerciseSessionRecord.EXERCISE_TYPE_WALKING }
            val origins = sessions.map { it.metadata.dataOrigin.packageName }.distinct()
            val target = runs.firstOrNull() ?: walks.firstOrNull()

            out += ProbeResult(
                src, "Recent run + summary",
                if (target != null) ProbeStatus.AVAILABLE_TESTED else ProbeStatus.NOT_AVAILABLE,
                "Last 30 days: ${sessions.size} sessions (${runs.size} runs, ${walks.size} walks). " +
                    "Written by: ${origins.ifEmpty { listOf("none") }.joinToString()}." +
                    (target?.let { "\nTested: ${typeName(it.exerciseType)} on ${it.startTime.atZone(zone).toLocalDateTime()}, " +
                        "${Duration.between(it.startTime, it.endTime).toMinutes()} min, title='${it.title ?: ""}'" } ?: ""),
            )

            if (target != null) {
                latestSessionId = target.metadata.id
                out += probeSessionDetails(target, ::has, ::hasRead)
            }
        }

        // ---- Daily steps & distance ----------------------------------------------------------
        if (hasRead(StepsRecord::class) && hasRead(DistanceRecord::class)) {
            val startOfDay = now.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant()
            val agg = client.aggregate(
                AggregateRequest(setOf(StepsRecord.COUNT_TOTAL, DistanceRecord.DISTANCE_TOTAL), TimeRangeFilter.between(startOfDay, now)),
            )
            val steps = agg[StepsRecord.COUNT_TOTAL]
            val dist = agg[DistanceRecord.DISTANCE_TOTAL]
            out += ProbeResult(
                src, "Daily steps + walking distance",
                if (steps != null) ProbeStatus.AVAILABLE_TESTED else ProbeStatus.NOT_AVAILABLE,
                "Today so far: steps=${steps ?: "none"}, total distance=${dist?.let { "%.2f km".format(it.inKilometers) } ?: "none"} " +
                    "(from ${agg.dataOrigins.joinToString { it.packageName }.ifEmpty { "no app" }}). " +
                    "Note: this is all-day distance; walking-only distance must be summed from walk sessions.",
            )
        } else {
            out += ProbeResult(src, "Daily steps + walking distance", ProbeStatus.SUPPORTED_NOT_VERIFIED, "Steps/distance permission not granted.")
        }

        // ---- Sleep, RHR, HRV -----------------------------------------------------------------
        out += simpleLatest(SleepSessionRecord::class, "Sleep", hasRead(SleepSessionRecord::class), last30) { r ->
            val s = r as SleepSessionRecord
            "Latest: ${s.startTime.atZone(zone).toLocalDateTime()} → ${s.endTime.atZone(zone).toLocalTime()}, " +
                "${Duration.between(s.startTime, s.endTime).toMinutes()} min, ${s.stages.size} stage segments"
        }
        out += simpleLatest(RestingHeartRateRecord::class, "Resting heart rate", hasRead(RestingHeartRateRecord::class), last30) { r ->
            val x = r as RestingHeartRateRecord
            "Latest: ${x.beatsPerMinute} bpm on ${x.time.atZone(zone).toLocalDate()}"
        }
        out += simpleLatest(HeartRateVariabilityRmssdRecord::class, "HRV", hasRead(HeartRateVariabilityRmssdRecord::class), last30) { r ->
            val x = r as HeartRateVariabilityRmssdRecord
            "Latest RMSSD: %.1f ms at %s".format(x.heartRateVariabilityMillis, x.time.atZone(zone).toLocalDateTime())
        }

        // ---- History ---------------------------------------------------------------------------
        out += probeHistory(has(PERM_HISTORY), hasRead(StepsRecord::class), now, zone)

        // ---- Background ------------------------------------------------------------------------
        out += ProbeResult(
            src, "Background access",
            if (has(PERM_BACKGROUND)) ProbeStatus.SUPPORTED_NOT_VERIFIED else ProbeStatus.NOT_AVAILABLE,
            if (has(PERM_BACKGROUND)) "Background-read permission granted. An actual background sync job is built in stage 2; not exercised by this PoC."
            else "Background-read permission not granted (or not offered by your Health Connect version). The app would sync when opened instead.",
        )
        return out
    }

    private suspend fun probeSessionDetails(
        s: ExerciseSessionRecord,
        has: (String) -> Boolean,
        hasRead: (KClass<out Record>) -> Boolean,
    ): List<ProbeResult> {
        val out = mutableListOf<ProbeResult>()
        val window = TimeRangeFilter.between(s.startTime, s.endTime)
        val minutes = Duration.between(s.startTime, s.endTime).seconds / 60.0

        // Route: readRecord for a single session is where route data is returned.
        val single = client.readRecord(ExerciseSessionRecord::class, s.metadata.id).record
        var route: ExerciseRoute? = null
        val routeDetails = when (val rr = single.exerciseRouteResult) {
            is ExerciseRouteResult.Data -> { route = rr.exerciseRoute; "Route returned: ${rr.exerciseRoute.route.size} points." }
            is ExerciseRouteResult.ConsentRequired ->
                "A route exists but needs consent. Tap 'Allow route for latest session' (or choose 'Always allow' for routes in Health Connect)."
            is ExerciseRouteResult.NoData -> "No route stored for this session in Health Connect."
            else -> "Unknown route result."
        }
        out += ProbeResult(
            src, "GPS route coordinates",
            when (single.exerciseRouteResult) {
                is ExerciseRouteResult.Data -> ProbeStatus.AVAILABLE_TESTED
                is ExerciseRouteResult.ConsentRequired -> ProbeStatus.SUPPORTED_NOT_VERIFIED
                else -> ProbeStatus.NOT_AVAILABLE
            },
            routeDetails + if (!has(PERM_ROUTES)) " (Route permission not granted.)" else "",
        )
        val hcGain = if (hasRead(ElevationGainedRecord::class)) client.aggregate(
            AggregateRequest(setOf(ElevationGainedRecord.ELEVATION_GAINED_TOTAL), window),
        )[ElevationGainedRecord.ELEVATION_GAINED_TOTAL]?.inMeters else null
        lastSessionElevationGained = hcGain
        out += routeAnalysis(route, hcGain, fromConsent = false)

        // Distance and pace.
        val dist = if (hasRead(DistanceRecord::class)) client.aggregate(
            AggregateRequest(setOf(DistanceRecord.DISTANCE_TOTAL), window),
        )[DistanceRecord.DISTANCE_TOTAL] else null
        out += ProbeResult(
            src, "Distance, duration, pace",
            if (dist != null) ProbeStatus.AVAILABLE_TESTED else ProbeStatus.NOT_AVAILABLE,
            "Duration ${"%.1f".format(minutes)} min (elapsed); distance ${dist?.let { "%.2f km".format(it.inKilometers) } ?: "not found"}" +
                (dist?.takeIf { it.inMeters > 0 }?.let { "; average pace ${Splits.formatPace(minutes * 60 / it.inKilometers)} /km (elapsed)" } ?: "") +
                ". Pace is calculated by the app from distance ÷ time.",
        )

        // Heart rate during the session.
        if (hasRead(HeartRateRecord::class)) {
            val hr = client.readRecords(ReadRecordsRequest(HeartRateRecord::class, window)).records.flatMap { it.samples }
            out += ProbeResult(
                src, "Heart rate during activity",
                if (hr.isNotEmpty()) ProbeStatus.AVAILABLE_TESTED else ProbeStatus.NOT_AVAILABLE,
                if (hr.isEmpty()) "No heart-rate samples in the session window."
                else "${hr.size} samples; avg ${hr.map { it.beatsPerMinute }.average().toInt()} bpm, max ${hr.maxOf { it.beatsPerMinute }} bpm; " +
                    "median gap ${medianGapSeconds(hr.map { it.time })} s between samples.",
            )
        } else {
            out += ProbeResult(src, "Heart rate during activity", ProbeStatus.SUPPORTED_NOT_VERIFIED, "Heart-rate permission not granted.")
        }
        return out
    }

    /** Route analysis — reused when the user grants per-session route consent. */
    fun routeAnalysis(route: ExerciseRoute?, hcGainMeters: Double?, fromConsent: Boolean): ProbeResult {
        val gainText = hcGainMeters?.let { " Health Connect 'elevation gained' total for the session: ${"%.0f".format(it)} m (measured by source)." }
            ?: " No 'elevation gained' record for the session."
        if (route == null || route.route.isEmpty()) {
            return ProbeResult(src, "Elevation / altitude",
                if (hcGainMeters != null) ProbeStatus.AVAILABLE_TESTED else ProbeStatus.NOT_AVAILABLE,
                "No route points returned, so no per-point altitude or elevation profile yet.$gainText")
        }
        val pts = route.route.map {
            TrackPoint(time = it.time, latitude = it.latitude, longitude = it.longitude, altitudeMeters = it.altitude?.inMeters)
        }
        val withAlt = pts.count { it.altitudeMeters != null }
        val elev = ElevationCalculator.calculateGps(pts)
        val vAcc = route.route.mapNotNull { it.verticalAccuracy?.inMeters }
        return ProbeResult(
            src, "Elevation / altitude",
            if (withAlt > 0) ProbeStatus.AVAILABLE_TESTED else ProbeStatus.NOT_AVAILABLE,
            "$withAlt of ${pts.size} route points have altitude" +
                (if (vAcc.isNotEmpty()) "; median vertical accuracy ${"%.1f".format(vAcc.sorted()[vAcc.size / 2])} m" else "; no vertical accuracy given") +
                (elev.ascentMeters?.let { "; app-estimated ascent ${"%.0f".format(it)} m, descent ${"%.0f".format(elev.descentMeters)} m (${elev.method})" } ?: "") +
                "; median gap ${medianGapSeconds(pts.map { it.time })} s between points." + gainText +
                if (fromConsent) " [after route consent]" else "",
        )
    }

    /** Remembered so the route-consent result can be compared against it. */
    var lastSessionElevationGained: Double? = null
        private set

    private suspend fun simpleLatest(
        type: KClass<out Record>, item: String, permitted: Boolean, range: TimeRangeFilter, describe: (Record) -> String,
    ): ProbeResult {
        if (!permitted) return ProbeResult(src, item, ProbeStatus.SUPPORTED_NOT_VERIFIED, "Permission not granted.")
        val recs = client.readRecords(ReadRecordsRequest(type, range, ascendingOrder = false, pageSize = 30)).records
        if (recs.isEmpty()) return ProbeResult(src, item, ProbeStatus.NOT_AVAILABLE,
            "Permission granted but no records in the last 30 days. Google Health may not be writing this type to Health Connect.")
        val origins = recs.map { it.metadata.dataOrigin.packageName }.distinct().joinToString()
        return ProbeResult(src, item, ProbeStatus.AVAILABLE_TESTED, "${recs.size} records in 30 days (from $origins). ${describe(recs.first())}")
    }

    private suspend fun probeHistory(historyGranted: Boolean, stepsGranted: Boolean, now: Instant, zone: ZoneId): ProbeResult {
        if (!stepsGranted) return ProbeResult(src, "Historical data", ProbeStatus.SUPPORTED_NOT_VERIFIED, "Steps permission not granted.")
        val older = client.readRecords(
            ReadRecordsRequest(
                StepsRecord::class,
                TimeRangeFilter.between(now.minus(3650, ChronoUnit.DAYS), now.minus(31, ChronoUnit.DAYS)),
                ascendingOrder = true, pageSize = 1,
            ),
        ).records.firstOrNull()
        val text = buildString {
            append("History permission ${if (historyGranted) "granted" else "NOT granted"}. ")
            if (older != null) append("Oldest steps record visible: ${older.startTime.atZone(zone).toLocalDate()}. ")
            else append("No steps records older than 31 days visible. ")
            append("Without the history permission, Health Connect only returns data from 30 days before the app's first permission grant. ")
            append("It also only holds what Google Health has copied into it, which may not include your full Fitbit history.")
        }
        return ProbeResult(
            src, "Historical data",
            if (older != null) ProbeStatus.AVAILABLE_TESTED else ProbeStatus.NOT_AVAILABLE, text,
        )
    }

    private fun medianGapSeconds(times: List<Instant>): Long {
        if (times.size < 2) return 0
        val gaps = times.sorted().zipWithNext { a, b -> Duration.between(a, b).seconds }.sorted()
        return gaps[gaps.size / 2]
    }

    private fun typeName(t: Int) = when (t) {
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING -> "Run"
        ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> "Walk"
        else -> "Exercise type $t"
    }
}
