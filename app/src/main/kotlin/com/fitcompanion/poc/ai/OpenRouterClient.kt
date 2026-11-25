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
                throw AiError(when (code) {
                    401 -> "OpenRouter rejected the API key. Check it in AI settings."
                    402 -> "Your OpenRouter account is out of credit."
                    429 -> "Rate limited by OpenRouter. Try again in a minute."
                    else -> "OpenRouter error $code: $msg"
                })
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
}
