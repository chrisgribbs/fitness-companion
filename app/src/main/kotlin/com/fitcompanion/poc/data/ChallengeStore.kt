package com.fitcompanion.poc.data

import android.content.Context
import com.fitcompanion.core.ChallengeDefinition
import com.fitcompanion.core.ChallengeMetric
import com.fitcompanion.core.Milestone
import com.fitcompanion.core.Waypoint
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

/** Challenges saved as one JSON file in app-private storage. Activity data is never stored here. */
class ChallengeStore(context: Context) {
    private val file = File(context.filesDir, "challenges.json")

    fun load(): List<ChallengeDefinition> = runCatching {
        if (!file.exists()) return emptyList()
        val arr = JSONArray(file.readText())
        (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    fun save(all: List<ChallengeDefinition>) {
        val tmp = File(file.parentFile, "challenges.json.tmp")
        tmp.writeText(JSONArray(all.map(::toJson)).toString())
        tmp.renameTo(file)
    }

    fun upsert(c: ChallengeDefinition) = save(load().filterNot { it.id == c.id } + c)
    fun delete(id: String) = save(load().filterNot { it.id == id })
    fun deleteAll() { file.delete() }

    private fun toJson(c: ChallengeDefinition) = JSONObject()
        .put("id", c.id).put("name", c.name).put("metric", c.metric.name).put("goal", c.goalMeters)
        .put("runs", c.includeRuns).put("walks", c.includeWalks).put("start", c.startDate.toString())
        .put("description", c.description).put("ai", c.createdByAi).put("preset", c.presetId).put("credit", c.credit).put("startedAt", c.startedAt?.toString() ?: "")
        .put("pauses", org.json.JSONArray().apply { c.pauses.forEach { put(org.json.JSONObject().put("from", it.from.toString()).put("until", it.until?.toString() ?: "")) } })
        .put("route", JSONArray(c.route.map { JSONObject().put("n", it.name).put("lat", it.latitude).put("lon", it.longitude) }))
        .put("milestones", JSONArray(c.milestones.map { JSONObject().put("n", it.name).put("at", it.atMeters) }))

    private fun fromJson(o: JSONObject): ChallengeDefinition {
        val r = o.optJSONArray("route") ?: JSONArray()
        val m = o.optJSONArray("milestones") ?: JSONArray()
        return ChallengeDefinition(
            id = o.getString("id"), name = o.getString("name"), metric = ChallengeMetric.valueOf(o.getString("metric")),
            goalMeters = o.getDouble("goal"), includeRuns = o.getBoolean("runs"), includeWalks = o.getBoolean("walks"),
            startDate = LocalDate.parse(o.getString("start")),
            route = (0 until r.length()).map { r.getJSONObject(it).let { w -> Waypoint(w.getString("n"), w.getDouble("lat"), w.getDouble("lon")) } },
            milestones = (0 until m.length()).map { m.getJSONObject(it).let { x -> Milestone(x.getString("n"), x.getDouble("at")) } },
            description = o.optString("description"), createdByAi = o.optBoolean("ai"),
            presetId = o.optString("preset"), credit = o.optString("credit"),
            startedAt = o.optString("startedAt").takeIf { it.isNotBlank() }?.let(java.time.Instant::parse),
            pauses = o.optJSONArray("pauses")?.let { a -> (0 until a.length()).map { i -> val p = a.getJSONObject(i)
                com.fitcompanion.core.Pause(java.time.Instant.parse(p.getString("from")), p.optString("until").takeIf { it.isNotBlank() }?.let(java.time.Instant::parse)) } } ?: emptyList(),
        )
    }
}
