package com.fitcompanion.core

import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class WorkoutAnalysisTest {
    /** 30-min walk heading north at steady pace; minutes 10–14 and 20–24 climb a hill; HR follows the hill. */
    private fun walk(hrFollowsHill: Boolean): WorkoutAnalysis.Input {
        val start = 1_700_000_000_000L
        fun uphill(m: Int) = m in 10..14 || m in 20..24
        // Speed wobbles a little each minute (unrelated to the hill), like real walking.
        val dist = DoubleArray(1801)
        for (s in 1..1800) dist[s] = dist[s - 1] + 1.5 + 0.12 * kotlin.math.sin(s / 60 * 1.7)
        val route = (0..1800 step 5).map { s -> TrackPoint(Instant.ofEpochMilli(start + s * 1000L), -26.0 + dist[s] / 111_320.0, 153.0) }
        // Height: +6 m over each hill minute, by distance.
        val heightBySecond = DoubleArray(1801)
        for (s in 1..1800) heightBySecond[s] = heightBySecond[s - 1] + if (uphill((s - 1) / 60)) 0.1 else 0.0
        val heightAt: (Double) -> Double? = { d ->
            val s = dist.indexOfFirst { it >= d }.let { if (it < 0) 1800 else it }
            heightBySecond[s]
        }
        val rnd = java.util.Random(3)
        val hr = (0..1800 step 5).map { s ->
            val m = ((s * 1000L - 45_000) / 60_000).toInt()
            val bump = if (hrFollowsHill && s >= 45 && uphill(m)) 20 else 0
            start + s * 1000L to (110 + bump + rnd.nextInt(3))
        }
        return WorkoutAnalysis.Input("Walk", start, start + 1_800_000, route, heightAt, "test terrain", hr, 60, 180)
    }

    @Test fun hillsDriveHeartRate() {
        val r = WorkoutAnalysis.report(walk(true))
        assertTrue(r, r.contains("mainly by HILLS"))
        assertTrue(r, r.contains("Uphill"))
        assertTrue(r, r.contains("climbing"))
    }

    @Test fun noRouteSaysSo() {
        val r = WorkoutAnalysis.report(walk(true).copy(route = null))
        assertTrue(r, r.contains("no GPS route"))
    }

    @Test fun noHeartRateSaysSo() {
        val r = WorkoutAnalysis.report(walk(true).copy(hr = emptyList()))
        assertTrue(r, r.contains("not recorded"))
    }
}
