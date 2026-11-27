package com.fitcompanion.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class CompletionTest {
    private val today = LocalDate.of(2026, 11, 27)
    private val zone = ZoneId.of("Australia/Brisbane")

    @Test fun recordedFinishStaysFinishedWithoutWorkouts() {
        val c = ChallengeDefinition("n", "Noosa", ChallengeMetric.DISTANCE, 6800.0, false, true, today.minusDays(60),
            completion = Completion(today.minusDays(40), 8500.0, 3))
        val p = ChallengeEngine.progress(c, emptyList(), today, zone)
        assertEquals(today.minusDays(40), p.completedOn)
        assertEquals(1.0, p.fraction, 0.0)
        assertEquals(21, c.completion!!.daysTaken(c.startDate))
    }
}
