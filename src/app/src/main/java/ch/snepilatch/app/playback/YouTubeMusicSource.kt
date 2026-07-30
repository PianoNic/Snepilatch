package ch.snepilatch.app.playback

import ch.snepilatch.app.util.LokiLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Audio from YouTube Music over the InnerTube JSON API. No account, no PO token, no signature
 * cipher. Search runs on WEB_REMIX, the player call on ANDROID_VR.
 *
 * Two things here are load-bearing and cost a long afternoon to find. The player client version must
 * be [VR_VERSION]: 1.61.47 and 1.62.27 both answer LOGIN_REQUIRED ("Sign in to confirm you're not a
 * bot"). And every request needs [visitorData]; without it the same client is refused, and clients
 * that do answer without it (IOS) hand back a URL that only serves its first megabyte before
 * returning 403 forever. With both in place the URL is plain and the whole file is readable.
 */
object YouTubeMusicSource {

    private const val TAG = "YtmSource"

    private const val SEARCH_URL = "https://music.youtube.com/youtubei/v1/search"
    private const val PLAYER_URL = "https://youtubei.googleapis.com/youtubei/v1/player"

    /**
     * Search filters. Songs is the catalog proper and is tried first because its durations match
     * Spotify's masters. Plenty of tracks only exist as uploads though (German YouTuber rap, for
     * one), and for those the songs shelf silently returns other work by the same artist, so a miss
     * retries against videos.
     */
    private const val SONGS_PARAMS = "EgWKAQIIAWoKEAoQAxAEEAkQBQ%3D%3D"
    private const val VIDEOS_PARAMS = "EgWKAQIQAWoKEAoQAxAEEAkQBQ%3D%3D"

    private const val WEB_REMIX_VERSION = "1.20240101.01.00"
    private const val WEB_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private const val VR_VERSION = "1.65.10"
    private const val VR_UA =
        "com.google.android.apps.youtube.vr.oculus/$VR_VERSION (Linux; U; Android 12; GB) gzip"

    /**
     * A catalog master matches Spotify's within a second or two, but a music-video upload of the
     * same track carries an intro or outro, so the window has to allow for that. Combined with the
     * title score below it is still tight enough to reject a different song of similar length.
     */
    internal const val DURATION_TOLERANCE_SEC = 30L

    /**
     * Share of the wanted title's words a candidate must carry. High on purpose: at 0.5 the shared
     * filler in "Rappe nur das Gleiche" and "Es ist immer das Gleiche" was enough to match, and a
     * confidently wrong track is worse than none.
     */
    internal const val MIN_TITLE_SCORE = 0.8

    private const val MAX_CANDIDATES = 10

    private val json = Json { ignoreUnknownKeys = true }
    private val jsonMedia = "application/json".toMediaType()

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    @Volatile private var visitorData: String? = null

    data class Stream(val url: String, val mimeType: String?, val headers: Map<String, String>)

    internal data class Candidate(
        val videoId: String,
        val title: String,
        val artist: String?,
        val durationSec: Long,
    )

    /**
     * Resolves a Spotify track to a YouTube Music stream, or null when there is no confident match.
     * [durationMs] is the Spotify length and is what rejects a same-titled radio edit or live take;
     * pass 0 when it isn't known and matching falls back to the title alone.
     */
    suspend fun resolve(
        title: String,
        artist: String,
        region: String?,
        durationMs: Long,
    ): Stream? = withContext(Dispatchers.IO) {
        val query = listOf(artist, title).filter { it.isNotBlank() }.joinToString(" ").trim()
        if (query.isBlank()) return@withContext null

        val visitor = visitorData ?: fetchVisitorData(region)?.also { visitorData = it }
        if (visitor == null) {
            LokiLogger.w(TAG, "no visitorData, cannot resolve '$query'")
            return@withContext null
        }

        var seen = 0
        val match = listOf(SONGS_PARAMS, VIDEOS_PARAMS).firstNotNullOfOrNull { params ->
            val body = post(SEARCH_URL, searchBody(query, region, visitor, params), WEB_UA, visitor)
            val candidates = body?.let { runCatching { parseCandidates(it) }.getOrNull() }.orEmpty()
            seen += candidates.size
            bestMatch(candidates, title, durationMs)
        }
        if (match == null) {
            LokiLogger.i(TAG, "no match for '$query' ($seen candidates, ${durationMs / 1000}s)")
            return@withContext null
        }
        LokiLogger.i(TAG, "'$query' -> ${match.videoId} '${match.title}' ${match.durationSec}s")

        streamFor(match.videoId, region, visitor)
    }

    /** Any InnerTube response carries one; a throwaway search is the cheapest way to get it. */
    private fun fetchVisitorData(region: String?): String? {
        val gl = (region ?: "US").uppercase()
        val body = """{"context":{"client":{"clientName":"WEB_REMIX",""" +
            """"clientVersion":"$WEB_REMIX_VERSION","hl":"en","gl":"$gl"}},"query":"a"}"""
        val raw = post(SEARCH_URL, body, WEB_UA, visitor = null) ?: return null
        return runCatching {
            json.parseToJsonElement(raw).jsonObject["responseContext"]?.jsonObject
                ?.get("visitorData")?.jsonPrimitive?.content
        }.getOrNull()
    }

    private fun post(url: String, body: String, userAgent: String, visitor: String?): String? = runCatching {
        val request = Request.Builder()
            .url(url)
            .post(body.toRequestBody(jsonMedia))
            .header("User-Agent", userAgent)
            .header("Accept", "*/*")
            .apply { if (visitor != null) header("X-Goog-Visitor-Id", visitor) }
            .build()
        http.newCall(request).execute().use { if (it.isSuccessful) it.body?.string() else null }
    }.getOrNull()

    private fun searchBody(query: String, region: String?, visitor: String, params: String): String {
        val gl = (region ?: "US").uppercase()
        return """{"context":{"client":{"clientName":"WEB_REMIX","clientVersion":"$WEB_REMIX_VERSION",""" +
            """"hl":"en","gl":"$gl","visitorData":"$visitor"}},""" +
            """"query":"${escapeJson(query)}","params":"$params"}"""
    }

    private fun playerBody(videoId: String, region: String?, visitor: String): String {
        val gl = (region ?: "US").uppercase()
        return """{"context":{"client":{"clientName":"ANDROID_VR","clientVersion":"$VR_VERSION",""" +
            """"deviceMake":"Oculus","deviceModel":"Quest 3","osName":"Android","osVersion":"12",""" +
            """"androidSdkVersion":32,"hl":"en","gl":"$gl","visitorData":"$visitor"}},""" +
            """"videoId":"$videoId","contentCheckOk":true,"racyCheckOk":true}"""
    }

    private fun streamFor(videoId: String, region: String?, visitor: String): Stream? {
        val raw = post(PLAYER_URL, playerBody(videoId, region, visitor), VR_UA, visitor)
        if (raw == null) {
            LokiLogger.w(TAG, "player request failed for $videoId")
            return null
        }
        return runCatching { pickAudio(raw) }.getOrNull()
    }

    /**
     * contents -> tabbedSearchResultsRenderer -> tabs -> sectionListRenderer -> musicShelfRenderer
     * -> musicResponsiveListItemRenderer. Rows without a videoId or title are shelf furniture.
     */
    internal fun parseCandidates(rawJson: String): List<Candidate> {
        val rows = json.parseToJsonElement(rawJson).jsonObject["contents"]?.jsonObject
            ?.get("tabbedSearchResultsRenderer")?.jsonObject
            ?.get("tabs")?.jsonArray
            ?.flatMap { tab ->
                tab.jsonObject["tabRenderer"]?.jsonObject
                    ?.get("content")?.jsonObject
                    ?.get("sectionListRenderer")?.jsonObject
                    ?.get("contents")?.jsonArray
                    ?.mapNotNull { it.jsonObject["musicShelfRenderer"]?.jsonObject }
                    ?.flatMap { shelf -> shelf["contents"]?.jsonArray?.toList() ?: emptyList() }
                    ?: emptyList()
            }
            ?: return emptyList()
        return rows.mapNotNull { candidateFrom(it) }.take(MAX_CANDIDATES)
    }

    private fun candidateFrom(row: JsonElement): Candidate? {
        val item = row.jsonObject["musicResponsiveListItemRenderer"]?.jsonObject ?: return null
        val videoId = item["playlistItemData"]?.jsonObject?.get("videoId")?.jsonPrimitive?.content
            ?: return null
        val texts = item["flexColumns"]?.jsonArray?.flatMap { column ->
            column.jsonObject["musicResponsiveListItemFlexColumnRenderer"]?.jsonObject
                ?.get("text")?.jsonObject
                ?.get("runs")?.jsonArray
                ?.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.content }
                ?: emptyList()
        } ?: return null
        val title = texts.firstOrNull()?.takeIf { it.isNotBlank() } ?: return null
        return Candidate(
            videoId = videoId,
            title = title,
            artist = texts.drop(1).firstOrNull { it.trim().length > 1 },
            durationSec = texts.firstNotNullOfOrNull { parseDuration(it) } ?: 0L,
        )
    }

    /** "3:59" / "1:02:33" -> seconds; null for any run that isn't a timestamp. */
    internal fun parseDuration(text: String): Long? {
        val parts = text.trim().split(":")
        if (parts.size !in 2..3 || parts.any { it.isEmpty() || !it.all(Char::isDigit) }) return null
        return parts.fold(0L) { acc, part -> acc * 60 + part.toLong() }
    }

    /**
     * Returning null rather than guessing is deliberate: the wrong recording is worse than none, and
     * the caller already skips a track it cannot resolve.
     */
    internal fun bestMatch(candidates: List<Candidate>, wantTitle: String, durationMs: Long): Candidate? {
        val want = words(wantTitle)
        val titled = candidates.filter { want.isEmpty() || titleScore(it.title, want) >= MIN_TITLE_SCORE }
        if (durationMs <= 0L) return titled.firstOrNull()
        val wantSec = durationMs / 1000
        return titled
            .filter { it.durationSec > 0 && abs(it.durationSec - wantSec) <= DURATION_TOLERANCE_SEC }
            .minByOrNull { abs(it.durationSec - wantSec) }
    }

    /**
     * Share of the wanted title's words the candidate carries. Word overlap rather than substring
     * containment, because YouTube and Spotify disagree on spelling often enough to matter and one
     * differing word should not throw the match away.
     */
    internal fun titleScore(candidateTitle: String, want: Set<String>): Double {
        if (want.isEmpty()) return 0.0
        val have = words(candidateTitle)
        return want.count { w -> have.any { nearlyEqual(it, w) } }.toDouble() / want.size
    }

    /**
     * Equal, or one edit apart for words long enough that a single differing character is a spelling
     * variant rather than a different word. Spotify's "Tobbss" and YouTube's "Tobbs" are the same
     * track, and exact comparison threw it away.
     */
    private fun nearlyEqual(a: String, b: String): Boolean {
        if (a == b) return true
        if (minOf(a.length, b.length) < 4 || abs(a.length - b.length) > 1) return false
        val (long, short) = if (a.length >= b.length) a to b else b to a
        var i = 0
        var j = 0
        var edits = 0
        while (i < long.length && j < short.length) {
            if (long[i] == short[j]) {
                i++
                j++
            } else {
                if (++edits > 1) return false
                i++
                if (long.length == short.length) j++
            }
        }
        return edits + (long.length - i) + (short.length - j) <= 1
    }

    private fun words(s: String): Set<String> =
        normalize(s).split(' ').filter { it.isNotBlank() }.toSet()

    internal fun normalize(s: String): String = s.lowercase()
        .replace(Regex("\\(.*?\\)|\\[.*?]"), " ")
        .replace(Regex("[^\\p{L}\\p{N} ]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    /** Highest-bitrate audio-only format. Formats with `signatureCipher` instead of `url` are skipped. */
    internal fun pickAudio(rawJson: String): Stream? {
        val root = json.parseToJsonElement(rawJson).jsonObject
        val status = root["playabilityStatus"]?.jsonObject?.get("status")?.jsonPrimitive?.content
        if (status != "OK") {
            LokiLogger.w(TAG, "playabilityStatus=$status")
            return null
        }
        val best = root["streamingData"]?.jsonObject?.get("adaptiveFormats")?.jsonArray
            ?.map { it.jsonObject }
            ?.filter {
                it["mimeType"]?.jsonPrimitive?.content.orEmpty().startsWith("audio") && it["url"] != null
            }
            ?.maxByOrNull { it["bitrate"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L }
            ?: return null
        val url = best["url"]?.jsonPrimitive?.content ?: return null
        return Stream(
            url = url,
            mimeType = best["mimeType"]?.jsonPrimitive?.content?.substringBefore(';'),
            headers = mapOf("User-Agent" to VR_UA),
        )
    }

    private fun escapeJson(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"")
}
