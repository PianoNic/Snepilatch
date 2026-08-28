package ch.snepilatch.app.download

import ch.snepilatch.app.util.LokiLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
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

    /**
     * One string field off a rendition, or null when it is absent or explicitly null. JsonNull is a
     * JsonPrimitive whose content reads back as the string "null", so a plain content check treats an
     * absent convertTo as a transcode and discards every rendition there is.
     */
    private fun field(obj: JsonObject?, name: String): String? {
        val element = obj?.get(name) ?: return null
        if (element is JsonNull) return null
        return runCatching { element.jsonPrimitive.content }.getOrNull()?.takeIf { it.isNotBlank() }
    }

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

        // Renditions describe the real formats; the flattened bestQuality fields are the older shape
        // and came back empty once the server moved over. Anything carrying convertTo is transcoded,
        // and the highest-bitrate one of those is an mp3 re-encode of a rendition already listed here.
        val untouched = runCatching {
            audio?.get("renditions")?.jsonArray
                ?.mapNotNull { runCatching { it.jsonObject }.getOrNull() }
                ?.filter { field(it, "convertTo").isNullOrBlank() }
        }.getOrNull().orEmpty()
        val best = untouched.maxByOrNull { field(it, "bitrate")?.toDoubleOrNull() ?: 0.0 }

        val key = field(best, "key") ?: runCatching {
            audio?.get("bestQualityKey")?.jsonPrimitive?.content
        }.getOrNull()
        if (key.isNullOrBlank()) {
            LokiLogger.w(TAG, "no audio key for $trackUri")
            return@withContext null
        }
        // Trust the reported mime over the extension: the extension has claimed .mp3 for Opus before,
        // and the mime is what tells the store to remux into Ogg rather than write WebM as an m4a.
        val mimeType = field(best, "mimeType") ?: runCatching {
            audio?.get("bestQualityMimeType")?.jsonPrimitive?.content
        }.getOrNull()
        LokiLogger.i(TAG, "resolved $trackUri as ${mimeType ?: "unknown"} (${field(best, "label") ?: "?"})")
        Audio("$BASE_URL/api/Stream/Media/$key", mimeType)
    }
}
