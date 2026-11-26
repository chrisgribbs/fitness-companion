package com.fitcompanion.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class HeartRateTest {
    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test fun parses8BitHeartRateWithContact() {
        val r = HeartRateParser.parse(b(0x06, 72))!!        // contact supported + detected
        assertEquals(72, r.bpm); assertEquals(true, r.contact); assertEquals(0, r.rrMs.size)
    }

    @Test fun parses16BitHeartRateEnergyAndRr() {
        // flags: 16-bit (0x01) | energy (0x08) | RR (0x10) ; bpm 300 ; energy 5 ; RR 1024 (=1000 ms), 512 (=500 ms)
        val r = HeartRateParser.parse(b(0x19, 0x2C, 0x01, 0x05, 0x00, 0x00, 0x04, 0x00, 0x02))!!
        assertEquals(300, r.bpm)
        assertEquals(listOf(1000.0, 500.0), r.rrMs)
    }

    @Test fun rejectsShortPackets() { assertNull(HeartRateParser.parse(b(0x00))) }

    @Test fun karvonenZones() {
        assertEquals(180, HeartZones.estimatedMax(40))           // 208 - 28
        val z = HeartZones.zones(60, 180)                        // reserve 120
        assertEquals(120, z[0].low); assertEquals(132, z[1].low); assertEquals(144, z[2].low)
        assertEquals(2, HeartZones.zoneOf(135, z)); assertEquals(0, HeartZones.zoneOf(100, z)); assertEquals(5, HeartZones.zoneOf(179, z))
    }

    @Test fun summaryTimeInZoneAndRmssd() {
        val z = HeartZones.zones(60, 180)
        val samples = (0 until 61).map { it * 1000L to if (it < 30) 135 else 150 }   // 30 s zone 2, 30 s zone 3
        val rr = List(40) { if (it % 2 == 0) 800.0 else 820.0 }
        val s = LiveStats.summarise(samples, rr, z, target = 2)!!
        assertEquals(60, s.durationSec); assertEquals(30, s.secondsInZone[2]); assertEquals(30, s.secondsInZone[3]); assertEquals(30, s.secondsInTarget)
        assertNotNull(s.rmssdMs); assertEquals(20.0, s.rmssdMs!!, 1e-6)
    }
}
