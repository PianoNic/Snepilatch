package ch.snepilatch.app.logic.download

import ch.snepilatch.app.logic.shared.LokiLogger
import kotify.api.song.Song
import kotify.session.Session
import kotify.session.SessionConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.abs

/**
 * Which catalogue track a file without a track id is (#930). Strict on purpose: a wrong match indexes
 * the file as another song, and that song then plays from it, so a near miss is left unmatched.
 */
internal object TrackMatcher {

    data class Candidate(val id: String, val title: String, val artists: List<String>, val durationMs: Long)

    /** YouTube uploads run a few seconds long or short (silence, an intro); a different cut is far more. */
    private const val DURATION_SLACK_MS = 5_000L

    private val BRACKETS = Regex("""\s*[(\[][^)\]]*[)\]]""")
    private val ARTIST_SEPARATORS = Regex(""",|&| feat\.? | ft\.? | x """, RegexOption.IGNORE_CASE)

    /**
     * Among candidates with the same title and an artist in common, the one closest in length, if it is
     * close enough. Closest rather than first: a release often has the same song twice, seconds apart.
     */
    fun match(title: String, artist: String, durationMs: Long, candidates: List<Candidate>): String? {
        val wantTitle = normalize(title).ifEmpty { return null }
        val wantArtists = artist.split(ARTIST_SEPARATORS).map(::normalize).filter { it.isNotEmpty() }.toSet()
        val same = candidates.filter { c -> normalize(c.title) == wantTitle && c.artists.any { normalize(it) in wantArtists } }
        if (durationMs <= 0) return same.firstOrNull()?.id
        return same.filter { it.durationMs > 0 }
            .minByOrNull { abs(it.durationMs - durationMs) }
            ?.takeIf { abs(it.durationMs - durationMs) <= DURATION_SLACK_MS }
            ?.id
    }

    /** Case, "(feat. …)", "[Remastered]", " - Live" and punctuation do not tell two titles apart. */
    internal fun normalize(text: String): String =
        text.lowercase().replace(BRACKETS, "").substringBefore(" - ").filter { it.isLetterOrDigit() }
}

/**
 * Catalogue search for [FolderScan], on an anonymous session so a folder's worth of lookups runs on
 * no account (#930). Made on first use and kept for the scan. The first refusal, the session not
 * starting included, stops the rest of the run (#1034): the service once refused anonymous sessions
 * their token, and every file after it was reported as not found.
 */
internal class CatalogueLookup(private val session: suspend () -> Session? = anonymous()) {

    /** What a file's tags are filled in from (#946). */
    data class TrackInfo(val title: String, val artist: String, val album: String?, val coverUrl: String?)

    /** The details of each track a search matched, so filling in its tags asks nothing more. */
    private val matched = java.util.concurrent.ConcurrentHashMap<String, TrackInfo>()

    /** Set by the first refused request: the rest of the run asks nothing more, and says so. */
    @Volatile var refused: String? = null
        private set

    suspend fun find(title: String, artist: String, durationMs: Long): String? {
        if (refused != null || title.isBlank()) return null
        return try {
            val sess = session() ?: return refuse("no session")
            val found = Song(sess).search("$artist $title", limit = 10).tracks.items
            val hits = found.map { hit ->
                TrackMatcher.Candidate(hit.id, hit.name, hit.artists.map { it.name }, hit.durationMs)
            }
            TrackMatcher.match(title, artist, durationMs, hits)?.also { id ->
                found.firstOrNull { it.id == id }?.let { hit ->
                    matched[id] = TrackInfo(hit.name, hit.artists.joinToString(", ") { it.name }, hit.album?.name, hit.album?.coverArtUrl)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            refuse("search for '$title' failed: ${e.message}")
        }
    }

    /** The title, artists, album and cover of track [id]: from the search that matched it, or else its track page. */
    suspend fun info(id: String): TrackInfo? {
        matched[id]?.let { return it }
        if (refused != null) return null
        return try {
            val sess = session() ?: return refuse("no session")
            Song(sess).getSong(id)?.let { track ->
                TrackInfo(track.name, track.artists.joinToString(", ") { it.name }, track.album.name, track.album.coverArtUrl)
            } ?: null.also {
                // An empty answer, not an error; the row stays unchecked, so the next migration asks again.
                LokiLogger.w("CatalogueLookup", "no details for $id in the answer")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            refuse("details for $id failed: ${e.message}")
        }
    }

    private companion object {
        /** One anonymous session for the whole scan, however many lookups ask for it at once. */
        fun anonymous(): suspend () -> Session {
            var made: Session? = null
            val starting = Mutex()
            return {
                starting.withLock {
                    made ?: Session(
                        SessionConfig(identifier = "folder-scan", anonymous = true, deviceProfile = kotify.config.DeviceProfile.CHROME_ANDROID_DESKTOP)
                    ).also {
                        it.load()
                        made = it
                    }
                }
            }
        }
    }

    private fun <T> refuse(why: String): T? {
        if (refused == null) LokiLogger.w("CatalogueLookup", "stopping the lookups: $why")
        refused = why
        return null
    }
}
