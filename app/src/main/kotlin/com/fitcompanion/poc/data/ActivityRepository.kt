package com.fitcompanion.poc.data

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.fitcompanion.core.ActivityKind
import com.fitcompanion.core.ActivityRecord
import com.fitcompanion.core.DailyMetric
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId

/**
 * Reads runs and walks (with distance and climb) and daily wellness numbers from Health Connect.
 * Climb comes from Google Health's own "elevation gained" records for each session (MEASURED);
 * where none exists it is left missing, never guessed.
 */
class ActivityRepository(private val context: Context) {
    companion object {
        /** When several apps log the same night, use these first (Fitbit / Google Health), as the Fitbit app does. */
        val PREFERRED_SLEEP_SOURCES = listOf("com.fitbit.FitbitMobile", "com.google.android.apps.fitness", "com.google.android.apps.healthdata")
    }

    private val client by lazy { HealthConnectClient.getOrCreate(context) }

    fun available() = HealthConnectClient.getSdkStatus(context, "com.google.android.apps.healthdata") == HealthConnectClient.SDK_AVAILABLE

    suspend fun activitiesSince(from: LocalDate, zone: ZoneId = ZoneId.systemDefault()): List<ActivityRecord> {
        val granted = client.permissionController.getGrantedPermissions()
        if (HealthPermission.getReadPermission(ExerciseSessionRecord::class) !in granted) return emptyList()
        val canDistance = HealthPermission.getReadPermission(DistanceRecord::class) in granted
        val canElevation = HealthPermission.getReadPermission(ElevationGainedRecord::class) in granted

        val range = TimeRangeFilter.between(from.atStartOfDay(zone).toInstant(), java.time.Instant.now())
        val sessions = mutableListOf<ExerciseSessionRecord>()
        var token: String? = null
        do {
            val resp = client.readRecords(ReadRecordsRequest(ExerciseSessionRecord::class, range, pageSize = 500, pageToken = token))
            sessions += resp.records
            token = resp.pageToken
        } while (token != null)

        // Every session is kept. Types other than run/walk become OTHER: they show in the
        // activity list (so nothing silently disappears) but don't count towards challenges.
        return sessions.map { s ->
            val kind = kindOf(s.exerciseType)
            val window = TimeRangeFilter.between(s.startTime, s.endTime)
            val metrics = buildSet {
                if (canDistance) add(DistanceRecord.DISTANCE_TOTAL)
                if (canElevation) add(ElevationGainedRecord.ELEVATION_GAINED_TOTAL)
            }
            val agg = if (metrics.isEmpty()) null else client.aggregate(AggregateRequest(metrics, window))
            ActivityRecord(
                id = s.metadata.id, kind = kind, start = s.startTime, end = s.endTime,
                distanceMeters = agg?.get(DistanceRecord.DISTANCE_TOTAL)?.inMeters,
                ascentMeters = agg?.get(ElevationGainedRecord.ELEVATION_GAINED_TOTAL)?.inMeters,
            )
        }
    }

    /** One row per Health Connect exercise session, with everything needed to see why it does or doesn't count. */
    data class SessionInspection(
        val start: java.time.Instant,
        val end: java.time.Instant,
        val typeCode: Int,
        val typeName: String,
        val countedAs: ActivityKind,
        val title: String?,
        val sourceApp: String,
        val distanceMeters: Double?,
        val elevationMeters: Double?,
        val hasRoute: String,
    )

    data class Inspection(
        val permissions: Map<String, Boolean>,
        val sessions: List<SessionInspection>,
        val stepsTodaySources: List<String>,
        val distanceTodaySources: List<String>,
        val stepsToday: Long?,
    )

    suspend fun inspect(days: Long = 30, zone: ZoneId = ZoneId.systemDefault()): Inspection {
        val granted = client.permissionController.getGrantedPermissions()
        fun can(c: kotlin.reflect.KClass<out androidx.health.connect.client.records.Record>) = HealthPermission.getReadPermission(c) in granted
        val perms = linkedMapOf(
            "Exercise" to can(ExerciseSessionRecord::class),
            "Distance" to can(DistanceRecord::class),
            "Elevation gained" to can(ElevationGainedRecord::class),
            "Steps" to can(StepsRecord::class),
            "Routes" to ("android.permission.health.READ_EXERCISE_ROUTES" in granted),
            "History (older than 30 days)" to ("android.permission.health.READ_HEALTH_DATA_HISTORY" in granted),
        )
        val now = java.time.Instant.now()
        val range = TimeRangeFilter.between(now.minus(java.time.Duration.ofDays(days)), now)
        val sessions = if (!can(ExerciseSessionRecord::class)) emptyList() else {
            val all = mutableListOf<ExerciseSessionRecord>()
            var token: String? = null
            do {
                val resp = client.readRecords(ReadRecordsRequest(ExerciseSessionRecord::class, range, ascendingOrder = false, pageSize = 500, pageToken = token))
                all += resp.records; token = resp.pageToken
            } while (token != null)
            all.map { s ->
                val window = TimeRangeFilter.between(s.startTime, s.endTime)
                val metrics = buildSet {
                    if (can(DistanceRecord::class)) add(DistanceRecord.DISTANCE_TOTAL)
                    if (can(ElevationGainedRecord::class)) add(ElevationGainedRecord.ELEVATION_GAINED_TOTAL)
                }
                val agg = if (metrics.isEmpty()) null else client.aggregate(AggregateRequest(metrics, window))
                SessionInspection(
                    start = s.startTime, end = s.endTime, typeCode = s.exerciseType, typeName = typeName(s.exerciseType),
                    countedAs = kindOf(s.exerciseType), title = s.title, sourceApp = s.metadata.dataOrigin.packageName,
                    distanceMeters = agg?.get(DistanceRecord.DISTANCE_TOTAL)?.inMeters,
                    elevationMeters = agg?.get(ElevationGainedRecord.ELEVATION_GAINED_TOTAL)?.inMeters,
                    hasRoute = when (s.exerciseRouteResult) {
                        is androidx.health.connect.client.records.ExerciseRouteResult.Data -> "yes"
                        is androidx.health.connect.client.records.ExerciseRouteResult.ConsentRequired -> "yes (needs consent)"
                        else -> "no"
                    },
                )
            }
        }
        val dayStart = LocalDate.now(zone).atStartOfDay(zone).toInstant()
        val todayRange = TimeRangeFilter.between(dayStart, now)
        val stepsAgg = if (can(StepsRecord::class)) client.aggregate(AggregateRequest(setOf(StepsRecord.COUNT_TOTAL), todayRange)) else null
        val distAgg = if (can(DistanceRecord::class)) client.aggregate(AggregateRequest(setOf(DistanceRecord.DISTANCE_TOTAL), todayRange)) else null
        return Inspection(
            permissions = perms,
            sessions = sessions,
            stepsTodaySources = stepsAgg?.dataOrigins?.map { it.packageName }.orEmpty(),
            distanceTodaySources = distAgg?.dataOrigins?.map { it.packageName }.orEmpty(),
            stepsToday = stepsAgg?.get(StepsRecord.COUNT_TOTAL),
        )
    }

    /** How recent the newest record of one data type is, and which app wrote it. */
    data class Freshness(
        val label: String,
        val permitted: Boolean,
        val newest: java.time.Instant?,
        val sourcePackage: String?,
    )

    /** Newest record per data type within the last 30 days (one tiny read each). */
    suspend fun freshness(): List<Freshness> {
        val granted = client.permissionController.getGrantedPermissions()
        val now = java.time.Instant.now()
        val range = TimeRangeFilter.between(now.minus(java.time.Duration.ofDays(30)), now)
        suspend fun <T : androidx.health.connect.client.records.Record> one(
            label: String, type: kotlin.reflect.KClass<T>, time: (T) -> java.time.Instant,
        ): Freshness {
            val ok = HealthPermission.getReadPermission(type) in granted
            if (!ok) return Freshness(label, false, null, null)
            val r = runCatching { client.readRecords(ReadRecordsRequest(type, range, ascendingOrder = false, pageSize = 1)).records.firstOrNull() }.getOrNull()
            return Freshness(label, true, r?.let(time), r?.metadata?.dataOrigin?.packageName)
        }
        return listOf(
            one("Workouts", ExerciseSessionRecord::class) { it.endTime },
            one("Steps", StepsRecord::class) { it.endTime },
            one("Distance", DistanceRecord::class) { it.endTime },
            one("Climb (elevation)", ElevationGainedRecord::class) { it.endTime },
            one("Sleep", SleepSessionRecord::class) { it.endTime },
            one("Resting heart rate", RestingHeartRateRecord::class) { it.time },
            one("HRV", HeartRateVariabilityRmssdRecord::class) { it.time },
        )
    }

    companion object {
        fun kindOf(type: Int): ActivityKind = when (type) {
            ExerciseSessionRecord.EXERCISE_TYPE_RUNNING, ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL -> ActivityKind.RUN
            ExerciseSessionRecord.EXERCISE_TYPE_WALKING, ExerciseSessionRecord.EXERCISE_TYPE_HIKING -> ActivityKind.WALK
            else -> ActivityKind.OTHER
        }

        fun typeName(type: Int): String = when (type) {
            ExerciseSessionRecord.EXERCISE_TYPE_RUNNING -> "Running"
            ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL -> "Treadmill running"
            ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> "Walking"
            ExerciseSessionRecord.EXERCISE_TYPE_HIKING -> "Hiking"
            ExerciseSessionRecord.EXERCISE_TYPE_BIKING -> "Cycling"
            ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY -> "Stationary bike"
            ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT -> "Other workout"
            ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING -> "Strength training"
            ExerciseSessionRecord.EXERCISE_TYPE_ELLIPTICAL -> "Elliptical"
            ExerciseSessionRecord.EXERCISE_TYPE_YOGA -> "Yoga"
            ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL -> "Pool swim"
            ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER -> "Open-water swim"
            ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING -> "HIIT"
            else -> "Type $type"
        }
    }

    /** Minutes actually asleep in a session; whole session if the source gives no stages. */
    private fun asleepMinutes(r: SleepSessionRecord): Int {
        if (r.stages.isEmpty()) return java.time.Duration.between(r.startTime, r.endTime).toMinutes().toInt()
        val awake = setOf(SleepSessionRecord.STAGE_TYPE_AWAKE, SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED, SleepSessionRecord.STAGE_TYPE_OUT_OF_BED)
        return r.stages.filter { it.stage !in awake }.sumOf { java.time.Duration.between(it.startTime, it.endTime).toMinutes() }.toInt()
    }

    /** Your most recent heart-rate reading (time, bpm) from the last 12 hours, as synced by the watch. */
    suspend fun latestHeartRate(): Pair<java.time.Instant, Int>? = runCatching {
        val granted = client.permissionController.getGrantedPermissions()
        if (HealthPermission.getReadPermission(androidx.health.connect.client.records.HeartRateRecord::class) !in granted) return null
        val now = java.time.Instant.now()
        val r = client.readRecords(ReadRecordsRequest(androidx.health.connect.client.records.HeartRateRecord::class,
            TimeRangeFilter.between(now.minusSeconds(12 * 3600), now), ascendingOrder = false, pageSize = 5))
        r.records.flatMap { it.samples }.maxByOrNull { it.time }?.let { it.time to it.beatsPerMinute.toInt() }
    }.getOrNull()

    /** Heart-rate samples (epoch ms, bpm) recorded by the watch between two times. Read-only. */
    suspend fun heartRate(start: java.time.Instant, end: java.time.Instant): List<Pair<Long, Int>> = runCatching {
        val granted = client.permissionController.getGrantedPermissions()
        if (HealthPermission.getReadPermission(androidx.health.connect.client.records.HeartRateRecord::class) !in granted) return emptyList()
        val out = mutableListOf<Pair<Long, Int>>()
        var token: String? = null
        do {
            val r = client.readRecords(ReadRecordsRequest(androidx.health.connect.client.records.HeartRateRecord::class,
                TimeRangeFilter.between(start, end), pageSize = 1000, pageToken = token))
            r.records.forEach { rec -> rec.samples.forEach { sm -> if (!sm.time.isBefore(start) && !sm.time.isAfter(end)) out += sm.time.toEpochMilli() to sm.beatsPerMinute.toInt() } }
            token = r.pageToken
        } while (token != null && out.size < 20_000)
        out.sortedBy { it.first }.distinctBy { it.first }
    }.getOrDefault(emptyList())

    suspend fun dailyMetrics(days: Int, zone: ZoneId = ZoneId.systemDefault()): List<DailyMetric> {
        val granted = client.permissionController.getGrantedPermissions()
        fun can(c: kotlin.reflect.KClass<out androidx.health.connect.client.records.Record>) = HealthPermission.getReadPermission(c) in granted
        val today = LocalDate.now(zone)
        val start = today.minusDays((days - 1).toLong())
        val range = TimeRangeFilter.between(start.atStartOfDay(), today.plusDays(1).atStartOfDay())
        val instantRange = TimeRangeFilter.between(start.atStartOfDay(zone).toInstant(), java.time.Instant.now())

        val steps = if (can(StepsRecord::class)) client.aggregateGroupByPeriod(
            AggregateGroupByPeriodRequest(setOf(StepsRecord.COUNT_TOTAL), range, Period.ofDays(1)),
        ).associate { it.startTime.toLocalDate() to it.result[StepsRecord.COUNT_TOTAL] } else emptyMap()

        val rhr = if (can(RestingHeartRateRecord::class)) client.readRecords(ReadRecordsRequest(RestingHeartRateRecord::class, instantRange, pageSize = 1000))
            .records.associate { it.time.atZone(zone).toLocalDate() to it.beatsPerMinute.toInt() } else emptyMap()

        val hrv = if (can(HeartRateVariabilityRmssdRecord::class)) client.readRecords(ReadRecordsRequest(HeartRateVariabilityRmssdRecord::class, instantRange, pageSize = 5000))
            .records.groupBy { it.time.atZone(zone).toLocalDate() }.mapValues { (_, v) -> v.map { it.heartRateVariabilityMillis }.average() } else emptyMap()

        // Sleep, matched to how Fitbit / Google Health show it:
        //  - time ASLEEP (light + deep + REM), not time in bed: awake and out-of-bed stages are left out;
        //  - one source per night, so two apps logging the same night aren't added together (Fitbit/Google preferred);
        //  - the main sleep (longest session), attributed to the morning you woke up; naps aren't added to the night.
        val sleep = if (can(SleepSessionRecord::class)) {
            val sessions = mutableListOf<SleepSessionRecord>()
            var token: String? = null
            do {
                val r = client.readRecords(ReadRecordsRequest(SleepSessionRecord::class, instantRange, pageSize = 1000, pageToken = token))
                sessions += r.records; token = r.pageToken
            } while (token != null)
            sessions.groupBy { it.endTime.atZone(zone).toLocalDate() }.mapValues { (_, v) ->
                val origin = v.map { it.metadata.dataOrigin.packageName }.distinct()
                    .sortedWith(compareBy<String> { o -> PREFERRED_SLEEP_SOURCES.indexOf(o).let { if (it < 0) 99 else it } }
                        .thenByDescending { o -> v.filter { it.metadata.dataOrigin.packageName == o }.sumOf { it.stages.size } })
                    .first()
                v.filter { it.metadata.dataOrigin.packageName == origin }.maxOf { asleepMinutes(it) }
            }
        } else emptyMap()

        return (0 until days).map { i ->
            val d = start.plusDays(i.toLong())
            DailyMetric(d, steps[d], rhr[d], hrv[d], sleep[d])
        }
    }
}
