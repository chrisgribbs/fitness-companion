package com.fitcompanion.poc.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Curated research notes the coach draws on and cites. A copy ships inside the app; a newer copy is fetched
 * (at most daily) from the project's GitHub, so research can be added over time without a new build.
 * The download is a public file: nothing about the user is sent.
 */
class ResearchLibrary(private val context: Context) {

    data class Note(val id: String, val title: String, val tags: List<String>, val finding: String, val use: String,
                    val source: String, val url: String, val type: String)

    private val cache = File(context.filesDir, "research-library.json")
    @Volatile var notes: List<Note> = load(); private set
    val version: String get() = runCatching { JSONObject(text()).optString("updated") }.getOrDefault("")

    private fun text(): String = if (cache.exists()) cache.readText()
        else context.assets.open("research/library.json").bufferedReader().readText()

    private fun load(): List<Note> = runCatching { parse(text()) }.getOrElse {
        parse(context.assets.open("research/library.json").bufferedReader().readText())
    }

    private fun parse(json: String): List<Note> {
        val arr = JSONObject(json).getJSONArray("notes")
        return (0 until arr.length()).map { arr.getJSONObject(it) }.map { o ->
            val t = o.optJSONArray("tags")
            Note(o.getString("id"), o.getString("title"), (0 until (t?.length() ?: 0)).map { t!!.getString(it).lowercase() },
                o.getString("finding"), o.optString("use"), o.getString("source"), o.optString("url"), o.optString("type"))
        }
    }

    /** Fetch the latest library from GitHub if the copy here is more than a day old. */
    suspend fun refresh() = withContext(Dispatchers.IO) {
        if (cache.exists() && System.currentTimeMillis() - cache.lastModified() < 24 * 3600_000L) return@withContext
        runCatching {
            val c = URL(REMOTE).openConnection() as HttpURLConnection
            c.connectTimeout = 10_000; c.readTimeout = 15_000
            if (c.responseCode == 200) {
                val body = c.inputStream.bufferedReader().readText()
                val parsed = parse(body)                     // only keep it if it's valid
                if (parsed.isNotEmpty()) { cache.writeText(body); notes = parsed }
            }
            c.disconnect()
        }
    }

    /** The notes most relevant to a question (by tag matches), best first. */
    fun relevant(question: String, max: Int = 4): List<Note> {
        val q = question.lowercase()
        val scored = notes.map { n -> n to n.tags.count { q.contains(it) } }.filter { it.second > 0 }.sortedByDescending { it.second }
        val picked = scored.take(max).map { it.first }
        // General questions ("how did my week go?") still get the core health guidance.
        return picked.ifEmpty { notes.filter { it.id in setOf("aus-activity-guidelines", "steps-mortality", "sleep-duration") } }
    }

    companion object {
        const val REMOTE = "https://raw.githubusercontent.com/chrisgribbs/fitness-companion/main/app/src/main/assets/research/library.json"
    }
}

/** US→Australian dollar rate for showing OpenRouter costs in A$ (free ECB-based feed, cached daily). */
class ExchangeRate(context: Context) {
    private val prefs = context.getSharedPreferences("fx", Context.MODE_PRIVATE)
    val audPerUsd: Double get() = prefs.getFloat("aud", FALLBACK.toFloat()).toDouble()
    val isLive: Boolean get() = prefs.contains("aud")

    suspend fun refresh() = withContext(Dispatchers.IO) {
        if (System.currentTimeMillis() - prefs.getLong("at", 0) < 24 * 3600_000L) return@withContext
        runCatching {
            val c = URL("https://api.frankfurter.app/latest?from=USD&to=AUD").openConnection() as HttpURLConnection
            c.connectTimeout = 10_000; c.readTimeout = 10_000
            if (c.responseCode == 200) {
                val rate = JSONObject(c.inputStream.bufferedReader().readText()).getJSONObject("rates").getDouble("AUD")
                if (rate in 0.8..3.0) prefs.edit().putFloat("aud", rate.toFloat()).putLong("at", System.currentTimeMillis()).apply()
            }
            c.disconnect()
        }
    }

    companion object { const val FALLBACK = 1.43 }
}
