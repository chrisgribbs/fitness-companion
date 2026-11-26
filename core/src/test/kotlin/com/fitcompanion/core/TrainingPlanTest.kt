package com.fitcompanion.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class TrainingPlanTest {
    private val today = LocalDate.of(2026, 11, 27)          // a Friday
    private val goldCoast = LocalDate.of(2027, 7, 4)        // Sunday

    @Test fun marathonPlanEndsOnRaceWeekWithBaseWeeksFirst() {
        val p = TrainingPlans.build(RaceDistance.MARATHON, PlanLevel.INTERMEDIATE, goldCoast, today)
        val last = p.weeks.last()
        assertEquals("Race week", last.phase)
        assertTrue(last.sessions.any { it.type == SessionType.RACE && it.km == 42.2 })
        assertTrue(!goldCoast.isBefore(last.start) && goldCoast.isBefore(last.start.plusWeeks(1)))
        assertEquals("Base", p.weeks.first().phase)
        assertEquals(18, p.weeks.count { it.phase != "Base" })
        assertTrue(p.notes.any { it.contains("base-building") })
    }

    @Test fun longRunsGrowInSmallStepsAndPeakIsCapped() {
        for (d in RaceDistance.entries) for (l in PlanLevel.entries) {
            val p = TrainingPlans.build(d, l, today.plusWeeks(30), today)
            var longest = 0.0
            p.weeks.filter { it.phase != "Race week" }.forEach { w ->
                if (longest > 0) assertTrue("$d $l week ${w.number}: ${w.longKm} after $longest", w.longKm <= longest + 2.5 + 0.01)
                longest = maxOf(longest, w.longKm)
            }
            assertTrue("$d $l peak long ${p.peakLongKm}", p.peakLongKm <= 35.0)
        }
    }

    @Test fun taperCutsVolume() {
        val p = TrainingPlans.build(RaceDistance.HALF, PlanLevel.INTERMEDIATE, today.plusWeeks(12), today)
        val peak = p.weeks.filter { it.phase == "Peak" }.maxOf { it.km }
        val taper = p.weeks.filter { it.phase == "Taper" }
        assertEquals(2, taper.size)
        assertTrue(taper.all { it.km < peak })
    }

    @Test fun shortNoticeIsFlagged() {
        val p = TrainingPlans.build(RaceDistance.MARATHON, PlanLevel.BEGINNER, today.plusWeeks(6), today)
        assertTrue(p.notes.first().contains("under the usual minimum"))
        assertEquals("Race week", p.weeks.last().phase)
    }

    @Test fun beginner5kIsRunWalkThreeTimesAWeek() {
        val p = TrainingPlans.build(RaceDistance.FIVE_K, PlanLevel.BEGINNER, today.plusWeeks(9), today)
        assertTrue(p.weeks.dropLast(1).all { w -> w.sessions.size == 3 && w.sessions.all { it.type == SessionType.RUN_WALK } })
    }

    @Test fun progressCountsRunsInEachWeek() {
        val zone = ZoneOffset.UTC
        val p = TrainingPlans.build(RaceDistance.TEN_K, PlanLevel.INTERMEDIATE, today.plusWeeks(8), today)
        val monday = p.weeks.first().start
        fun run(day: Long, km: Double) = ActivityRecord("r$day", ActivityKind.RUN, monday.plusDays(day).atTime(7, 0).toInstant(zone),
            monday.plusDays(day).atTime(7, 40).toInstant(zone), km * 1000, null)
        val prog = TrainingPlans.progress(p, listOf(run(4, 5.0), run(6, 8.0), run(8, 6.0)), zone, monday.plusDays(9))
        assertEquals(2, prog.size)
        assertEquals(2, prog[0].runs); assertEquals(13.0, prog[0].km, 1e-6); assertEquals(8.0, prog[0].longestKm, 1e-6)
        assertEquals(1, prog[1].runs)
    }
}
