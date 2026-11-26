package com.fitcompanion.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.math.sin

class TerrainClimbTest {
    private fun line(km: Double): List<TrackPoint> =
        (0..(km * 1000).toInt() step 7).map { m -> TrackPoint(Instant.ofEpochSecond(m.toLong()), -27.47, 153.0 + m / 98_700.0) }

    @Test fun resamplesEveryTenMetres() {
        val s = TerrainClimb.resample(line(1.0))
        assertTrue(s.size in 99..102)
        assertEquals(10.0, s[1].distanceMeters, 0.01)
    }

    @Test fun hillClimbIsAccurate() {
        // One hill 40 m up and down over 2 km, plus 0.2 m LiDAR noise: climb should be ~40 m (within 3 m).
        val s = TerrainClimb.resample(line(2.0))
        val rnd = java.util.Random(3)
        val h = s.map { 10 + 40 * sin(Math.PI * it.distanceMeters / 2000) + rnd.nextGaussian() * 0.2 }
        val r = TerrainClimb.calculate(s, h, "test")
        assertEquals(40.0, r.ascentMeters!!, 3.0)
    }

    @Test fun bridgeOverRiverIsNotADive() {
        // Flat 8 m banks with a 300 m bridge over the river (terrain model shows the river bed at -5 m).
        val s = TerrainClimb.resample(line(1.5))
        val h = s.map { if (it.distanceMeters in 600.0..900.0) -5.0 else 8.0 }
        val r = TerrainClimb.calculate(s, h, "test")
        assertTrue("bridge: ${r.ascentMeters}", r.ascentMeters!! < 1.0)
    }

    @Test fun shortUnderpassIsIgnored() {
        // Walking over a 25 m road overpass: the ground model dips 6 m underneath.
        val s = TerrainClimb.resample(line(1.0))
        val h = s.map { if (it.distanceMeters in 500.0..520.0) 14.0 else 20.0 }
        assertTrue(TerrainClimb.calculate(s, h, "test").ascentMeters!! < 1.0)
    }

    @Test fun mostlyMissingIsUnavailable() {
        val s = TerrainClimb.resample(line(1.0))
        assertNull(TerrainClimb.calculate(s, s.map { null }, "test").ascentMeters)
    }
}
