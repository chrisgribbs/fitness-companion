package com.fitcompanion.poc.ai

import com.fitcompanion.core.ChallengeDraft
import com.fitcompanion.core.DraftMilestone
import com.fitcompanion.core.DraftWaypoint
import org.json.JSONObject

object Prompts {

    val ANALYSIS_SYSTEM = """
        You are a friendly running and walking companion inside a private fitness app.
        You will receive a FACTS SHEET calculated by the app from the user's own Fitbit/Google Health data, then a question.
        Rules:
        - Use only numbers that appear on the facts sheet. Never invent or estimate values that aren't there.
        - When you use a number, say which date or week it is from.
        - If data needed to answer is missing ("—", "n/a", "not available"), say so plainly.
        - Don't present correlation as cause. Use words like "alongside" or "at the same time as", not "because".
        - This is general fitness information, not medical advice. If something looks like a health concern
          (for example a sustained rise in resting heart rate), suggest checking with a doctor rather than diagnosing.
        - Be encouraging and concise: a short answer, then up to 4 bullet points. Plain English, metric units.
    """.trimIndent()

    val CHALLENGE_SYSTEM = """
        You design fun, personal fitness challenges for a walking/running app. The user describes a challenge in
        plain English; you return ONE JSON object and nothing else (no markdown fences, no commentary).

        Two kinds of challenge:
        1. A virtual journey ("metric": "distance"): the user's walking/running distance moves them along a route
           between real places. Give waypoints in travel order, starting at the start and ending at the finish.
           Use real towns, landmarks or beaches the route passes, roughly following the real path a walker would take
           (coastline for coastal walks, major roads or trails for long journeys). Use 6–25 waypoints for short routes
           and up to 50 for very long ones, so straight lines between them follow the real path reasonably closely.
           Give each waypoint's latitude and longitude in decimal degrees as accurately as you can.
        2. A climbing challenge ("metric": "ascent"): total metres climbed, e.g. a famous mountain's height or a
           multiple of it. Give "goal_meters". Add a few fun "milestones" (smaller peaks or landmarks) below the goal.

        Schema:
        {
          "name": string (max 60 chars, fun but clear),
          "description": string (1–2 sentences),
          "metric": "distance" | "ascent",
          "goal_meters": number (for distance, your estimate of the route length; the app re-measures it),
          "include_runs": boolean,
          "include_walks": boolean,
          "waypoints": [ { "name": string, "lat": number, "lon": number } ],   // distance only
          "milestones": [ { "name": string, "at_meters": number } ]           // optional, ascent mainly
        }
        If the user doesn't say runs or walks, include both. Never include personal data.
    """.trimIndent()

    /** Pulls the first JSON object out of a model reply (tolerates code fences or stray text). */
    fun extractJson(reply: String): JSONObject {
        val start = reply.indexOf('{')
        val end = reply.lastIndexOf('}')
        if (start < 0 || end <= start) throw OpenRouterClient.AiError("The AI didn't return a challenge. Try rephrasing.")
        return try { JSONObject(reply.substring(start, end + 1)) } catch (e: Exception) {
            throw OpenRouterClient.AiError("The AI's reply wasn't valid JSON. Try again.")
        }
    }

    fun toDraft(json: JSONObject): ChallengeDraft {
        fun JSONObject.dbl(k: String): Double? = if (has(k) && !isNull(k)) optDouble(k).takeIf { !it.isNaN() } else null
        fun JSONObject.bool(k: String): Boolean? = if (has(k) && !isNull(k)) optBoolean(k) else null
        val wps = json.optJSONArray("waypoints")
        val ms = json.optJSONArray("milestones")
        return ChallengeDraft(
            name = json.optString("name").ifBlank { null },
            metric = json.optString("metric").ifBlank { null },
            goalMeters = json.dbl("goal_meters"),
            includeRuns = json.bool("include_runs"),
            includeWalks = json.bool("include_walks"),
            waypoints = (0 until (wps?.length() ?: 0)).map { i ->
                val w = wps!!.optJSONObject(i) ?: JSONObject()
                DraftWaypoint(w.optString("name").ifBlank { null }, w.dbl("lat"), w.dbl("lon"))
            },
            milestones = (0 until (ms?.length() ?: 0)).map { i ->
                val m = ms!!.optJSONObject(i) ?: JSONObject()
                DraftMilestone(m.optString("name").ifBlank { null }, m.dbl("at_meters"))
            },
            description = json.optString("description").ifBlank { null },
        )
    }
}
