package com.fitcompanion.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos

class PushupTest {
    /** A smooth up-down signal at 30 frames a second, one rep every [periodMs], with a little noise. */
    private fun signal(reps: Int, periodMs: Long = 1600, amp: Double = 0.2): List<Pair<Long, Double>> {
        val rnd = java.util.Random(1)
        val total = reps * periodMs + 1500
        return (0..total step 33).map { t ->
            val phase = if (t < 500 || t > reps * periodMs + 500) 0.0 else ((t - 500) % periodMs).toDouble() / periodMs
            t to (0.5 + amp * (cos(2 * PI * phase) - 1) / 2 * 2 + rnd.nextGaussian() * 0.004)
        }
    }

    @Test fun countsEachRepOnce() {
        val c = RepCounter(minRange = 0.05)
        signal(12).forEach { (t, v) -> c.feed(t, v) }
        assertEquals(12, c.reps.size)
    }

    @Test fun ignoresSmallWobbles() {
        val c = RepCounter(minRange = 0.05)
        signal(10, amp = 0.01).forEach { (t, v) -> c.feed(t, v) }
        assertEquals(0, c.reps.size)
    }

    @Test fun floorSensorOnOff() {
        val c = RepCounter(minRange = 0.5, smoothing = 1.0)
        var t = 0L
        repeat(15) { c.feed(t, 1.0); t += 700; c.feed(t, 0.0); t += 700 }
        c.feed(t, 1.0)
        assertEquals(15, c.reps.size)
    }

    @Test fun notesFlagShallowAndSlowing() {
        val reps = (1..12).map { i -> RepForm(depth = if (i > 8) 0.5 else 1.0, durationMs = if (i > 8) 2600 else 1400) }
        val n = PushupAnalysis.notes(reps, PushupMethod.CAMERA_FRONT)
        assertTrue(n.toString(), n.any { it.contains("slowed") })
        val c = PushupFormAssessor.assess(reps, PushupMethod.CAMERA_FRONT)
        assertEquals(CheckStatus.WORK_ON, c.first { it.id == "depth" }.status)
    }

    @Test fun sideOnChecksDepthLockoutAndHips() {
        val good = RepForm(1.0, 1800, elbowDegrees = 88.0, bodyLineDegrees = 175.0, lockoutDegrees = 170.0, neckDegrees = 165.0)
        val sag = good.copy(bodyLineDegrees = 150.0, hipsLow = true)
        val reps = List(6) { good } + List(4) { sag }
        val c = PushupFormAssessor.assess(reps, PushupMethod.CAMERA_SIDE).associateBy { it.id }
        assertEquals(CheckStatus.GOOD, c["depth"]!!.status)
        assertEquals(CheckStatus.GOOD, c["lockout"]!!.status)
        assertEquals(CheckStatus.WORK_ON, c["bodyline"]!!.status)
        assertTrue(c["bodyline"]!!.result, c["bodyline"]!!.result.contains("sagged on 4"))
        assertEquals(6, PushupFormAssessor.cleanReps(reps, PushupMethod.CAMERA_SIDE))
    }
}
