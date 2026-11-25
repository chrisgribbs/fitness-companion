package com.fitcompanion.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration

class SplitsTest {

    @Test
    fun evenPaceGivesEqualKilometreSplits() {
        // 2.5 km at 1 m/s → 1000 s per km, partial 500 m at the end
        val pts = TestData.track(List(2501) { 0.0 }, metersPerPoint = 1.0, hr = 140)
        val s = Splits.compute(pts)
        assertEquals(3, s.size)
        assertEquals(1000.0, s[0].paceSecondsPerKm, 0.5)
        assertEquals(Duration.ofSeconds(1000), s[1].duration)
        assertTrue(s[2].partial)
        assertEquals(500.0, s[2].distanceMeters, 0.5)
        assertEquals(140, s[0].averageHeartRateBpm)
    }

    @Test
    fun tinyRemainderIsDropped() {
        val pts = TestData.track(List(1021) { 0.0 })
        assertEquals(1, Splits.compute(pts).size)
    }

    @Test
    fun usesHaversineWhenNoSourceDistance() {
        val pts = TestData.track(List(2001) { 0.0 }).map { it.copy(distanceMeters = null) }
        val s = Splits.compute(pts)
        assertEquals(2, s.size)
        assertEquals(1000.0, s[0].paceSecondsPerKm, 5.0)
    }

    @Test
    fun formatPace() {
        assertEquals("5:32", Splits.formatPace(332.0))
        assertEquals("—", Splits.formatPace(null))
    }
}

class TcxParserTest {

    private val sample = """
        <?xml version="1.0" encoding="UTF-8"?>
        <TrainingCenterDatabase xmlns="http://www.garmin.com/xmlschemas/TrainingCenterDatabase/v2">
          <Activities>
            <Activity Sport="Running">
              <Id>2026-01-01T00:00:00.000Z</Id>
              <Lap StartTime="2026-01-01T00:00:00.000Z">
                <TotalTimeSeconds>20.0</TotalTimeSeconds>
                <DistanceMeters>60.0</DistanceMeters>
                <Track>
                  <Trackpoint>
                    <Time>2026-01-01T00:00:00.000Z</Time>
                    <Position><LatitudeDegrees>-27.47</LatitudeDegrees><LongitudeDegrees>153.02</LongitudeDegrees></Position>
                    <AltitudeMeters>12.5</AltitudeMeters>
                    <DistanceMeters>0.0</DistanceMeters>
                    <HeartRateBpm><Value>120</Value></HeartRateBpm>
                  </Trackpoint>
                  <Trackpoint>
                    <Time>2026-01-01T10:00:10+10:00</Time>
                    <Position><LatitudeDegrees>-27.4702</LatitudeDegrees><LongitudeDegrees>153.0201</LongitudeDegrees></Position>
                    <AltitudeMeters>13.0</AltitudeMeters>
                    <DistanceMeters>30.0</DistanceMeters>
                  </Trackpoint>
                  <Trackpoint>
                    <Time>2026-01-01T00:00:20Z</Time>
                    <DistanceMeters>60.0</DistanceMeters>
                  </Trackpoint>
                </Track>
              </Lap>
            </Activity>
          </Activities>
        </TrainingCenterDatabase>
    """.trimIndent()

    @Test
    fun parsesTrackpoints() {
        val a = TcxParser.parse(sample.byteInputStream())
        assertEquals("Running", a.sport)
        assertEquals(1, a.lapCount)
        assertEquals(60.0, a.totalDistanceMeters!!, 1e-9)
        assertEquals(3, a.points.size)
        assertEquals(2, a.pointsWithPosition)
        assertEquals(2, a.pointsWithAltitude)
        assertEquals(1, a.pointsWithHeartRate)
        assertEquals(-27.47, a.points[0].latitude!!, 1e-9)
        assertEquals(30.0, a.points[1].distanceMeters!!, 1e-9) // trackpoint distance, not the lap's
        assertEquals(TestData.T0.plusSeconds(10), a.points[1].time) // +10:00 offset handled
        assertNull(a.points[2].altitudeMeters)
        assertFalse(a.points[2].hasPosition)
    }

    @Test
    fun rejectsDoctype() {
        val evil = """<?xml version="1.0"?><!DOCTYPE x [<!ENTITY e SYSTEM "file:///etc/passwd">]><TrainingCenterDatabase>&e;</TrainingCenterDatabase>"""
        val threw = try { TcxParser.parse(evil.byteInputStream()); false } catch (_: Exception) { true }
        assertTrue("DOCTYPE should be rejected", threw)
    }
}
