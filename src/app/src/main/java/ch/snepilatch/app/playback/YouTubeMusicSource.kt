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
 * Two things are load-bearing. [VR_VERSION] must stay current: 1.61.47 and 1.62.27 answer
 * LOGIN_REQUIRED. And every request needs visitorData; without it the client is refused, and clients
 * that answer anyway (IOS) return a URL that serves one megabyte and then 403s.
 */
object YouTubeMusicSource {

    private const val TAG = "YtmSource"

    private const val SEARCH_URL = "https://music.youtube.com/youtubei/v1/search"
    private const val PLAYER_URL = "https://youtubei.googleapis.com/youtubei/v1/player"

    // Songs first (durations match Spotify's masters). Tracks that only exist as uploads are absent
    // from that shelf, which returns other work by the same artist instead, so a miss retries videos.
    private const val SONGS_PARAMS = "EgWKAQIIAWoKEAoQAxAEEAkQBQ%3D%3D"
    private const val VIDEOS_PARAMS = "EgWKAQIQAWoKEAoQAxAEEAkQBQ%3D%3D"

    private const val WEB_REMIX_VERSION = "1.20240101.01.00"
    private const val WEB_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private const val VR_VERSION = "1.65.10"
    private const val VR_UA =
        "com.google.android.apps.youtube.vr.oculus/$VR_VERSION (Linux; U; Android 12; GB) gzip"

    /** Wide because a music-video upload of the same track carries an intro or outro. */
    internal const val DURATION_TOLERANCE_SEC = 30L

    /** Duration differences within this many seconds count as equally good, so rank decides. */
    internal const val DURATION_BUCKET_SEC = 5L

    /** Share of the wanted title's words a candidate must carry. At 0.5 shared filler words matched. */
    internal const val MIN_TITLE_SCORE = 0.8

    /**
     * Words that mark a re-recording. A candidate carrying one the request did not ask for is a
     * cover, a karaoke track or an edit, not the recording the user has in their library.
     */
    private val REWORK_MARKERS = setOf(
        "cover", "covers", "karaoke", "instrumental", "remix", "nightcore", "sped", "slowed",
        "reverb", "piano", "acoustic", "tribute", "parody", "mashup", "rendition", "remake",
        "unplugged", "orchestral", "lofi", "8d", "guitar", "violin", "flute",
    )

    private const val MAX_CANDIDATES = 10

    /** What the UI shows when a track carries no artist name; never a real credit to match against. */
    internal const val UNKNOWN_PLACEHOLDER = "Unknown"

    private val BRACKETED = Regex("""[(\[]([^)\]]*)[)\]]""")

    private val json = Json { ignoreUnknownKeys = true }
    private val jsonMedia = "application/json".toMediaType()

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    @Volatile private var visitorData: String? = null

    /**
     * Drops the cached visitor id so the next resolve mints a fresh one. A stale one still resolves
     * happily but yields media urls googlevideo then refuses, which looks like every track failing
     * at once for no reason.
     */
    fun invalidateVisitorData() {
        visitorData = null
    }

    data class Stream(val url: String, val mimeType: String?, val headers: Map<String, String>)

    internal data class Candidate(
        val videoId: String,
        val title: String,
        val artist: String?,
        val durationSec: Long,
        /** Artist, album and the rest of the row. An instrumental release often only says so here. */
        val details: String = "",
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
        // The display placeholder is not a search word. bestMatch drops it again as a match constraint.
        val artist = realArtist(artist)
        val query = searchQuery(artist, title)
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
            bestMatch(candidates, title, artist, durationMs, officialShelf = params == SONGS_PARAMS)
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
            details = texts.drop(1).joinToString(" "),
        )
    }

    /** "3:59" / "1:02:33" -> seconds; null for any run that isn't a timestamp. */
    internal fun parseDuration(text: String): Long? {
        val parts = text.trim().split(":")
        if (parts.size !in 2..3 || parts.any { it.isEmpty() || !it.all(Char::isDigit) }) return null
        return parts.fold(0L) { acc, part -> acc * 60 + part.toLong() }
    }

    /** Null rather than a guess: the wrong recording is worse than none, and the caller skips. */
    internal fun bestMatch(
        candidates: List<Candidate>,
        wantTitle: String,
        wantArtist: String,
        durationMs: Long,
        officialShelf: Boolean = false,
    ): Candidate? {
        val want = titleWords(wantTitle)
        val wantArtistWords = words(realArtist(wantArtist))
        // Read the request the same way as the candidate, brackets included: asking for "Sonne
        // (Remix)" must keep the remix, and that marker only survives in the bracket-keeping split.
        val asked = markerWords(wantTitle) + markerWords(wantArtist)
        val titled = candidates.filter { candidate ->
            (want.isEmpty() || titleScore(candidate.title, want) >= MIN_TITLE_SCORE) &&
                // The release text too, not just the title: an instrumental cut usually carries the
                // original's exact title and artist, and only the release it sits on says what it is.
                // Bracketed only, though — an album is where a release declares itself, and scanning
                // the whole run made a plain album name disqualify every candidate. REWORK_MARKERS
                // holds bare instrument nouns, so "Guitar Songs" or "Piano Man" read as reworks and
                // the track became unresolvable on this source.
                !addsRework("${candidate.title} ${bracketedIn(candidate.details)}", asked)
        }
        // The songs shelf is YouTube Music's own catalogue, so a row on it is a release rather than
        // somebody's upload, and a credit that does not match ours is usually the same recording
        // filed under a different name: it lists "DIGGER" under GIRLS REVOLUTION PROJECT where
        // Spotify credits TSUMITOBATSU, biz, ZERA. When no candidate carries the wanted name at all
        // the credit is telling us nothing, and rejecting on it throws the release away for good.
        //
        // The videos shelf is where anyone can upload, which is what the artist check is for, so
        // there a miss stays a miss. Same on any shelf as soon as one candidate does carry the name:
        // the credit discriminates again, and the ones that lack it lose.
        val byArtist = titled.filter { artistMatches(it.artist, wantArtistWords) }
        val viable = if (officialShelf) byArtist.ifEmpty { titled } else byArtist
        if (durationMs <= 0L) return viable.firstOrNull()
        val wantSec = durationMs / 1000
        // YouTube Music ranks the canonical upload first. An instrumental runs to the same length as
        // the vocal take, so picking purely by the smallest duration difference let a second or two of
        // noise outrank that order; only a clearly better fit (a whole bucket) may.
        return viable
            .withIndex()
            .filter { (_, c) -> c.durationSec > 0 && abs(c.durationSec - wantSec) <= DURATION_TOLERANCE_SEC }
            .minWithOrNull(
                compareBy({ abs(it.value.durationSec - wantSec) / DURATION_BUCKET_SEC }, { it.index })
            )
            ?.value
    }

    /**
     * True when the candidate advertises a rework the request never asked for. Asking for a track
     * that genuinely is a remix keeps working, because the marker is then in [asked] too.
     */
    internal fun addsRework(candidateTitle: String, asked: Set<String>): Boolean =
        markerWords(candidateTitle).any { it in REWORK_MARKERS && it !in asked }

    /** The bracketed parts of a run of release text — "Artist • Album (Instrumental) • 3:45" -> "Instrumental". */
    internal fun bracketedIn(text: String): String =
        BRACKETED.findAll(text).joinToString(" ") { it.groupValues[1] }

    /**
     * The artist to treat as asked for. Blanks the UI's placeholder, which reaches here whenever a
     * queue push carried no artist name: it is not a credit, so requiring candidates to share a word
     * with it rejects every real result and the track stops resolving at all.
     */
    internal fun realArtist(artist: String): String =
        if (artist.equals(UNKNOWN_PLACEHOLDER, ignoreCase = true)) "" else artist

    /**
     * Like [words] but keeps bracketed text. Titles score with brackets dropped, so "Song (feat. X)"
     * still matches "Song" — but that also erased the one thing marking a rework, and "(Instrumental)"
     * became indistinguishable from the real recording: same title, same artist, same length.
     */
    private fun markerWords(s: String): Set<String> =
        s.lowercase()
            .replace(Regex("[^\\p{L}\\p{N} ]"), " ")
            .split(' ')
            .filter { it.isNotBlank() }
            .toSet()

    /**
     * The strongest signal against a cover: a piano rendition is uploaded by whoever played it, not
     * by the artist. Spotify may credit several artists where YouTube credits one, so sharing a
     * single name is enough. An unknown artist on either side cannot rule anything out.
     */
    internal fun artistMatches(candidateArtist: String?, wantArtist: Set<String>): Boolean {
        if (wantArtist.isEmpty()) return true
        val have = words(candidateArtist.orEmpty())
        if (have.isEmpty()) return true
        // Two scripts cannot be compared by word overlap at all. Spotify romanises names YouTube
        // Music leaves in the original, so the same band is TSUMITOBATSU on one side and 罪十罰 on
        // the other, they share nothing, and every real candidate was thrown away. Skipping the
        // check costs nothing the other filters do not already cover: an upload in a different
        // script from the artist we asked for is not what a cover or a karaoke channel looks like.
        if (hasLatin(have) != hasLatin(wantArtist)) return true
        return have.any { h -> wantArtist.any { nearlyEqual(h, it) } }
    }

    /** normalize() has already lowercased, so a Latin letter is enough to tell the scripts apart. */
    private fun hasLatin(words: Set<String>): Boolean = words.any { word -> word.any { it in 'a'..'z' } }

    /** Share of the wanted title's words the candidate carries. */
    internal fun titleScore(candidateTitle: String, want: Set<String>): Double {
        if (want.isEmpty()) return 0.0
        val have = words(candidateTitle)
        return want.count { w -> have.any { nearlyEqual(it, w) } }.toDouble() / want.size
    }

    /** Equal, or one edit apart, so a spelling variant like "Tobbs" / "Tobbss" still matches. */
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

    /**
     * What to search for, with the operator meaning taken out of a leading hyphen.
     *
     * YouTube reads "-word" as "exclude everything containing word". A track called "改変 -罪-"
     * therefore asked search to drop every result carrying 罪, which is the artist and the whole
     * release, and the shelf came back empty. Only a hyphen that opens a word is an operator, so a
     * name like Spider-Man keeps its own.
     */
    internal fun searchQuery(artist: String, title: String): String =
        listOf(artist, title).filter { it.isNotBlank() }.joinToString(" ")
            .replace(Regex("(^|\\s)-+"), "$1")
            .trim()

    /**
     * The words of the wanted title a candidate has to carry, which stops at the feature credit.
     *
     * Spotify writes the guest into the track name, YouTube Music puts it in brackets that
     * [normalize] then strips off the candidate. So "弔花 feat. 他人事" was scored against a
     * candidate reading "弔花", matched one word of three, and never came near [MIN_TITLE_SCORE].
     * Only the part before the credit is required; a candidate that does spell the guest out still
     * matches, because the score measures how much of the wanted title the candidate carries and
     * never penalises extra words.
     */
    internal fun titleWords(title: String): Set<String> =
        words(normalize(title).split(" feat ", " ft ", limit = 2).first()).ifEmpty { words(title) }

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
