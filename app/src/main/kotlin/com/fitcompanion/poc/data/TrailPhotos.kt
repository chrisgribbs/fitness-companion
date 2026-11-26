package com.fitcompanion.poc.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * A picture of the spot you've reached on a virtual walk.
 *
 * 1. Wikimedia Commons (free, no key): the nearest geotagged photo within 2 km, with its
 *    author and licence shown as the licence requires. Cached on the phone.
 * 2. Google Street View (optional, your own key): used when Commons has nothing nearby, or when
 *    you choose it. Uses the free metadata check first, so a paid image is only requested when one
 *    exists. Not cached to disk, in line with Google's terms.
 *
 * Only the trail position is sent (a point on the Sunshine Coast), never your real location.
 */
class TrailPhotos(private val context: Context) {

    enum class Source { WIKIMEDIA, STREET_VIEW }

    data class Photo(
        val bitmap: Bitmap,
        val source: Source,
        /** Attribution line to show under the photo. */
        val credit: String,
        val title: String?,
        val distanceMeters: Double?,
    )

    private val ua = "FitnessCompanionPersonal/0.1 (github.com/chrisgribbs/fitness-companion)"
    private val cacheDir = File(context.cacheDir, "trail-photos").apply { mkdirs() }
    private val memory = mutableMapOf<String, Photo?>()

    /** Wikimedia first, then Street View if a key is set. Null if neither has anything. */
    suspend fun near(lat: Double, lon: Double, googleKey: String?, prefer: Source = Source.WIKIMEDIA): Photo? {
        val order = if (prefer == Source.STREET_VIEW) listOf(Source.STREET_VIEW, Source.WIKIMEDIA) else listOf(Source.WIKIMEDIA, Source.STREET_VIEW)
        for (s in order) {
            val p = when (s) {
                Source.WIKIMEDIA -> runCatching { wikimedia(lat, lon) }.getOrNull()
                Source.STREET_VIEW -> if (googleKey.isNullOrBlank()) null else runCatching { streetView(lat, lon, googleKey) }.getOrNull()
            }
            if (p != null) return p
        }
        return null
    }

    // ---- Wikimedia Commons ----------------------------------------------------------------------

    private suspend fun wikimedia(lat: Double, lon: Double): Photo? = withContext(Dispatchers.IO) {
        val key = "wm:%.4f,%.4f".format(lat, lon)
        memory[key]?.let { return@withContext it }
        val api = "https://commons.wikimedia.org/w/api.php"
        val geo = getJson("$api?action=query&list=geosearch&gscoord=$lat%7C$lon&gsradius=2000&gsnamespace=6&gslimit=15&format=json")
        val hits = geo.optJSONObject("query")?.optJSONArray("geosearch") ?: return@withContext null
        val candidates = (0 until hits.length()).map { hits.getJSONObject(it) }
            .filter { it.optString("title").lowercase().let { t -> (t.endsWith(".jpg") || t.endsWith(".jpeg")) && !t.contains("map") } }
            .take(5)
        if (candidates.isEmpty()) return@withContext null
        val titles = candidates.joinToString("|") { it.getString("title") }
        val info = getJson("$api?action=query&prop=imageinfo&iiprop=url%7Cextmetadata&iiurlwidth=900&format=json&titles=" + enc(titles))
        val pages = info.optJSONObject("query")?.optJSONObject("pages") ?: return@withContext null
        val byTitle = pages.keys().asSequence().map { pages.getJSONObject(it) }.associateBy { it.optString("title") }
        for (c in candidates) {
            val page = byTitle[c.getString("title")] ?: continue
            val ii = page.optJSONArray("imageinfo")?.optJSONObject(0) ?: continue
            val url = ii.optString("thumburl").ifBlank { continue }
            val meta = ii.optJSONObject("extmetadata")
            fun m(k: String) = meta?.optJSONObject(k)?.optString("value")?.replace(Regex("<[^>]*>"), "")?.trim().orEmpty()
            val author = m("Artist").ifBlank { "Unknown author" }
            val license = m("LicenseShortName").ifBlank { "see Wikimedia Commons" }
            val file = File(cacheDir, c.getString("pageid") + ".jpg")
            if (!file.exists()) download(url, emptyMap())?.let { file.writeBytes(it) }
            val bmp = BitmapFactory.decodeFile(file.path) ?: continue
            val photo = Photo(bmp, Source.WIKIMEDIA, "Photo: $author · $license · Wikimedia Commons",
                m("ObjectName").ifBlank { null }, c.optDouble("dist"))
            memory[key] = photo
            return@withContext photo
        }
        null
    }

    // ---- Google Street View ---------------------------------------------------------------------

    private suspend fun streetView(lat: Double, lon: Double, key: String): Photo? = withContext(Dispatchers.IO) {
        val headers = mapOf("X-Android-Package" to context.packageName, "X-Android-Cert" to SHA1_NO_COLONS)
        val loc = "location=$lat,$lon&radius=1000&source=outdoor&key=" + enc(key)
        // Free check: is there a panorama near this point?
        val meta = JSONObject(String(download("https://maps.googleapis.com/maps/api/streetview/metadata?$loc", headers) ?: return@withContext null))
        when (meta.optString("status")) {
            "OK" -> Unit
            "REQUEST_DENIED" -> throw IllegalStateException("Google rejected the key: ${meta.optString("error_message")}")
            else -> return@withContext null
        }
        val bytes = download("https://maps.googleapis.com/maps/api/streetview?size=640x400&$loc", headers) ?: return@withContext null
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@withContext null
        val date = meta.optString("date").ifBlank { null }
        Photo(bmp, Source.STREET_VIEW, "© Google Street View" + (date?.let { " · $it" } ?: ""), null, null)
    }

    /** Checks a Google key with the free metadata call. Returns null if OK, or the problem. */
    suspend fun testGoogleKey(key: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val headers = mapOf("X-Android-Package" to context.packageName, "X-Android-Cert" to SHA1_NO_COLONS)
            val meta = JSONObject(String(download(
                "https://maps.googleapis.com/maps/api/streetview/metadata?location=-26.6814,153.1210&source=outdoor&key=" + enc(key), headers)
                ?: return@runCatching "No response from Google"))
            when (meta.optString("status")) {
                "OK", "ZERO_RESULTS" -> null
                else -> "${meta.optString("status")}: ${meta.optString("error_message")}"
            }
        }.getOrElse { it.message ?: "Couldn't reach Google" }
    }

    // ---- HTTP -----------------------------------------------------------------------------------

    private fun getJson(url: String) = JSONObject(String(download(url, emptyMap()) ?: error("no response")))

    private fun download(url: String, headers: Map<String, String>): ByteArray? {
        val c = URL(url).openConnection() as HttpURLConnection
        return try {
            c.setRequestProperty("User-Agent", ua)
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            c.connectTimeout = 10_000; c.readTimeout = 20_000
            if (c.responseCode != 200) null else c.inputStream.use { it.readBytes() }
        } finally { c.disconnect() }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    companion object {
        /** The app's signing fingerprint (see app/debug.keystore), sent so an Android-restricted key works. */
        const val SHA1_NO_COLONS = "69CE07EE1292C7224D7E1F44ABD75E3628707183"
    }
}
