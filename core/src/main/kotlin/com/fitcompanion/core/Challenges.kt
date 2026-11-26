package com.fitcompanion.core

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import kotlin.math.ceil

enum class ChallengeMetric { DISTANCE, ASCENT }

data class Waypoint(val name: String, val latitude: Double, val longitude: Double)

data class Milestone(val name: String, val atMeters: Double)

/** A paused stretch of a challenge: activities starting in [from, until) don't count. until == null: still paused. */
data class Pause(val from: java.time.Instant, val until: java.time.Instant? = null) {
    fun covers(t: java.time.Instant) = !t.isBefore(from) && (until == null || t.isBefore(until))
}

/**
 * A challenge is just a target plus rules for which activities count. Challenges never change
 * activity records; resetting one only moves [startDate].
 */
data class ChallengeDefinition(
    val id: String,
    val name: String,
    val metric: ChallengeMetric,
    val goalMeters: Double,
    val includeRuns: Boolean,
    val includeWalks: Boolean,
    val startDate: LocalDate,
    /** For virtual journeys: the route, in order. Empty for climbing challenges. */
    val route: List<Waypoint> = emptyList(),
    val milestones: List<Milestone> = emptyList(),
    val description: String = "",
    val createdByAi: Boolean = false,
    /** Set for built-in walks, e.g. "noosa-coastal-walk". */
    val presetId: String = "",
    /** Data credit shown under the map, e.g. OpenStreetMap attribution. */
    val credit: String = "",
    /** Times this challenge was paused; activities in a pause don't count toward it. */
    val pauses: List<Pause> = emptyList(),
    /** Exact moment the challenge was started or restarted; activities that began earlier don't count. Null: whole start day counts. */
    val startedAt: java.time.Instant? = null,
) {
    val paused: Boolean get() = pauses.any { it.until == null }
    fun isPausedAt(t: java.time.Instant) = pauses.any { it.covers(t) }
    fun pause(now: java.time.Instant) = if (paused) this else copy(pauses = pauses + Pause(now))
    fun resume(now: java.time.Instant) = copy(pauses = pauses.map { if (it.until == null) it.copy(until = now) else it })

    init {
        require(name.isNotBlank()) { "name required" }
        require(goalMeters > 0) { "goal must be positive" }
        require(includeRuns || includeWalks) { "include runs, walks or both" }
        require(metric != ChallengeMetric.DISTANCE || route.isEmpty() || route.size >= 2) { "a route needs 2+ waypoints" }
    }

    fun counts(kind: ActivityKind) = when (kind) {
        ActivityKind.RUN -> includeRuns
        ActivityKind.WALK -> includeWalks
        ActivityKind.OTHER -> false
    }
}

object Everest {
    const val HEIGHT_METERS = 8849.0

    /** Familiar heights to pass on the way up. Heights rounded to the metre. */
    val LANDMARKS = listOf(
        Milestone("Mt Coot-tha, Brisbane", 287.0),
        Milestone("Mt Kosciuszko", 2228.0),
        Milestone("Mont Blanc", 4806.0),
        Milestone("Everest Base Camp (south)", 5364.0),
        Milestone("Kilimanjaro", 5895.0),
        Milestone("Summit of Everest", HEIGHT_METERS),
    )

    fun definition(id: String, startDate: LocalDate, includeRuns: Boolean = true, includeWalks: Boolean = true) =
        ChallengeDefinition(
            id = id, name = "Climb Everest", metric = ChallengeMetric.ASCENT, goalMeters = HEIGHT_METERS,
            includeRuns = includeRuns, includeWalks = includeWalks, startDate = startDate,
            milestones = LANDMARKS, description = "Accumulate 8,849 m of climbing — the height of Mount Everest.",
        )
}

data class PeriodTotal(val start: LocalDate, val meters: Double)

/** Always shown in the UI with the word "estimate". */
data class CompletionEstimate(val date: LocalDate, val metersPerDay: Double, val basisDays: Int)

data class ChallengeProgress(
    val totalMeters: Double,
    val goalMeters: Double,
    /** 0.0 – 1.0 */
    val fraction: Double,
    val remainingMeters: Double,
    val completedOn: LocalDate?,
    val thisWeekMeters: Double,
    val thisMonthMeters: Double,
    /** Last 8 weeks (Monday starts), oldest first. */
    val weekly: List<PeriodTotal>,
    /** Last 6 months, oldest first. */
    val monthly: List<PeriodTotal>,
    val activitiesCounted: Int,
    /** Activities that match the challenge but have no value for its metric. */
    val activitiesMissingData: Int,
    val reached: List<Milestone>,
    val next: Milestone?,
    val estimate: CompletionEstimate?,
    val includesDemoData: Boolean,
) {
    val completed get() = completedOn != null
    val percent get() = fraction * 100
}

object ChallengeEngine {

    fun progress(def: ChallengeDefinition, activities: List<ActivityRecord>, today: LocalDate, zone: ZoneId): ChallengeProgress {
        fun dateOf(a: ActivityRecord) = a.start.atZone(zone).toLocalDate()
        fun valueOf(a: ActivityRecord) = when (def.metric) {
            ChallengeMetric.DISTANCE -> a.distanceMeters
            ChallengeMetric.ASCENT -> a.ascentMeters
        }

        val matching = activities
            .filter { def.counts(it.kind) && !dateOf(it).isBefore(def.startDate) && !dateOf(it).isAfter(today) && !def.isPausedAt(it.start)
                && (def.startedAt == null || !it.start.isBefore(def.startedAt)) }
            .sortedBy { it.start }
        val withValue = matching.filter { valueOf(it) != null }
        val total = withValue.sumOf { valueOf(it)!! }

        var running = 0.0
        var completedOn: LocalDate? = null
        for (a in withValue) {
            running += valueOf(a)!!
            if (completedOn == null && running >= def.goalMeters) completedOn = dateOf(a)
        }

        fun sumBetween(from: LocalDate, toExclusive: LocalDate) =
            withValue.filter { val d = dateOf(it); !d.isBefore(from) && d.isBefore(toExclusive) }.sumOf { valueOf(it)!! }

        val weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val monthStart = today.withDayOfMonth(1)
        val weekly = (7 downTo 0).map { i -> val s = weekStart.minusWeeks(i.toLong()); PeriodTotal(s, sumBetween(s, s.plusWeeks(1))) }
        val monthly = (5 downTo 0).map { i -> val s = monthStart.minusMonths(i.toLong()); PeriodTotal(s, sumBetween(s, s.plusMonths(1))) }

        val milestones = def.milestones.sortedBy { it.atMeters }
        val remaining = (def.goalMeters - total).coerceAtLeast(0.0)

        return ChallengeProgress(
            totalMeters = total,
            goalMeters = def.goalMeters,
            fraction = (total / def.goalMeters).coerceIn(0.0, 1.0),
            remainingMeters = remaining,
            completedOn = completedOn,
            thisWeekMeters = sumBetween(weekStart, today.plusDays(1)),
            thisMonthMeters = sumBetween(monthStart, today.plusDays(1)),
            weekly = weekly,
            monthly = monthly,
            activitiesCounted = withValue.size,
            activitiesMissingData = matching.size - withValue.size,
            reached = milestones.filter { it.atMeters <= total },
            next = milestones.firstOrNull { it.atMeters > total },
            estimate = if (completedOn != null) null else estimate(def, remaining, today) { from -> sumBetween(from, today.plusDays(1)) },
            includesDemoData = matching.any { it.isDemo },
        )
    }

    /**
     * Projects a finish date from the recent daily average (last 28 days, or since the start if
     * shorter, minimum 7 days so one big day doesn't dominate). Null when there's no recent activity.
     */
    private fun estimate(def: ChallengeDefinition, remaining: Double, today: LocalDate, sumFrom: (LocalDate) -> Double): CompletionEstimate? {
        val daysSinceStart = ChronoUnit.DAYS.between(def.startDate, today).toInt() + 1
        val basis = daysSinceStart.coerceIn(7, 28)
        val recent = sumFrom(today.minusDays((basis - 1).toLong()))
        if (recent <= 0) return null
        val perDay = recent / basis
        return CompletionEstimate(today.plusDays(ceil(remaining / perDay).toLong()), perDay, basis)
    }
}

/** Geometry of a virtual journey: straight lines between waypoints, in order. */
class JourneyRoute(val waypoints: List<Waypoint>) {
    init { require(waypoints.size >= 2) { "a route needs at least 2 waypoints" } }

    /** Cumulative distance at each waypoint; first is 0. */
    val cumulativeMeters: List<Double> = waypoints.zipWithNext { a, b ->
        Geo.haversineMeters(a.latitude, a.longitude, b.latitude, b.longitude)
    }.runningFold(0.0) { acc, d -> acc + d }

    val totalMeters: Double get() = cumulativeMeters.last()

    data class Position(
        val latitude: Double,
        val longitude: Double,
        /** Index of the waypoint most recently passed. */
        val lastPassedIndex: Int,
        val metersToNextWaypoint: Double,
        val finished: Boolean,
    )

    fun positionAt(meters: Double): Position {
        val d = meters.coerceIn(0.0, totalMeters)
        if (d >= totalMeters) {
            val w = waypoints.last()
            return Position(w.latitude, w.longitude, waypoints.lastIndex, 0.0, finished = true)
        }
        val leg = cumulativeMeters.indexOfLast { it <= d }.coerceAtMost(waypoints.lastIndex - 1)
        val legStart = cumulativeMeters[leg]
        val legLen = cumulativeMeters[leg + 1] - legStart
        val f = if (legLen <= 0) 0.0 else (d - legStart) / legLen
        val a = waypoints[leg]; val b = waypoints[leg + 1]
        return Position(
            latitude = a.latitude + f * (b.latitude - a.latitude),
            longitude = a.longitude + f * (b.longitude - a.longitude),
            lastPassedIndex = leg,
            metersToNextWaypoint = cumulativeMeters[leg + 1] - d,
            finished = false,
        )
    }

    /** Points of the route already travelled, ending at the current position (for drawing). */
    fun travelledPath(meters: Double): List<Pair<Double, Double>> {
        val p = positionAt(meters)
        return waypoints.take(p.lastPassedIndex + 1).map { it.latitude to it.longitude } +
            if (p.finished) emptyList() else listOf(p.latitude to p.longitude)
    }

    /** Each named waypoint after the start becomes a milestone at its distance along the route. */
    fun waypointMilestones(): List<Milestone> =
        waypoints.indices.drop(1).filter { waypoints[it].name.isNotBlank() }.map { Milestone(waypoints[it].name, cumulativeMeters[it]) }

    /** Where a milestone sits on the map (milestones are stored as distances along the route). */
    fun pointAt(meters: Double): Pair<Double, Double> = positionAt(meters).let { it.latitude to it.longitude }
}
