package com.fitcompanion.poc.data

import com.fitcompanion.core.ActivityKind
import com.fitcompanion.core.ActivityRecord
import com.fitcompanion.core.DailyMetric
import java.time.LocalDate
import java.time.ZoneId
import kotlin.random.Random

/**
 * Made-up activities for trying the screens before real data is connected. Every record has
 * isDemo = true, and the UI shows a "DEMO DATA" banner whenever any of it is on screen.
 */
object DemoData {
    fun activities(days: Int = 60, zone: ZoneId = ZoneId.systemDefault(), seed: Int = 7): List<ActivityRecord> {
        val r = Random(seed)
        val today = LocalDate.now(zone)
        return (0 until days).flatMap { i ->
            val d = today.minusDays(i.toLong())
            buildList {
                if (r.nextDouble() < 0.7) add(make(d, 7, ActivityKind.WALK, 2500.0 + r.nextDouble() * 4000, 15.0 + r.nextDouble() * 60, zone))
                if (r.nextDouble() < 0.35) add(make(d, 17, ActivityKind.RUN, 3000.0 + r.nextDouble() * 7000, 20.0 + r.nextDouble() * 110, zone))
            }
        }
    }

    fun daily(days: Int = 90, zone: ZoneId = ZoneId.systemDefault(), seed: Int = 11): List<DailyMetric> {
        val r = Random(seed)
        val today = LocalDate.now(zone)
        return (0 until days).map { i ->
            DailyMetric(today.minusDays(i.toLong()), (5000 + r.nextInt(8000)).toLong(), 56 + r.nextInt(6),
                38.0 + r.nextDouble() * 20, 360 + r.nextInt(120))
        }
    }

    private fun make(d: LocalDate, hour: Int, kind: ActivityKind, dist: Double, asc: Double, zone: ZoneId) = ActivityRecord(
        id = "demo-$d-$kind", kind = kind,
        start = d.atTime(hour, 0).atZone(zone).toInstant(),
        end = d.atTime(hour, 45).atZone(zone).toInstant(),
        distanceMeters = dist, ascentMeters = asc, isDemo = true,
    )
}
