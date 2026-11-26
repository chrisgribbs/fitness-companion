package com.fitcompanion.poc.data

import android.content.Context
import com.fitcompanion.core.ChallengeDefinition
import com.fitcompanion.core.ChallengeMetric
import com.fitcompanion.core.JourneyRoute
import com.fitcompanion.core.Milestone
import com.fitcompanion.core.Waypoint
import org.json.JSONObject
import java.time.LocalDate
import java.util.UUID

/**
 * Built-in walks (assets/walks/*.json), generated from OpenStreetMap by tools/walks/fetch_walks.py.
 * Each holds the real walking path and its landmarks with their distance along the path.
 */
class WalkPresets(private val context: Context) {

    data class Preset(
        val id: String,
        val name: String,
        val description: String,
        val officialKm: Double,
        val officialSource: String,
        val path: List<Waypoint>,
        val milestones: List<Milestone>,
        val credit: String,
    ) {
        val routeMeters: Double get() = JourneyRoute(path).totalMeters
    }

    fun all(): List<Preset> = runCatching {
        context.assets.list("walks").orEmpty().filter { it.endsWith(".json") }.sorted().mapNotNull { f ->
            runCatching { parse(JSONObject(context.assets.open("walks/$f").bufferedReader().readText())) }.getOrNull()
        }
    }.getOrDefault(emptyList())

    private fun parse(o: JSONObject): Preset {
        val pathArr = o.getJSONArray("path")
        val ms = o.getJSONArray("milestones")
        val path = (0 until pathArr.length()).map { i ->
            val p = pathArr.getJSONArray(i)
            val name = if (i == 0) ms.getJSONObject(0).getString("name") else if (i == pathArr.length() - 1) ms.getJSONObject(ms.length() - 1).getString("name") else ""
            Waypoint(name, p.getDouble(0), p.getDouble(1))
        }
        // Milestones were measured along the router's full path; rescale to the simplified path.
        val measured = JourneyRoute(path).totalMeters
        val routed = o.optDouble("routedMeters", measured).takeIf { it > 0 } ?: measured
        val scale = measured / routed
        val milestones = (1 until ms.length()).map { i ->
            val m = ms.getJSONObject(i)
            Milestone(m.getString("name"), (m.getDouble("atMeters") * scale).coerceAtMost(measured))
        }
        return Preset(
            id = o.getString("id"), name = o.getString("name"), description = o.getString("description"),
            officialKm = o.optDouble("officialKm"), officialSource = o.optString("officialSource"),
            path = path, milestones = milestones, credit = o.optString("attribution"),
        )
    }

    fun toChallenge(p: Preset, runs: Boolean, walks: Boolean, start: LocalDate) = ChallengeDefinition(
        id = UUID.randomUUID().toString(), name = p.name, metric = ChallengeMetric.DISTANCE,
        goalMeters = p.routeMeters, includeRuns = runs, includeWalks = walks, startDate = start,
        route = p.path, milestones = p.milestones, description = p.description,
        presetId = p.id, credit = p.credit,
    )
}
