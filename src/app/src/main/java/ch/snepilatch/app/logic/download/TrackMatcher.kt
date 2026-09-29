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
 * no account (#930). Made on first use and kept for the scan; if it cannot start, the rest of the scan
 * stops asking.
 */
internal class CatalogueLookup {

    private var session: Session? = null

    @Volatile private var unavailable = false
    private val starting = Mutex()

    /** One session for the whole scan, however many lookups ask for it at once. */
    private suspend fun session(): Session = starting.withLock {
        session ?: Session(SessionConfig(identifier = "folder-scan", anonymous = true)).also {
            it.load()
            session = it
        }
    }

    suspend fun find(title: String, artist: String, durationMs: Long): String? {
        if (unavailable || title.isBlank()) return null
        return try {
            val hits = Song(session()).search("$artist $title", limit = 10).tracks.items.map { hit ->
                TrackMatcher.Candidate(hit.id, hit.name, hit.artists.map { it.name }, hit.durationMs)
            }
            TrackMatcher.match(title, artist, durationMs, hits)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LokiLogger.w("CatalogueLookup", "lookup failed for '$title': ${e.message}")
            if (session == null) unavailable = true
            null
        }
    }
}
