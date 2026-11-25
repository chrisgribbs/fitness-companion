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

        return sessions.mapNotNull { s ->
            val kind = when (s.exerciseType) {
                ExerciseSessionRecord.EXERCISE_TYPE_RUNNING, ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL -> ActivityKind.RUN
                ExerciseSessionRecord.EXERCISE_TYPE_WALKING, ExerciseSessionRecord.EXERCISE_TYPE_HIKING -> ActivityKind.WALK
                else -> return@mapNotNull null
            }
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

        // Sleep is attributed to the day it ends (the morning you wake up).
        val sleep = if (can(SleepSessionRecord::class)) client.readRecords(ReadRecordsRequest(SleepSessionRecord::class, instantRange, pageSize = 1000))
            .records.groupBy { it.endTime.atZone(zone).toLocalDate() }
            .mapValues { (_, v) -> v.sumOf { java.time.Duration.between(it.startTime, it.endTime).toMinutes() }.toInt() } else emptyMap()

        return (0 until days).map { i ->
            val d = start.plusDays(i.toLong())
            DailyMetric(d, steps[d], rhr[d], hrv[d], sleep[d])
        }
    }
}
