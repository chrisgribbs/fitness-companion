package com.fitcompanion.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteSanityTest {
    private val gor = listOf(
        Waypoint("Torquay", -38.33, 144.32), Waypoint("Bells Beach", -38.37, 144.28), Waypoint("Anglesea", -38.41, 144.19),
        Waypoint("Lorne", -38.54, 143.97), Waypoint("Apollo Bay", -38.76, 143.67), Waypoint("Port Campbell", -38.62, 142.99),
        Waypoint("Warrnambool", -38.38, 142.48))

    @Test fun realRouteIsFine() { assertTrue(RouteSanity.check(gor, 240_000.0).isEmpty()) }

    @Test fun torquayInEnglandIsBlocked() {
        val wrong = listOf(Waypoint("Torquay", 50.46, -3.53)) + gor.drop(1)
        val p = RouteSanity.check(wrong, 240_000.0)
        assertTrue(p.any { it.blocksSaving && it.message.contains("Torquay") })
    }

    @Test fun farMatchIsIgnored() {
        val c = WaypointCheck.of("Torquay", -38.33, 144.32, 50.46, -3.53)
        assertEquals(WaypointCheckStatus.TOO_FAR, c.status)
        assertEquals(-38.33, c.resolved().latitude, 1e-9)
    }
}
