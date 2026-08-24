package ch.snepilatch.app.download

import ch.snepilatch.app.util.LokiLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Resolves audio through ArgonFetch, for when googlevideo refuses the url the InnerTube player
 * handed us. It takes the Spfy url and does its own lookup, so the fallback needs no YouTube search
 * of ours — which is also the path that used to fail outright when our matcher found no candidate.
 */
object ArgonFetchSource {

    private const val TAG = "ArgonFetch"
    private const val BASE_URL = "https://app.argonfetch.dev"

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    /** Where the audio is and what it is. The mime decides whether the store remuxes or writes as-is. */
    data class Audio(val url: String, val mimeType: String?)

    /** A streamable audio for [trackUri], or null when ArgonFetch cannot resolve it. */
    suspend fun audio(trackUri: String): Audio? = withContext(Dispatchers.IO) {
        val id = trackUri.substringAfterLast(':')
        val request = Request.Builder()
            .url("$BASE_URL/api/Fetch/GetResource?url=https://open.spotify.com/track/$id")
            .header("Accept", "application/json")
            .build()
        val payload = runCatching {
            http.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    response.body?.string()
                } else {
                    LokiLogger.w(TAG, "HTTP ${response.code} for $trackUri")
                    null
                }
            }
        }.onFailure { LokiLogger.w(TAG, "request failed: $it") }.getOrNull()
            ?: return@withContext null

        val audio = runCatching {
            json.parseToJsonElement(payload).jsonObject["mediaItems"]?.jsonArray
                ?.firstOrNull()?.jsonObject?.get("audio")?.jsonObject
        }.getOrNull()
        val key = runCatching { audio?.get("bestQualityKey")?.jsonPrimitive?.content }.getOrNull()
        if (key.isNullOrBlank()) {
            LokiLogger.w(TAG, "no audio key for $trackUri")
            return@withContext null
        }
        // Trust the reported mime over the extension: the extension has claimed .mp3 for Opus before,
        // and the mime is what tells the store to remux into Ogg rather than write WebM as an m4a.
        val mimeType = runCatching { audio?.get("bestQualityMimeType")?.jsonPrimitive?.content }.getOrNull()
        LokiLogger.i(TAG, "resolved $trackUri as ${mimeType ?: "unknown"}")
        Audio("$BASE_URL/api/Stream/Media/$key", mimeType)
    }
}
