package com.fitcompanion.poc.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal OpenRouter chat-completions client. It sends exactly the messages it is given — the
 * callers show the user that text before calling [chat]. Nothing is sent in the background.
 */
class OpenRouterClient(private val apiKey: String, private val model: String) {

    class AiError(message: String) : Exception(message)

    data class Message(val role: String, val content: String)

    data class Reply(val text: String, val model: String?)

    suspend fun chat(messages: List<Message>, temperature: Double = 0.3, maxTokens: Int = 1500): Reply = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("model", model)
            .put("temperature", temperature)
            .put("max_tokens", maxTokens)
            .put("messages", JSONArray(messages.map { JSONObject().put("role", it.role).put("content", it.content) }))

        val c = URL("https://openrouter.ai/api/v1/chat/completions").openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 15_000
            c.readTimeout = 90_000
            c.doOutput = true
            c.setRequestProperty("Authorization", "Bearer $apiKey")
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.use { String(it.readBytes()) } ?: ""
            if (code !in 200..299) {
                val msg = runCatching { JSONObject(text).getJSONObject("error").getString("message") }.getOrNull() ?: text.take(200)
                val unusable = code == 404 || (code == 400 && msg.contains("cannot be used", true)) || msg.contains("No endpoints found", true)
                throw AiError(when {
                    unusable -> "The model \"$model\" can't be used for chat right now. Tap the model bar above to pick another one."
                    else -> when (code) {
                    401 -> "OpenRouter rejected the API key. Check it in AI settings."
                    402 -> "Your OpenRouter account is out of credit."
                    429 -> "Rate limited by OpenRouter. Try again in a minute."
                    else -> "OpenRouter error $code: $msg"
                }})
            }
            val json = JSONObject(text)
            val choice = json.getJSONArray("choices").getJSONObject(0)
            Reply(choice.getJSONObject("message").optString("content"), json.optString("model").ifBlank { null })
        } catch (e: java.io.IOException) {
            throw AiError("Couldn't reach OpenRouter (${e.message}). Check your internet connection.")
        } finally {
            c.disconnect()
        }
    }

    /** Result of a streamed answer, with what it actually cost (from OpenRouter's usage report). */
    data class StreamResult(
        val text: String, val reasoning: String, val model: String?, val finishReason: String?,
        val costUsd: Double?, val promptTokens: Int, val completionTokens: Int, val reasoningTokens: Int,
    )

    /**
     * Streams an answer. [onReasoning] / [onText] get each new piece as it arrives (on a background thread).
     * Reasoning is kept low-effort so it doesn't eat the answer's token budget.
     */
    suspend fun stream(
        messages: List<Message>, temperature: Double = 0.6, maxTokens: Int = 4000,
        webSearch: Boolean = false, onReasoning: (String) -> Unit = {}, onText: (String) -> Unit = {},
    ): StreamResult = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("model", model).put("temperature", temperature).put("max_tokens", maxTokens).put("stream", true)
            .put("messages", JSONArray(messages.map { JSONObject().put("role", it.role).put("content", it.content) }))
            .put("reasoning", JSONObject().put("effort", "low"))
            .put("usage", JSONObject().put("include", true))
            .put("stream_options", JSONObject().put("include_usage", true))
        if (webSearch) body.put("plugins", JSONArray().put(JSONObject().put("id", "web").put("max_results", 5)))
        val c = URL("https://openrouter.ai/api/v1/chat/completions").openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"; c.connectTimeout = 20_000; c.readTimeout = 120_000; c.doOutput = true
            c.setRequestProperty("Authorization", "Bearer $apiKey")
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("Accept", "text/event-stream")
            c.setRequestProperty("X-Title", "Fitness Companion")
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = c.responseCode
            if (code !in 200..299) {
                val text = c.errorStream?.use { String(it.readBytes()) } ?: ""
                throw errorFor(code, runCatching { JSONObject(text).getJSONObject("error").getString("message") }.getOrNull() ?: text.take(200))
            }
            val text = StringBuilder(); val reasoning = StringBuilder()
            var modelUsed: String? = null; var finish: String? = null
            var cost: Double? = null; var pt = 0; var ct = 0; var rt = 0
            c.inputStream.bufferedReader().useLines { lines ->
                for (raw in lines) {
                    kotlin.coroutines.coroutineContext.ensureActive()
                    val line = raw.trim()
                    if (!line.startsWith("data:")) continue          // ": OPENROUTER PROCESSING" keep-alives etc.
                    val data = line.removePrefix("data:").trim()
                    if (data == "[DONE]") break
                    val j = runCatching { JSONObject(data) }.getOrNull() ?: continue
                    j.optJSONObject("error")?.let { throw AiError("OpenRouter: " + it.optString("message")) }
                    j.optString("model").takeIf { it.isNotBlank() }?.let { modelUsed = it }
                    j.optJSONArray("choices")?.optJSONObject(0)?.let { ch ->
                        ch.optJSONObject("delta")?.let { d ->
                            d.optString("reasoning").takeIf { it.isNotEmpty() && it != "null" }?.let { reasoning.append(it); onReasoning(it) }
                            d.optString("content").takeIf { it.isNotEmpty() && it != "null" }?.let { text.append(it); onText(it) }
                        }
                        ch.optString("finish_reason").takeIf { it.isNotBlank() && it != "null" }?.let { finish = it }
                    }
                    j.optJSONObject("usage")?.let { u ->
                        if (u.has("cost")) cost = u.optDouble("cost")
                        pt = u.optInt("prompt_tokens", pt); ct = u.optInt("completion_tokens", ct)
                        rt = u.optJSONObject("completion_tokens_details")?.optInt("reasoning_tokens", rt) ?: rt
                    }
                }
            }
            StreamResult(text.toString(), reasoning.toString(), modelUsed, finish, cost, pt, ct, rt)
        } catch (e: java.io.IOException) {
            throw AiError("Lost the connection to OpenRouter (${e.message}). Check your internet and tap Retry.")
        } finally { c.disconnect() }
    }

    private fun errorFor(code: Int, msg: String): AiError {
        val unusable = code == 404 || (code == 400 && msg.contains("cannot be used", true)) || msg.contains("No endpoints found", true)
        return AiError(when {
            unusable -> "The model \"$model\" can't be used for chat right now. Tap the model name at the top to pick another one."
            code == 401 -> "OpenRouter didn't accept your sign-in. Sign in again from the AI account screen."
            code == 402 -> "Your OpenRouter account is out of credit. Top up at openrouter.ai or pick a free model."
            code == 429 -> "OpenRouter is busy (rate limited). Try again in a minute, or pick another model."
            else -> "OpenRouter error $code: $msg"
        })
    }

    data class ModelInfo(val id: String, val name: String, val promptPerToken: Double, val completionPerToken: Double,
                         val contextLength: Int, val reasons: Boolean = false) {
        val free get() = promptPerToken == 0.0 && completionPerToken == 0.0
        /** Estimated US dollars for one question, from typical (or your own measured) token use. */
        fun usdPerQuestion(inTokens: Int, outTokens: Int) =
            promptPerToken * inTokens + completionPerToken * (outTokens + if (reasons) 800 else 0)
    }

    companion object {
        /** Batch-only variants can't answer chat requests, so they're hidden and never kept as the chosen model. */
        fun usableForChat(id: String) = !id.contains(":batch", ignoreCase = true)

        /** OpenRouter's public model list (no key needed). */
        suspend fun listModels(): List<ModelInfo> = withContext(Dispatchers.IO) {
            val c = URL("https://openrouter.ai/api/v1/models").openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 15_000; c.readTimeout = 30_000
                val code = c.responseCode
                if (code !in 200..299) throw AiError("Couldn't load the model list ($code).")
                val data = JSONObject(c.inputStream.use { String(it.readBytes()) }).getJSONArray("data")
                (0 until data.length()).map { data.getJSONObject(it) }.mapNotNull { m ->
                    if (!usableForChat(m.getString("id"))) return@mapNotNull null
                    val pricing = m.optJSONObject("pricing") ?: return@mapNotNull null
                    val inputs = m.optJSONObject("architecture")?.optJSONArray("input_modalities")
                    val outputs = m.optJSONObject("architecture")?.optJSONArray("output_modalities")
                    // Text chat models only.
                    if (outputs != null && (0 until outputs.length()).none { outputs.getString(it) == "text" }) return@mapNotNull null
                    if (inputs != null && (0 until inputs.length()).none { inputs.getString(it) == "text" }) return@mapNotNull null
                    val p = pricing.optString("prompt").toDoubleOrNull() ?: return@mapNotNull null
                    val o = pricing.optString("completion").toDoubleOrNull() ?: return@mapNotNull null
                    if (p < 0 || o < 0) return@mapNotNull null   // "auto"/router entries use -1
                    val params = m.optJSONArray("supported_parameters")
                    val reasons = params != null && (0 until params.length()).any { params.getString(it) == "reasoning" }
                    ModelInfo(m.getString("id"), m.optString("name").ifBlank { m.getString("id") }, p, o, m.optInt("context_length"), reasons)
                }
            } catch (e: java.io.IOException) {
                throw AiError("Couldn't reach OpenRouter to load models (${e.message}).")
            } finally { c.disconnect() }
        }
    }
}
