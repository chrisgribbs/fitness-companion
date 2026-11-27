package com.fitcompanion.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

private val ZONE: ZoneId = ZoneId.of("Australia/Brisbane")
private val TODAY: LocalDate = LocalDate.of(2026, 11, 26) // a Thursday

private fun act(date: LocalDate, kind: ActivityKind, distance: Double? = null, ascent: Double? = null, demo: Boolean = false) =
    ActivityRecord(
        id = "$date-$kind-$distance-$ascent",
        kind = kind,
        start = date.atTime(7, 0).atZone(ZONE).toInstant(),
        end = date.atTime(8, 0).atZone(ZONE).toInstant(),
        distanceMeters = distance,
        ascentMeters = ascent,
        isDemo = demo,
    )

class ChallengeEngineTest {

    private val everest = Everest.definition("e", startDate = LocalDate.of(2026, 11, 1))

    @Test
    fun totalsPercentRemaining() {
        val acts = listOf(
            act(LocalDate.of(2026, 11, 3), ActivityKind.RUN, ascent = 120.0),
            act(LocalDate.of(2026, 11, 20), ActivityKind.WALK, ascent = 80.5),
        )
        val p = ChallengeEngine.progress(everest, acts, TODAY, ZONE)
        assertEquals(200.5, p.totalMeters, 1e-9)
        assertEquals(200.5 / 8849 * 100, p.percent, 1e-9)
        assertEquals(8849 - 200.5, p.remainingMeters, 1e-9)
        assertFalse(p.completed)
    }

    @Test
    fun runsOnlyExcludesWalks() {
        val runsOnly = Everest.definition("e", LocalDate.of(2026, 11, 1), includeRuns = true, includeWalks = false)
        val acts = listOf(act(TODAY, ActivityKind.RUN, ascent = 50.0), act(TODAY, ActivityKind.WALK, ascent = 70.0))
        assertEquals(50.0, ChallengeEngine.progress(runsOnly, acts, TODAY, ZONE).totalMeters, 1e-9)
    }

    @Test
    fun activitiesBeforeStartOrInFutureAreIgnored() {
        val acts = listOf(
            act(LocalDate.of(2026, 10, 31), ActivityKind.RUN, ascent = 999.0),
            act(TODAY.plusDays(1), ActivityKind.RUN, ascent = 999.0),
            act(TODAY, ActivityKind.RUN, ascent = 10.0),
        )
        assertEquals(10.0, ChallengeEngine.progress(everest, acts, TODAY, ZONE).totalMeters, 1e-9)
    }

    @Test
    fun missingAscentIsCountedSeparatelyNotAsZero() {
        val acts = listOf(act(TODAY, ActivityKind.RUN, ascent = null), act(TODAY, ActivityKind.WALK, ascent = 30.0))
        val p = ChallengeEngine.progress(everest, acts, TODAY, ZONE)
        assertEquals(1, p.activitiesCounted)
        assertEquals(1, p.activitiesMissingData)
    }

    @Test
    fun thisWeekStartsMonday() {
        val monday = LocalDate.of(2026, 11, 23)
        val acts = listOf(act(monday, ActivityKind.RUN, ascent = 40.0), act(monday.minusDays(1), ActivityKind.RUN, ascent = 100.0))
        val p = ChallengeEngine.progress(everest, acts, TODAY, ZONE)
        assertEquals(40.0, p.thisWeekMeters, 1e-9)
        assertEquals(8, p.weekly.size)
        assertEquals(monday, p.weekly.last().start)
        assertEquals(100.0, p.weekly[6].meters, 1e-9)
        assertEquals(140.0, p.thisMonthMeters, 1e-9)
    }

    @Test
    fun milestonesReachedAndNext() {
        val acts = listOf(act(TODAY, ActivityKind.RUN, ascent = 300.0))
        val p = ChallengeEngine.progress(everest, acts, TODAY, ZONE)
        assertEquals(listOf("Mt Coot-tha, Brisbane"), p.reached.map { it.name })
        assertEquals("Mt Kosciuszko", p.next!!.name)
    }

    @Test
    fun completionDateAndCap() {
        val small = everest.copy(goalMeters = 100.0, milestones = emptyList())
        val acts = listOf(act(LocalDate.of(2026, 11, 5), ActivityKind.RUN, ascent = 60.0), act(LocalDate.of(2026, 11, 9), ActivityKind.RUN, ascent = 60.0))
        val p = ChallengeEngine.progress(small, acts, TODAY, ZONE)
        assertEquals(LocalDate.of(2026, 11, 9), p.completedOn)
        assertEquals(1.0, p.fraction, 1e-9)
        assertEquals(0.0, p.remainingMeters, 1e-9)
        assertNull(p.estimate)
    }

    @Test
    fun estimateUsesRecentDailyAverage() {
        // 28-day window, 280 m climbed in it → 10 m/day; 8849-280 = 8569 m left → 857 days.
        val def = everest.copy(startDate = LocalDate.of(2026, 1, 1))
        val acts = listOf(act(TODAY.minusDays(3), ActivityKind.RUN, ascent = 280.0))
        val e = ChallengeEngine.progress(def, acts, TODAY, ZONE).estimate!!
        assertEquals(28, e.basisDays)
        assertEquals(10.0, e.metersPerDay, 1e-9)
        assertEquals(TODAY.plusDays(857), e.date)
    }

    @Test
    fun noRecentActivityMeansNoEstimate() {
        val def = everest.copy(startDate = LocalDate.of(2026, 1, 1))
        val acts = listOf(act(LocalDate.of(2026, 2, 1), ActivityKind.RUN, ascent = 50.0))
        assertNull(ChallengeEngine.progress(def, acts, TODAY, ZONE).estimate)
    }

    @Test
    fun demoDataIsFlagged() {
        val p = ChallengeEngine.progress(everest, listOf(act(TODAY, ActivityKind.RUN, ascent = 5.0, demo = true)), TODAY, ZONE)
        assertTrue(p.includesDemoData)
    }

    @Test
    fun distanceChallengeUsesDistance() {
        val def = ChallengeDefinition("d", "Walk", ChallengeMetric.DISTANCE, 10_000.0, includeRuns = false, includeWalks = true, startDate = TODAY.minusDays(10))
        val acts = listOf(act(TODAY, ActivityKind.WALK, distance = 4200.0, ascent = 30.0))
        assertEquals(4200.0, ChallengeEngine.progress(def, acts, TODAY, ZONE).totalMeters, 1e-9)
    }
}

class JourneyRouteTest {
    // Three points along the equator: 0°, 0.1°, 0.3° east → legs ≈ 11.12 km and 22.24 km.
    private val route = JourneyRoute(listOf(Waypoint("A", 0.0, 0.0), Waypoint("B", 0.0, 0.1), Waypoint("C", 0.0, 0.3)))

    @Test
    fun lengthIsSumOfLegs() {
        assertEquals(33_358.0, route.totalMeters, 20.0)
        assertEquals(0.0, route.cumulativeMeters.first(), 0.0)
    }

    @Test
    fun positionInterpolatesWithinLeg() {
        val half = route.cumulativeMeters[1] + (route.cumulativeMeters[2] - route.cumulativeMeters[1]) / 2
        val p = route.positionAt(half)
        assertEquals(0.2, p.longitude, 1e-6)
        assertEquals(1, p.lastPassedIndex)
        assertFalse(p.finished)
    }

    @Test
    fun clampsAtEnds() {
        assertEquals(0.0, route.positionAt(-5.0).longitude, 1e-9)
        val end = route.positionAt(1e9)
        assertTrue(end.finished)
        assertEquals(0.3, end.longitude, 1e-9)
    }

    @Test
    fun travelledPathEndsAtCurrentPosition() {
        val path = route.travelledPath(route.cumulativeMeters[1] + 100)
        assertEquals(3, path.size) // A, B, current point
        assertTrue(path.last().second > 0.1)
    }

    @Test
    fun unnamedPathPointsAreNotMilestones() {
        val dense = JourneyRoute(listOf(Waypoint("Start", 0.0, 0.0), Waypoint("", 0.0, 0.05), Waypoint("End", 0.0, 0.1)))
        assertEquals(listOf("End"), dense.waypointMilestones().map { it.name })
        assertEquals(0.05, dense.pointAt(dense.totalMeters / 2).second, 1e-6)
    }

    @Test
    fun waypointMilestones() {
        val m = route.waypointMilestones()
        assertEquals(listOf("B", "C"), m.map { it.name })
        assertEquals(route.totalMeters, m.last().atMeters, 1e-9)
    }
}

class ChallengeValidatorTest {
    private val start = LocalDate.of(2026, 11, 26)

    private fun journeyDraft(vararg wps: DraftWaypoint, goal: Double? = null) = ChallengeDraft(
        name = "Brisbane to the Gold Coast", metric = "distance", goalMeters = goal,
        includeRuns = false, includeWalks = true, waypoints = wps.toList(),
    )

    @Test
    fun validJourneyUsesMeasuredRouteLength() {
        val r = ChallengeValidator.validate(
            journeyDraft(DraftWaypoint("A", 0.0, 0.0), DraftWaypoint("B", 0.0, 0.1), goal = 50_000.0), "id", start,
        ) as ValidationResult.Ok
        assertEquals(11_120.0, r.definition.goalMeters, 20.0)
        assertTrue(r.warnings.any { it.contains("Using") }) // AI's 50 km claim rejected
        assertEquals(listOf("B"), r.definition.milestones.map { it.name })
        assertTrue(r.definition.createdByAi)
        assertFalse(r.definition.includeRuns)
    }

    @Test
    fun badCoordinatesRejected() {
        val r = ChallengeValidator.validate(journeyDraft(DraftWaypoint("A", 0.0, 0.0), DraftWaypoint("B", 95.0, 0.0)), "id", start)
        assertTrue(r is ValidationResult.Invalid)
    }

    @Test
    fun singleWaypointRejected() {
        assertTrue(ChallengeValidator.validate(journeyDraft(DraftWaypoint("A", 0.0, 0.0)), "id", start) is ValidationResult.Invalid)
    }

    @Test
    fun unknownMetricRejected() {
        val d = ChallengeDraft("X", "speed", 100.0, true, true)
        assertTrue(ChallengeValidator.validate(d, "id", start) is ValidationResult.Invalid)
    }

    @Test
    fun neitherRunsNorWalksRejected() {
        val d = ChallengeDraft("X", "ascent", 1000.0, includeRuns = false, includeWalks = false)
        assertTrue(ChallengeValidator.validate(d, "id", start) is ValidationResult.Invalid)
    }

    @Test
    fun ascentChallengeKeepsGoalAndDropsBadMilestones() {
        val d = ChallengeDraft("Kosciuszko x2", "ascent", 4456.0, null, null,
            milestones = listOf(DraftMilestone("Half", 2228.0), DraftMilestone("Too far", 9999.0), DraftMilestone(null, 10.0)))
        val r = ChallengeValidator.validate(d, "id", start) as ValidationResult.Ok
        assertEquals(4456.0, r.definition.goalMeters, 0.0)
        assertEquals(listOf("Half"), r.definition.milestones.map { it.name })
        assertTrue(r.definition.includeRuns && r.definition.includeWalks)
        assertEquals(3, r.warnings.size) // defaults + 2 dropped milestones
    }

    @Test
    fun ascentGoalOutOfRangeRejected() {
        assertTrue(ChallengeValidator.validate(ChallengeDraft("X", "ascent", 5.0, true, true), "id", start) is ValidationResult.Invalid)
    }

    @Test
    fun waypointCheck() {
        val ok = WaypointCheck.of("Surfers Paradise", -28.0, 153.43, -28.002, 153.43)
        assertEquals(WaypointCheckStatus.VERIFIED, ok.status)
        val moved = WaypointCheck.of("Surfers Paradise", -27.8, 153.43, -28.0, 153.43) // ~22 km: map wins
        assertEquals(WaypointCheckStatus.MOVED, moved.status)
        assertEquals(-28.0, moved.resolved().latitude, 0.0)
        val far = WaypointCheck.of("Surfers Paradise", -27.0, 153.43, -28.0, 153.43) // ~111 km: different place, AI kept
        assertEquals(WaypointCheckStatus.TOO_FAR, far.status)
        assertEquals(-27.0, far.resolved().latitude, 0.0)
        assertEquals(WaypointCheckStatus.NOT_FOUND, WaypointCheck.of("Nowhere", 1.0, 1.0, null, null).status)
    }
}

class AnalysisFactsTest {
    @Test
    fun factsContainCalculatedTotalsAndDates() {
        val acts = listOf(
            act(LocalDate.of(2026, 11, 24), ActivityKind.RUN, distance = 5000.0, ascent = 40.0),
            act(LocalDate.of(2026, 11, 25), ActivityKind.RUN, distance = 7000.0, ascent = null),
        )
        val daily = listOf(DailyMetric(TODAY, steps = 8000, restingHeartRate = 58))
        val text = AnalysisFacts.build(acts, daily, emptyList(), TODAY, ZONE)
        assertTrue(text.contains("this week so far, runs: 2, distance 12.0 km, climb 40 m"))
        assertTrue(text.contains("Longest run in last 28 days\n- 7000 m on 2026-11-25"))
        assertTrue(text.contains("2026-11-26: 8000 / 58 bpm / — / —"))
        assertFalse(text.contains("DEMO"))
    }

    @Test fun pausedStretchesDontCount() {
        val zone = java.time.ZoneOffset.UTC
        val day = java.time.LocalDate.of(2026, 11, 2)
        fun walk(h: Int, km: Double) = ActivityRecord("w$h", ActivityKind.WALK, day.atTime(h, 0).toInstant(zone),
            day.atTime(h, 30).toInstant(zone), km * 1000, null, DataQuality.MISSING, false)
        val base = ChallengeDefinition("c", "Test", ChallengeMetric.DISTANCE, 50_000.0, false, true, day)
        val paused = base.pause(day.atTime(9, 0).toInstant(zone)).resume(day.atTime(12, 0).toInstant(zone))
        val acts = listOf(walk(8, 1.0), walk(10, 2.0), walk(13, 4.0))
        assertEquals(7000.0, ChallengeEngine.progress(base, acts, day, zone).totalMeters, 1e-6)
        assertEquals(5000.0, ChallengeEngine.progress(paused, acts, day, zone).totalMeters, 1e-6)
        assertEquals(false, paused.paused)
        assertEquals(true, base.pause(day.atTime(9, 0).toInstant(zone)).paused)
    }

    @Test fun activitiesBeforeTheStartMomentDontCount() {
        val zone = java.time.ZoneOffset.UTC
        val day = java.time.LocalDate.of(2026, 11, 26)
        fun walk(h: Int, km: Double) = ActivityRecord("w$h", ActivityKind.WALK, day.atTime(h, 0).toInstant(zone),
            day.atTime(h, 30).toInstant(zone), km * 1000, null)
        val c = ChallengeDefinition("c", "Noosa", ChallengeMetric.DISTANCE, 6800.0, false, true, day,
            startedAt = day.atTime(20, 0).toInstant(zone))
        val p = ChallengeEngine.progress(c, listOf(walk(7, 11.1), walk(21, 1.0)), day, zone)
        assertEquals(1000.0, p.totalMeters, 1e-6)
    }
}
