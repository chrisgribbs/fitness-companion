package com.fitcompanion.core

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.math.sin

/** Phone GPS altitude is noisy: these walks reproduce the "996 m climb on a 5 km walk" bug. */
class GpsElevationTest {
    private fun walk(km: Double, seed: Long, terrain: (Double) -> Double, noise: Double = 6.0): List<TrackPoint> {
        val rnd = java.util.Random(seed)
        val n = (km * 750).toInt()                                // ~1 point a second at walking pace
        val metersPerDeg = 111_320.0
        var drift = 0.0
        return (0 until n).map { i ->
            val d = i * km * 1000 / n
            drift = 0.995 * drift + rnd.nextGaussian() * 1.2
            var alt = terrain(d) + drift + rnd.nextGaussian() * noise
            if (rnd.nextDouble() < 0.004) alt += (if (rnd.nextBoolean()) 1 else -1) * (20 + rnd.nextDouble() * 40)
            TrackPoint(Instant.ofEpochSecond(i.toLong()), -27.47, 153.02 + d / (metersPerDeg * 0.887), alt)
        }
    }

    @Test fun flatWalkIsNotHundredsOfMetres() {
        for (seed in 1L..5L) {
            val pts = walk(5.3, seed, { 20.0 })
            val old = ElevationCalculator.calculate(pts).ascentMeters ?: 0.0
            val gps = ElevationCalculator.calculateGps(pts).ascentMeters ?: 0.0
            assertTrue("old method should show the bug (got $old)", old > 500)
            assertTrue("flat walk, seed $seed: $gps m", gps < 60)
        }
    }

    @Test fun realHillIsRoughlyRight() {
        // Up 60 m and back down over ~2 km, twice: true climb ~120 m.
        for (seed in 1L..5L) {
            val pts = walk(8.0, seed, { d -> 30 + 30 * sin(d / 640.0 - Math.PI / 2) + 30 })
            val gps = ElevationCalculator.calculateGps(pts).ascentMeters ?: 0.0
            assertTrue("hill walk, seed $seed: $gps m", gps in 60.0..200.0)
        }
    }

    @Test fun hopelessAltitudeIsReportedAsUnavailable() {
        // Altitude swinging 150 m every ~400 m on a 3 km walk: implausible, so no number is shown.
        val pts = walk(3.0, 9, { d -> if ((d / 400).toInt() % 2 == 0) 0.0 else 150.0 }, noise = 2.0)
        assertNull(ElevationCalculator.calculateGps(pts).ascentMeters)
    }
}
