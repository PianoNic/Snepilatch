package ch.snepilatch.app.download

import ch.snepilatch.app.util.LokiLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Resolves audio for a YouTube id through yt1d, for when googlevideo refuses the url the InnerTube
 * player handed us. Last rung on purpose: this is a scraped page rather than an API, and the
 * reference client that relies on it keeps it behind its other providers because it has drifted.
 */
object Yt1dSource {

    private const val TAG = "Yt1d"
    private const val RESULTS_URL = "https://yt1d.io/results/"
    private const val AJAX_URL = "https://yt1d.io/wp-admin/admin-ajax.php"
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    private val ajaxPattern = Regex(""""ajaxurl"\s*:\s*"([^"]+)"""")
    private val noncePattern = Regex(""""nonce"\s*:\s*"([^"]+)"""")

    /** JSON escapes a slash; 92 is the backslash, kept as a code so no escape appears in source. */
    private val ESCAPED_SLASH = 92.toChar() + "/"

    private data class Config(val ajaxUrl: String, val nonce: String)

    @Volatile
    private var cached: Config? = null

    /** A direct audio url for [videoId], or null when yt1d has nothing for it. */
    suspend fun audioUrl(videoId: String): String? = withContext(Dispatchers.IO) {
        val config = cached ?: fetchConfig()?.also { cached = it } ?: return@withContext null
        val body = FormBody.Builder()
            .add("action", "process_youtube_audio_download")
            .add("video_url", "https://www.youtube.com/watch?v=$videoId")
            .add("quality", "m4a")
            .add("nonce", config.nonce)
            .build()
        val request = Request.Builder()
            .url(config.ajaxUrl)
            .post(body)
            .header("Accept", "application/json, text/javascript, */*; q=0.01")
            .header("Origin", "https://yt1d.io")
            .header("Referer", RESULTS_URL)
            .header("User-Agent", UA)
            .build()
        val payload = runCatching {
            http.newCall(request).execute().use { if (it.isSuccessful) it.body?.string() else null }
        }.getOrNull() ?: return@withContext null
        val url = runCatching { downloadUrl(json.parseToJsonElement(payload)) }.getOrNull()
        if (url == null) {
            // A stale nonce reads as a plain failure, so drop it and let the next attempt re-fetch.
            cached = null
            LokiLogger.w(TAG, "no download url for $videoId")
        }
        url
    }

    private fun fetchConfig(): Config? {
        val request = Request.Builder()
            .url(RESULTS_URL)
            .header("Accept", "text/html,application/xhtml+xml")
            .header("User-Agent", UA)
            .build()
        val html = runCatching {
            http.newCall(request).execute().use { if (it.isSuccessful) it.body?.string() else null }
        }.getOrNull() ?: return null
        val nonce = noncePattern.find(html)?.groupValues?.get(1) ?: return null
        val ajax = ajaxPattern.find(html)?.groupValues?.get(1)?.replace(ESCAPED_SLASH, "/") ?: AJAX_URL
        return Config(ajax, nonce)
    }

    /** The url sits under one of several keys, sometimes nested under "data". */
    private fun downloadUrl(element: JsonElement): String? {
        val obj = element as? JsonObject ?: return null
        for (key in listOf("downloadUrl", "downloadURL", "url", "download_link")) {
            val value = runCatching { obj[key]?.jsonPrimitive?.content }.getOrNull()
            if (value != null && value.startsWith("http")) return value
        }
        val nested = runCatching { obj["data"]?.jsonObject }.getOrNull() ?: return null
        return downloadUrl(nested)
    }
}
