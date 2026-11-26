package com.fitcompanion.poc.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.fitcompanion.poc.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks the public download repo for a newer build and installs it.
 * Reads only public release information from GitHub; sends nothing about you or your data.
 */
class Updater(private val context: Context) {

    data class Available(val build: Int, val notes: String, val downloadUrl: String, val sizeBytes: Long)

    val currentBuild: Int get() = BuildConfig.VERSION_CODE

    /** Returns the newer build, or null if this is the latest. */
    suspend fun check(): Available? = withContext(Dispatchers.IO) {
        val c = URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection
        try {
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.setRequestProperty("User-Agent", "FitnessCompanion/${BuildConfig.VERSION_NAME}")
            c.connectTimeout = 10_000; c.readTimeout = 15_000
            if (c.responseCode == 404) return@withContext null // no releases yet
            if (c.responseCode != 200) throw IllegalStateException("Update check failed (HTTP ${c.responseCode}).")
            val json = JSONObject(c.inputStream.use { String(it.readBytes()) })
            val build = json.optString("tag_name").removePrefix("build-").toIntOrNull() ?: return@withContext null
            val assets = json.optJSONArray("assets") ?: return@withContext null
            val apk = (0 until assets.length()).map { assets.getJSONObject(it) }.firstOrNull { it.optString("name").endsWith(".apk") }
                ?: return@withContext null
            if (build <= currentBuild) null
            else Available(build, json.optString("body"), apk.getString("browser_download_url"), apk.optLong("size"))
        } finally {
            c.disconnect()
        }
    }

    /** Downloads the APK into the app's cache, reporting progress 0–1. */
    suspend fun download(a: Available, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val out = File(dir, "FitnessCompanion-${a.build}.apk")
        var url = URL(a.downloadUrl)
        var c: HttpURLConnection
        var hops = 0
        while (true) { // follow GitHub's redirect to its download server
            c = url.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", "FitnessCompanion/${BuildConfig.VERSION_NAME}")
            val code = c.responseCode
            if (code in 300..399 && hops++ < 5) { url = URL(url, c.getHeaderField("Location")); c.disconnect(); continue }
            if (code != 200) throw IllegalStateException("Download failed (HTTP $code).")
            break
        }
        try {
            val total = c.contentLengthLong.takeIf { it > 0 } ?: a.sizeBytes
            c.inputStream.use { input ->
                out.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024); var done = 0L
                    while (true) {
                        val n = input.read(buf); if (n < 0) break
                        output.write(buf, 0, n); done += n
                        if (total > 0) onProgress((done.toFloat() / total).coerceAtMost(1f))
                    }
                }
            }
        } finally { c.disconnect() }
        out
    }

    /** True once you've allowed this app to install updates (asked once by Android). */
    fun canInstall(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    fun openInstallPermissionSettings() {
        context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Hands the APK to Android's installer, which shows "Update this app?". */
    fun install(apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.photos", apk)
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    companion object {
        const val REPO = "chrisgribbs/fitness-companion"
    }
}
