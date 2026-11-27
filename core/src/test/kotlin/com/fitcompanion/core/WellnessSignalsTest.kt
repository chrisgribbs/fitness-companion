package com.fitcompanion.core

import java.time.LocalDate
import org.junit.Test
import org.junit.Assert.assertEquals

class WellnessSignalsTest {
    private val today = LocalDate.of(2026, 11, 27)

    private fun days(n: Int, rhr: (Int) -> Int?, hrv: (Int) -> Double? = { null }) =
        (0 until n).map { i -> DailyMetric(today.minusDays(i.toLong()), restingHeartRate = rhr(i), hrvMillis = hrv(i)) }

    @Test fun oneBeatUpIsNormal() {
        // Usual alternates 63/65 (avg 64); last week 65 every day.
        val d = days(40, { i -> if (i < 7) 65 else if (i % 2 == 0) 63 else 65 })
        assertEquals(SignalLevel.NORMAL, WellnessSignals.restingHeartRate(d, today).level)
    }

    @Test fun sustainedRiseIsNotable() {
        val d = days(40, { i -> if (i < 4) 71 else 64 })
        assertEquals(SignalLevel.NOTABLE, WellnessSignals.restingHeartRate(d, today).level)
    }

    @Test fun oneHighDayIsNotNotable() {
        val d = days(40, { i -> if (i == 0) 72 else 64 })
        val s = WellnessSignals.restingHeartRate(d, today)
        assert(s.level != SignalLevel.NOTABLE) { s.toString() }
    }

    @Test fun tooFewDays() {
        assertEquals(SignalLevel.TOO_LITTLE_DATA, WellnessSignals.restingHeartRate(days(8, { 64 }), today).level)
    }

    @Test fun hrvSmallDipIsNormal() {
        // Usual varies 40–60 ms; last week averages ~47 ms.
        val d = days(60, { 64 }, { i -> if (i < 7) 47.0 else listOf(40.0, 50.0, 60.0)[i % 3] })
        assertEquals(SignalLevel.NORMAL, WellnessSignals.hrv(d, today).level)
    }

    @Test fun hrvClearDropIsNotable() {
        val d = days(60, { 64 }, { i -> if (i < 7) 30.0 else listOf(48.0, 50.0, 52.0)[i % 3] })
        assertEquals(SignalLevel.NOTABLE, WellnessSignals.hrv(d, today).level)
    }
}
