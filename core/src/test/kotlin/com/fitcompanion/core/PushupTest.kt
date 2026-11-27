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
        assertTrue(n.toString(), n.any { it.contains("shallower") })
        assertTrue(n.toString(), n.any { it.contains("slowed") })
    }
}
