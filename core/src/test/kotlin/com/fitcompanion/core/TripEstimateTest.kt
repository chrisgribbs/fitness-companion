package com.fitcompanion.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TripEstimateTest {
    // 2 km due north.
    private val route = (0..100).map { k -> -27.46 + k * 2000.0 / 100 / 111_320.0 to 153.02 }
    private fun input(heights: ((Double) -> Double?)?, run: Boolean = false, round: Boolean = false) =
        TripInput("A", "B", route, listOf(TripStreet("Flat St", 1000.0), TripStreet("Hill Tce", 1000.0)), heights, "test",
            run, round, flatPaceSecPerKm = 720.0, paceSource = "test")

    @Test fun flatWalkMatchesYourPace() {
        val e = TripEstimator.estimate(input(null))
        assertEquals(2000.0, e.meters, 15.0)
        assertEquals(24.0, e.minutes, 0.6) // 2 km at 12:00/km
    }

    @Test fun hillSlowsYouAndIsNamed() {
        // Second km climbs 60 m.
        val hill: (Double) -> Double? = { d -> if (d <= 1000) 0.0 else (d - 1000) * 0.06 }
        val e = TripEstimator.estimate(input(hill))
        assertTrue(e.minutes > 25)
        assertEquals(60.0, e.climbM!!, 2.0)
        assertEquals("Hill Tce", e.steepest.first().street)
        val back = TripEstimator.estimate(input(hill, round = true))
        assertEquals(4000.0, back.meters, 30.0)
        assertEquals(60.0, back.climbM!!, 2.0 + 0.0) // up on the way there, down on the way back… plus nothing else
    }
}
