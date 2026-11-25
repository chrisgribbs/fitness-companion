package com.fitcompanion.core

import java.time.LocalDate

/**
 * What the AI sends back, before any checking. Every field is nullable because model output
 * can't be trusted to be complete or sensible.
 */
data class ChallengeDraft(
    val name: String?,
    val metric: String?,
    val goalMeters: Double?,
    val includeRuns: Boolean?,
    val includeWalks: Boolean?,
    val waypoints: List<DraftWaypoint> = emptyList(),
    val milestones: List<DraftMilestone> = emptyList(),
    val description: String? = null,
)

data class DraftWaypoint(val name: String?, val latitude: Double?, val longitude: Double?)
data class DraftMilestone(val name: String?, val atMeters: Double?)

sealed interface ValidationResult {
    data class Ok(val definition: ChallengeDefinition, val warnings: List<String>) : ValidationResult
    data class Invalid(val errors: List<String>) : ValidationResult
}

/**
 * Turns an AI draft into a safe challenge. The app — not the AI — measures the route, so the goal
 * distance is always the real straight-line length between the waypoints.
 */
object ChallengeValidator {
    const val MAX_WAYPOINTS = 60
    const val MAX_MILESTONES = 30
    private const val MAX_NAME = 60

    fun validate(draft: ChallengeDraft, id: String, startDate: LocalDate): ValidationResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        val name = draft.name?.trim().orEmpty()
        if (name.isEmpty()) errors += "The challenge needs a name."
        val finalName = if (name.length > MAX_NAME) name.take(MAX_NAME).trimEnd().also { warnings += "Name shortened to $MAX_NAME characters." } else name

        val metric = when (draft.metric?.trim()?.lowercase()) {
            "distance" -> ChallengeMetric.DISTANCE
            "ascent", "elevation", "climb" -> ChallengeMetric.ASCENT
            else -> { errors += "Metric must be 'distance' or 'ascent' (got '${draft.metric}')."; null }
        }

        var runs = draft.includeRuns
        var walks = draft.includeWalks
        if (runs == null && walks == null) { runs = true; walks = true; warnings += "Runs and walks both count (not specified)." }
        if (runs != true && walks != true) errors += "At least one of runs or walks must count."

        var route = emptyList<Waypoint>()
        var goal = 0.0
        if (metric == ChallengeMetric.DISTANCE) {
            val cleaned = mutableListOf<Waypoint>()
            draft.waypoints.forEachIndexed { i, w ->
                val lat = w.latitude; val lon = w.longitude; val n = w.name?.trim().orEmpty()
                when {
                    n.isEmpty() -> errors += "Waypoint ${i + 1} has no name."
                    lat == null || lon == null || lat !in -90.0..90.0 || lon !in -180.0..180.0 ->
                        errors += "Waypoint '$n' has invalid coordinates."
                    cleaned.lastOrNull()?.let { it.latitude == lat && it.longitude == lon } == true ->
                        warnings += "Duplicate waypoint '$n' removed."
                    else -> cleaned += Waypoint(n, lat, lon)
                }
            }
            if (cleaned.size < 2) errors += "A journey needs at least 2 valid waypoints."
            if (cleaned.size > MAX_WAYPOINTS) errors += "Too many waypoints (${cleaned.size}); maximum is $MAX_WAYPOINTS."
            if (cleaned.size in 2..MAX_WAYPOINTS) {
                route = cleaned
                goal = JourneyRoute(cleaned).totalMeters
                if (goal < 100) errors += "The route is shorter than 100 m."
                if (goal > 40_075_000) errors += "The route is longer than the Earth's circumference."
                draft.goalMeters?.let { claimed ->
                    if (claimed > 0 && kotlin.math.abs(claimed - goal) / goal > 0.10) {
                        warnings += "AI said %.0f km; the route measures %.0f km in straight lines between waypoints. Using %.0f km."
                            .format(claimed / 1000, goal / 1000, goal / 1000)
                    }
                }
            }
        } else if (metric == ChallengeMetric.ASCENT) {
            val g = draft.goalMeters
            if (g == null || g < 10 || g > 100_000) errors += "A climbing goal must be between 10 m and 100,000 m."
            else goal = g
            if (draft.waypoints.isNotEmpty()) warnings += "Waypoints ignored for a climbing challenge."
        }

        val milestones = mutableListOf<Milestone>()
        if (goal > 0) {
            if (route.isNotEmpty()) milestones += JourneyRoute(route).waypointMilestones()
            for (m in draft.milestones) {
                val n = m.name?.trim().orEmpty(); val at = m.atMeters
                if (n.isEmpty() || at == null || at <= 0 || at > goal) {
                    warnings += "Milestone '${n.ifEmpty { "?" }}' dropped (outside the goal)."
                } else milestones += Milestone(n, at)
            }
        }
        val finalMilestones = milestones.distinctBy { it.name to it.atMeters }.sortedBy { it.atMeters }
        if (finalMilestones.size > MAX_MILESTONES) warnings += "Only the first $MAX_MILESTONES milestones kept."

        if (errors.isNotEmpty() || metric == null) return ValidationResult.Invalid(errors)
        return ValidationResult.Ok(
            ChallengeDefinition(
                id = id, name = finalName, metric = metric, goalMeters = goal,
                includeRuns = runs == true, includeWalks = walks == true, startDate = startDate,
                route = route, milestones = finalMilestones.take(MAX_MILESTONES),
                description = draft.description?.trim()?.take(500).orEmpty(), createdByAi = true,
            ),
            warnings,
        )
    }
}

/** Result of checking an AI-suggested waypoint against a real place lookup. */
enum class WaypointCheckStatus { VERIFIED, MOVED, NOT_FOUND }

data class WaypointCheck(
    val name: String,
    val aiLatitude: Double,
    val aiLongitude: Double,
    val foundLatitude: Double?,
    val foundLongitude: Double?,
    val offsetKm: Double?,
    val status: WaypointCheckStatus,
) {
    /** The coordinates to use: the looked-up place when the AI was off, otherwise the AI's. */
    fun resolved(): Waypoint = if (status == WaypointCheckStatus.MOVED && foundLatitude != null && foundLongitude != null)
        Waypoint(name, foundLatitude, foundLongitude) else Waypoint(name, aiLatitude, aiLongitude)

    companion object {
        fun of(name: String, aiLat: Double, aiLon: Double, foundLat: Double?, foundLon: Double?, toleranceKm: Double = 20.0): WaypointCheck {
            if (foundLat == null || foundLon == null) return WaypointCheck(name, aiLat, aiLon, null, null, null, WaypointCheckStatus.NOT_FOUND)
            val km = Geo.haversineMeters(aiLat, aiLon, foundLat, foundLon) / 1000
            return WaypointCheck(name, aiLat, aiLon, foundLat, foundLon, km,
                if (km <= toleranceKm) WaypointCheckStatus.VERIFIED else WaypointCheckStatus.MOVED)
        }
    }
}
