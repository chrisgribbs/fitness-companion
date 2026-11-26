package com.fitcompanion.poc.ai

import kotlinx.coroutines.Dispatchers
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

    data class ModelInfo(val id: String, val name: String, val promptPerToken: Double, val completionPerToken: Double, val contextLength: Int) {
        val free get() = promptPerToken == 0.0 && completionPerToken == 0.0
        /** Rough cost of one coach question in US cents: ~3,000 tokens in (facts + chat) and ~600 out. */
        val centsPerQuestion get() = (promptPerToken * 3000 + completionPerToken * 600) * 100
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
                    ModelInfo(m.getString("id"), m.optString("name").ifBlank { m.getString("id") }, p, o, m.optInt("context_length"))
                }
            } catch (e: java.io.IOException) {
                throw AiError("Couldn't reach OpenRouter to load models (${e.message}).")
            } finally { c.disconnect() }
        }
    }
}
