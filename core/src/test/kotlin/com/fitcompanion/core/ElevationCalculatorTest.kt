package com.fitcompanion.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ElevationCalculatorTest {

    @Test
    fun flatRouteWithGpsNoiseHasNoAscent() {
        val alts = TestData.noise(2000, amplitude = 1.5).map { 20.0 + it }
        val r = ElevationCalculator.calculate(TestData.track(alts))
        assertTrue("ascent ${r.ascentMeters}", r.ascentMeters!! < 1.0)
        assertTrue("descent ${r.descentMeters}", r.descentMeters!! < 1.0)
        assertEquals(DataQuality.ESTIMATED, r.quality)
    }

    @Test
    fun naiveSumOfNoiseWouldBeLargeButSmoothedIsNot() {
        // Demonstrates why smoothing matters: summing every positive step of pure noise is huge.
        val alts = TestData.noise(2000, amplitude = 1.5).map { 20.0 + it }
        val naive = alts.zipWithNext().sumOf { (a, b) -> maxOf(0.0, b - a) }
        val r = ElevationCalculator.calculate(TestData.track(alts))
        assertTrue("naive $naive should be big", naive > 500)
        assertTrue(r.ascentMeters!! < naive / 100)
    }

    @Test
    fun steadyClimbIsCountedInFull() {
        val n = 1000
        val alts = (0 until n).map { 100.0 * it / (n - 1) } .zip(TestData.noise(n, 1.0)) { a, e -> a + e }
        val r = ElevationCalculator.calculate(TestData.track(alts))
        assertEquals(100.0, r.ascentMeters!!, 3.0)
        assertTrue(r.descentMeters!! < 3.0)
    }

    @Test
    fun outAndBackHillCountsClimbNotNetChange() {
        val up = (0..500).map { it * 0.1 }          // 0 → 50 m
        val down = (499 downTo 0).map { it * 0.1 }  // 50 → 0 m
        val r = ElevationCalculator.calculate(TestData.track(up + down))
        assertEquals(50.0, r.ascentMeters!!, 0.5)
        assertEquals(50.0, r.descentMeters!!, 0.5)
        assertEquals(0.0, r.netChangeMeters!!, 0.5)
    }

    @Test
    fun repeatedHillsAccumulate() {
        val hill = (0..200).map { it * 0.1 } + (199 downTo 0).map { it * 0.1 } // 20 m up/down
        val alts = List(5) { hill }.flatten()
        val r = ElevationCalculator.calculate(TestData.track(alts))
        assertEquals(100.0, r.ascentMeters!!, 1.0)
    }

    @Test
    fun gentleStairStepClimbBelowThresholdPerStepStillCounts() {
        // 2 m steps (each below the 3 m threshold) adding up to 20 m.
        val alts = (0 until 10).flatMap { step -> List(30) { step * 2.0 } }
        val r = ElevationCalculator.calculate(TestData.track(alts))
        assertEquals(18.0, r.ascentMeters!!, 0.01) // 0 → 18 m (10 steps: 0,2,…,18)
    }

    @Test
    fun singlePointSpikeIsIgnored() {
        val alts = MutableList(300) { 10.0 }.also { it[150] = 45.0 }
        val r = ElevationCalculator.calculate(TestData.track(alts))
        assertEquals(0.0, r.ascentMeters!!, 0.01)
        assertEquals(45.0, r.maxAltitudeMeters!!, 0.01) // raw data preserved in stats
    }

    @Test
    fun missingAltitudesAreReportedNotInvented() {
        val alts: List<Double?> = (0..100).map { if (it % 4 == 0) null else it * 0.2 }
        val r = ElevationCalculator.calculate(TestData.track(alts))
        assertEquals(26, r.pointsMissingAltitude)
        assertEquals(75, r.pointsWithAltitude)
        assertTrue(r.ascentMeters!! > 15)
    }

    @Test
    fun noAltitudeMeansMissingNotZero() {
        val r = ElevationCalculator.calculate(TestData.track(List(50) { null }))
        assertNull(r.ascentMeters)
        assertEquals(DataQuality.MISSING, r.quality)
        assertTrue(r.profile.isEmpty())
    }

    @Test
    fun emptyTrack() {
        val r = ElevationCalculator.calculate(emptyList())
        assertNull(r.ascentMeters)
        assertEquals(DataQuality.MISSING, r.quality)
    }

    @Test
    fun zeroThresholdEqualsSimplePositiveSumOnCleanData() {
        val alts = listOf(0.0, 5.0, 3.0, 8.0, 8.0, 2.0)
        val r = ElevationCalculator.calculate(TestData.track(alts), ElevationOptions(thresholdMeters = 0.0, medianWindow = 1))
        assertEquals(10.0, r.ascentMeters!!, 1e-9) // +5, +5
        assertEquals(8.0, r.descentMeters!!, 1e-9) // -2, -6
    }

    @Test
    fun profileUsesSmoothedValuesAndDistance() {
        val r = ElevationCalculator.calculate(TestData.track(listOf(1.0, 2.0, 3.0), metersPerPoint = 10.0))
        assertEquals(3, r.profile.size)
        assertEquals(20.0, r.profile.last().distanceMeters, 1e-9)
    }

    @Test
    fun medianFilterKeepsEdges() {
        val out = ElevationCalculator.medianFilter(listOf(1.0, 100.0, 1.0, 1.0, 1.0), 5)
        assertEquals(listOf(1.0, 1.0, 1.0, 1.0, 1.0), out)
    }
}
