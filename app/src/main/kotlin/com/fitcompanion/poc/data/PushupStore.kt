package com.fitcompanion.poc.data

import android.content.Context
import com.fitcompanion.core.CheckStatus
import com.fitcompanion.core.FormCheck
import com.fitcompanion.core.PushupMethod
import com.fitcompanion.core.PushupSet
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant

/** Push-up sets, kept only on this phone (no video or pictures, just the counts and notes). */
class PushupStore(context: Context) {
    private val file = File(context.filesDir, "pushups.json")

    fun load(): List<PushupSet> = runCatching {
        if (!file.exists()) return emptyList()
        val a = JSONArray(file.readText())
        (0 until a.length()).map { i -> a.getJSONObject(i).let { o ->
            PushupSet(Instant.parse(o.getString("start")), o.getInt("reps"), o.getInt("secs"), PushupMethod.valueOf(o.getString("method")),
                o.optJSONArray("notes")?.let { n -> (0 until n.length()).map(n::getString) } ?: emptyList(),
                o.optJSONArray("checks")?.let { c -> (0 until c.length()).mapNotNull { j -> c.getJSONObject(j).let { x -> runCatching {
                    FormCheck(x.getString("id"), CheckStatus.valueOf(x.getString("status")), x.getString("result"), x.optInt("pass"), x.optInt("n")) }.getOrNull() } } }
                    ?: emptyList(),
                if (o.has("clean")) o.getInt("clean") else null)
        } }
    }.getOrDefault(emptyList())

    fun save(all: List<PushupSet>) {
        val tmp = File(file.parentFile, "pushups.json.tmp")
        tmp.writeText(JSONArray(all.map { s -> JSONObject().put("start", s.start.toString()).put("reps", s.reps).put("secs", s.seconds)
            .put("method", s.method.name).put("notes", JSONArray(s.notes))
            .put("checks", JSONArray(s.checks.map { c -> JSONObject().put("id", c.id).put("status", c.status.name).put("result", c.result).put("pass", c.passReps).put("n", c.measuredReps) }))
            .apply { s.cleanReps?.let { put("clean", it) } } }).toString())
        tmp.renameTo(file)
    }

    fun deleteAll() { file.delete() }
}
