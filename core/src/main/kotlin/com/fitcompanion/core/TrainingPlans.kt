package com.fitcompanion.core

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class RaceDistance(val label: String, val km: Double) {
    FIVE_K("5K", 5.0), TEN_K("10K", 10.0), HALF("Half marathon", 21.1), MARATHON("Marathon", 42.2)
}

enum class PlanLevel(val label: String, val who: String) {
    BEGINNER("Beginner", "New to running, or can run about 20–30 minutes without stopping."),
    INTERMEDIATE("Intermediate", "Running about 3 times a week and have finished this distance (or the one below)."),
    ADVANCED("Advanced", "Running 4–5 times a week and racing for a time."),
}

enum class SessionType(val label: String) { EASY("Easy run"), LONG("Long run"), QUALITY("Quality"), RUN_WALK("Run/walk"), RACE("Race day") }

data class PlannedSession(val type: SessionType, val km: Double, val detail: String)

data class PlanWeek(
    val number: Int,            // 1-based
    val start: LocalDate,       // Monday
    val phase: String,          // Base, Build, Cutback, Peak, Taper, Race week
    val sessions: List<PlannedSession>,
) {
    val km: Double get() = sessions.sumOf { it.km }
    val longKm: Double get() = sessions.filter { it.type == SessionType.LONG || it.type == SessionType.RACE }.maxOfOrNull { it.km } ?: 0.0
}

/** What the user chose. Stored with the challenge; the schedule itself is rebuilt from this in code. */
data class PlanSpec(
    val distance: RaceDistance,
    val level: PlanLevel,
    val eventDate: LocalDate,
    val eventName: String = "",
    val eventUrl: String = "",
    val startDate: LocalDate,
)

data class TrainingPlan(val spec: PlanSpec, val weeks: List<PlanWeek>, val notes: List<String>) {
    val totalKm get() = weeks.sumOf { it.km }
    val peakWeekKm get() = weeks.maxOfOrNull { it.km } ?: 0.0
    val peakLongKm get() = weeks.filter { it.phase != "Race week" }.maxOfOrNull { it.longKm } ?: 0.0
    val runsPerWeek get() = weeks.firstOrNull()?.sessions?.size ?: 0
    fun weekOf(date: LocalDate) = weeks.lastOrNull { !date.isBefore(it.start) }
}

/** How the user is going on one week, from their Fitbit runs. */
data class WeekProgress(val week: PlanWeek, val runs: Int, val km: Double, val longestKm: Double) {
    val sessionsDone get() = min(runs, week.sessions.size)
    val fraction get() = if (week.km <= 0) 0.0 else min(1.0, km / week.km)
}

/**
 * Builds week-by-week plans in code. The shape follows common coaching conventions (see the research library):
 * most running easy, one long run a week, gradual build with a lighter week every 4th week, and a taper
 * (Bosquet et al. 2007: ~2 weeks, volume down ~40–60%, keep some intensity). Long runs grow in small steps
 * (recent cohort data link single-run spikes well above your recent longest run with overuse injury).
 */
object TrainingPlans {

    /** Typical plan length in weeks (Higdon-style conventions: 5K/10K 8, half 12, marathon 18; Couch-to-5K 9). */
    fun recommendedWeeks(d: RaceDistance, l: PlanLevel): Int = when (d) {
        RaceDistance.FIVE_K -> if (l == PlanLevel.BEGINNER) 9 else 8
        RaceDistance.TEN_K -> if (l == PlanLevel.BEGINNER) 10 else 8
        RaceDistance.HALF -> 12
        RaceDistance.MARATHON -> if (l == PlanLevel.ADVANCED) 16 else 18
    }

    fun minimumWeeks(d: RaceDistance): Int = when (d) {
        RaceDistance.FIVE_K -> 4; RaceDistance.TEN_K -> 6; RaceDistance.HALF -> 8; RaceDistance.MARATHON -> 12
    }

    private data class Shape(val runs: Int, val peakWeekKm: Double, val peakLongKm: Double, val startFrac: Double, val longStep: Double, val quality: Int)

    private fun shape(d: RaceDistance, l: PlanLevel): Shape = when (d) {
        RaceDistance.FIVE_K -> when (l) {
            PlanLevel.BEGINNER -> Shape(3, 15.0, 5.0, 0.45, 0.5, 0)
            PlanLevel.INTERMEDIATE -> Shape(4, 25.0, 8.0, 0.6, 1.0, 1)
            PlanLevel.ADVANCED -> Shape(5, 40.0, 12.0, 0.65, 1.0, 2)
        }
        RaceDistance.TEN_K -> when (l) {
            PlanLevel.BEGINNER -> Shape(3, 20.0, 10.0, 0.45, 1.0, 0)
            PlanLevel.INTERMEDIATE -> Shape(4, 32.0, 13.0, 0.6, 1.0, 1)
            PlanLevel.ADVANCED -> Shape(5, 50.0, 16.0, 0.65, 1.5, 2)
        }
        RaceDistance.HALF -> when (l) {
            PlanLevel.BEGINNER -> Shape(3, 32.0, 19.0, 0.45, 1.5, 0)
            PlanLevel.INTERMEDIATE -> Shape(4, 45.0, 21.0, 0.55, 1.5, 1)
            PlanLevel.ADVANCED -> Shape(5, 60.0, 24.0, 0.6, 2.0, 2)
        }
        RaceDistance.MARATHON -> when (l) {
            PlanLevel.BEGINNER -> Shape(4, 50.0, 32.0, 0.45, 2.0, 0)
            PlanLevel.INTERMEDIATE -> Shape(4, 65.0, 32.0, 0.55, 2.0, 1)
            PlanLevel.ADVANCED -> Shape(5, 80.0, 35.0, 0.6, 2.5, 2)
        }
    }

    private fun taperWeeks(d: RaceDistance) = when (d) { RaceDistance.FIVE_K, RaceDistance.TEN_K -> 1; RaceDistance.HALF -> 2; RaceDistance.MARATHON -> 3 }

    private fun r1(x: Double) = (x * 2).roundToInt() / 2.0   // nearest 0.5 km

    /**
     * Plan from [today] to the event. If there's more time than the plan needs, the extra weeks at the start are
     * easy "base" weeks; if there's less, the plan is shortened (and a note says so).
     */
    fun build(distance: RaceDistance, level: PlanLevel, eventDate: LocalDate, today: LocalDate,
              eventName: String = "", eventUrl: String = ""): TrainingPlan {
        val notes = mutableListOf<String>()
        val firstMonday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val raceMonday = eventDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val available = (ChronoUnit.WEEKS.between(firstMonday, raceMonday).toInt() + 1).coerceAtLeast(1)
        val wanted = recommendedWeeks(distance, level)
        val s = shape(distance, level)
        val planWeeks = min(available, wanted)
        val baseWeeks = available - planWeeks
        if (available < minimumWeeks(distance))
            notes += "Only $available week${if (available == 1) "" else "s"} until race day, under the usual minimum of ${minimumWeeks(distance)} for a ${distance.label.lowercase()}. The plan is squeezed; consider a later event or a shorter distance."
        else if (available < wanted) notes += "You have $available weeks; this plan usually runs $wanted, so it starts part-way along."
        if (baseWeeks > 0) notes += "Race day is $available weeks away, so the first $baseWeeks week${if (baseWeeks == 1) " is" else "s are"} relaxed base-building before the $wanted-week plan starts."

        val taper = min(taperWeeks(distance), max(0, planWeeks - 2))
        val buildWeeks = max(1, planWeeks - taper - 1)          // weeks before taper, excluding race week
        val weeks = mutableListOf<PlanWeek>()
        var prevLong = 0.0
        var longestSoFar = 0.0
        val startWeekKm = s.peakWeekKm * s.startFrac
        val startLong = max(if (distance == RaceDistance.FIVE_K && level == PlanLevel.BEGINNER) 2.0 else 3.0, startWeekKm * 0.3)

        for (i in 0 until available) {
            val monday = firstMonday.plusWeeks(i.toLong())
            val isRace = i == available - 1
            val planIdx = i - baseWeeks                              // <0 = base week
            val (phase, weekKmTarget, longTarget) = when {
                isRace -> Triple("Race week", 0.0, distance.km)
                planIdx < 0 -> Triple("Base", startWeekKm * 0.9, startLong)
                planIdx >= buildWeeks -> {
                    val t = planIdx - buildWeeks + 1                  // 1..taper
                    val cut = when (taper) { 1 -> 0.6; 2 -> if (t == 1) 0.75 else 0.5; else -> listOf(0.8, 0.65, 0.45)[min(t - 1, 2)] }
                    Triple("Taper", s.peakWeekKm * cut, s.peakLongKm * cut)
                }
                else -> {
                    val progress = if (buildWeeks <= 1) 1.0 else planIdx.toDouble() / (buildWeeks - 1)
                    val wk = startWeekKm + (s.peakWeekKm - startWeekKm) * progress
                    val lg = startLong + (s.peakLongKm - startLong) * progress
                    val cutback = (planIdx + 1) % 4 == 0 && planIdx < buildWeeks - 1
                    when {
                        cutback -> Triple("Cutback", wk * 0.8, lg * 0.8)
                        planIdx >= buildWeeks - 2 -> Triple("Peak", wk, lg)
                        else -> Triple("Build", wk, lg)
                    }
                }
            }
            // Long run grows in small steps from the longest so far (never a big jump in one go).
            val long = when (phase) {
                "Race week" -> distance.km
                "Taper", "Cutback" -> r1(min(longTarget, longestSoFar))
                else -> r1(min(longTarget, if (longestSoFar == 0.0) longTarget else longestSoFar + s.longStep)).coerceAtLeast(2.0)
            }
            val sessions = if (isRace) raceWeek(distance, s) else week(distance, level, s, phase, max(weekKmTarget, long * 1.6), long, planIdx)
            if (!isRace) { prevLong = long; longestSoFar = max(longestSoFar, long) }
            weeks += PlanWeek(i + 1, monday, phase, sessions)
        }
        if (distance == RaceDistance.MARATHON && level == PlanLevel.BEGINNER)
            notes += "First marathon? A base of comfortably running about 25–30 km a week before you start makes this much easier."
        notes += "Easy runs should feel conversational: you can speak in full sentences."
        return TrainingPlan(PlanSpec(distance, level, eventDate, eventName, eventUrl, today), weeks, notes)
    }

    private fun raceWeek(d: RaceDistance, s: Shape): List<PlannedSession> {
        val easy = r1(min(5.0, s.peakWeekKm * 0.12)).coerceAtLeast(2.0)
        val list = mutableListOf(PlannedSession(SessionType.EASY, easy, "Short and easy, a few relaxed strides at the end"))
        if (s.runs >= 4) list += PlannedSession(SessionType.EASY, r1(easy * 0.8).coerceAtLeast(2.0), "Very easy shake-out, 2–3 days before")
        list += PlannedSession(SessionType.RACE, d.km, "Race day! Start steady and enjoy it")
        return list
    }

    private fun week(d: RaceDistance, l: PlanLevel, s: Shape, phase: String, weekKm: Double, long: Double, planIdx: Int): List<PlannedSession> {
        // Beginner 5K: run/walk sessions that build to continuous running (Couch-to-5K style, 3 a week).
        if (d == RaceDistance.FIVE_K && l == PlanLevel.BEGINNER) {
            val stage = (planIdx + 1).coerceIn(1, 9)
            val detail = when (stage) {
                1 -> "Run 1 min, walk 1½ min × 8 (after a 5-min brisk walk)"
                2 -> "Run 1½ min, walk 2 min × 6"
                3 -> "Run 2 min, walk 2 min × 5"
                4 -> "Run 3 min, walk 1½ min × 5"
                5 -> "Run 5 min, walk 2 min × 3 (one session: 20 min continuous)"
                6 -> "Run 8 min, walk 3 min × 2, then run 5 min (one session: 25 min continuous)"
                7 -> "Run 25 minutes, easy"
                8 -> "Run 28 minutes, easy"
                else -> "Run 30 minutes, easy"
            }
            val km = r1(max(2.0, weekKm / 3))
            return List(3) { PlannedSession(SessionType.RUN_WALK, km, detail) }
        }
        val quality = when {
            phase == "Base" || phase == "Cutback" -> 0
            phase == "Taper" -> min(1, s.quality)
            else -> s.quality
        }
        val others = s.runs - 1
        val remaining = max(0.0, weekKm - long)
        val qKm = r1(max(4.0, remaining / others))
        val list = mutableListOf<PlannedSession>()
        repeat(quality) { q ->
            list += PlannedSession(SessionType.QUALITY, qKm, qualityDetail(d, q, phase))
        }
        val easyCount = others - quality
        val easyKm = r1(max(3.0, (remaining - qKm * quality) / max(1, easyCount)))
        repeat(easyCount) { list += PlannedSession(SessionType.EASY, easyKm, "Conversational pace") }
        list += PlannedSession(SessionType.LONG, long, if (phase == "Taper") "Shorter long run, relaxed" else "Slow and steady; walk breaks are fine")
        return list
    }

    private fun qualityDetail(d: RaceDistance, n: Int, phase: String): String = when {
        phase == "Taper" -> "Short session at race pace to stay sharp"
        n == 0 && (d == RaceDistance.HALF || d == RaceDistance.MARATHON) -> "Tempo: 15–30 min comfortably hard (a few words at a time)"
        n == 0 -> "Intervals: e.g. 6 × 400 m–1 km at 5K–10K effort, easy jog between"
        else -> "Hills or strides: 6–10 × 20–60 s quick uphill, walk down"
    }

    /** Week-by-week progress from the user's runs (and walks, for run/walk plans). */
    fun progress(plan: TrainingPlan, activities: List<ActivityRecord>, zone: ZoneId, today: LocalDate): List<WeekProgress> {
        val runWalk = plan.spec.distance == RaceDistance.FIVE_K && plan.spec.level == PlanLevel.BEGINNER
        val counted = activities.filter {
            (it.kind == ActivityKind.RUN || (runWalk && it.kind == ActivityKind.WALK && java.time.Duration.between(it.start, it.end).toMinutes() >= 15)) &&
                !it.start.atZone(zone).toLocalDate().isBefore(plan.spec.startDate)
        }
        return plan.weeks.filter { !it.start.isAfter(today) }.map { w ->
            val inWeek = counted.filter { a -> val d = a.start.atZone(zone).toLocalDate(); !d.isBefore(w.start) && d.isBefore(w.start.plusWeeks(1)) }
            val kms = inWeek.mapNotNull { it.distanceMeters }.map { it / 1000 }
            WeekProgress(w, inWeek.size, kms.sum(), kms.maxOrNull() ?: 0.0)
        }
    }
}
