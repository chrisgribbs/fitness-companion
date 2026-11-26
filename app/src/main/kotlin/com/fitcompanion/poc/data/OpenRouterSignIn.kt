package com.fitcompanion.poc.data

import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * "Sign in with OpenRouter" using OAuth PKCE (https://openrouter.ai/docs/guides/overview/auth/oauth).
 *
 * 1. A tiny one-off web server listens on this phone only (127.0.0.1, random port).
 * 2. The browser opens OpenRouter's sign-in page; the user signs in and approves.
 * 3. OpenRouter sends the browser back to http://localhost:PORT/callback?code=…
 * 4. The app swaps that code plus its private code_verifier for the user's own API key.
 *
 * The app never sees the user's OpenRouter password, and the code is useless without the verifier,
 * which never leaves the phone. No keys or secrets are built into the app.
 */
class OpenRouterSignIn {

    /**
     * Runs the whole flow. [openBrowser] is called with the sign-in URL. [onApproved] is told once the
     * approval has arrived; [awaitForeground] suspends until the app is back on screen, because many
     * phones block internet for apps in the background. Returns the user's API key.
     */
    suspend fun signIn(openBrowser: (Uri) -> Unit, onApproved: () -> Unit, awaitForeground: suspend () -> Unit): String = withContext(Dispatchers.IO) {
        val verifier = randomUrlSafe(48)
        val challenge = b64url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
        val state = randomUrlSafe(16)

        ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 1000
            val callback = "http://localhost:${server.localPort}/callback"
            val url = Uri.parse("https://openrouter.ai/auth").buildUpon()
                .appendQueryParameter("callback_url", callback)
                .appendQueryParameter("code_challenge", challenge)
                .appendQueryParameter("code_challenge_method", "S256")
                .appendQueryParameter("state", state)
                .appendQueryParameter("key_label", "Fitness Companion (Android)")
                .build()
            withContext(Dispatchers.Main) { openBrowser(url) }

            val code = withTimeout(10 * 60 * 1000L) { waitForCode(server, state) }
            withContext(Dispatchers.Main) { onApproved() }
            awaitForeground()
            // The code lasts 10 minutes; retry briefly in case the network is still waking up.
            var last: Exception? = null
            repeat(5) { attempt ->
                try { return@withContext exchange(code, verifier) }
                catch (e: java.io.IOException) { last = e; kotlinx.coroutines.delay(1500L * (attempt + 1)) }
            }
            throw IllegalStateException("Couldn't reach OpenRouter to finish signing in. Check your internet connection and try again.", last)
        }
    }

    private suspend fun waitForCode(server: ServerSocket, state: String): String {
        while (true) {
            kotlin.coroutines.coroutineContext.ensureActive()
            val socket = try { server.accept() } catch (_: SocketTimeoutException) { continue }
            socket.use { s ->
                s.soTimeout = 5000
                val requestLine = s.getInputStream().bufferedReader().readLine().orEmpty()   // "GET /callback?code=… HTTP/1.1"
                val target = requestLine.split(" ").getOrNull(1).orEmpty()
                if (!target.startsWith("/callback")) {
                    respond(s, 404, "<p>Not found</p>"); return@use
                }
                val params = target.substringAfter('?', "").split('&').filter { it.contains('=') }
                    .associate { URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
                val code = params["code"]
                val returnedState = params["state"]
                when {
                    code.isNullOrBlank() -> {
                        respond(s, 200, page("Sign-in cancelled", "Nothing was connected. You can go back to Fitness Companion."))
                        error("Sign-in was cancelled.")
                    }
                    returnedState != null && returnedState != state -> {
                        respond(s, 400, page("Sign-in didn't match", "Please go back to the app and try again."))
                        error("Sign-in response didn't match this request. Try again.")
                    }
                    else -> {
                        respond(s, 200, page("Signed in", "Taking you back to Fitness Companion…"))
                        return code
                    }
                }
            }
        }
    }

    private fun exchange(code: String, verifier: String): String {
        val conn = URL("https://openrouter.ai/api/v1/auth/keys").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 20_000; conn.readTimeout = 30_000
        conn.setRequestProperty("Content-Type", "application/json")
        val body = JSONObject().put("code", code).put("code_verifier", verifier).put("code_challenge_method", "S256")
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        val status = conn.responseCode
        val text = (if (status in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText().orEmpty()
        if (status !in 200..299) error(when (status) {
            403 -> "OpenRouter didn't accept the sign-in (it may have expired). Try again."
            else -> "OpenRouter sign-in failed ($status)."
        })
        return JSONObject(text).optString("key").ifBlank { error("OpenRouter didn't return a key.") }
    }

    private fun respond(s: java.net.Socket, status: Int, html: String) {
        val bytes = html.toByteArray()
        val head = "HTTP/1.1 $status OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        s.getOutputStream().apply { write(head.toByteArray()); write(bytes); flush() }
    }

    /** Small page shown in the browser; it hands the user straight back to the app. */
    private fun page(title: String, message: String) = """
        <!DOCTYPE html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
        <title>$title</title><style>body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;
        background:#0B0E13;color:#F2F5F8;font:16px/1.5 system-ui,sans-serif;text-align:center;padding:24px}
        p{color:#9AA4B2}a{display:inline-block;margin-top:16px;padding:14px 28px;border-radius:999px;background:#D4FF3A;
        color:#0B0E13;font-weight:700;text-decoration:none}</style></head><body><div><h1>$title</h1><p>$message</p>
        <a href="fitcompanion://openrouter-callback">Return to the app</a></div>
        <script>setTimeout(function(){location.href="fitcompanion://openrouter-callback"},300)</script></body></html>
    """.trimIndent()

    private fun randomUrlSafe(bytes: Int) = b64url(ByteArray(bytes).also { SecureRandom().nextBytes(it) })
    private fun b64url(b: ByteArray) = Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
}
