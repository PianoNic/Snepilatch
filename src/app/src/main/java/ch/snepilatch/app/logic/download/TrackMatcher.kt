package ch.snepilatch.app.logic.download

import ch.snepilatch.app.logic.shared.LokiLogger
import kotify.api.song.Song
import kotify.session.Session
import kotlinx.coroutines.CancellationException
import kotlin.math.abs

/**
 * Which catalogue track a file without a track id is (#930). Strict on purpose: a wrong match indexes
 * the file as another song, and that song then plays from it, so a near miss is left unmatched.
 */
internal object TrackMatcher {

    data class Candidate(val id: String, val title: String, val artists: List<String>, val durationMs: Long)

    /** YouTube uploads run a few seconds long or short (silence, an intro); a different cut is far more. */
    private const val DURATION_SLACK_MS = 5_000L

    /** How close a differently titled result must be in length to count as the same recording. */
    private const val SAME_RECORDING_MS = 2_000L

    private val BRACKETS = Regex("""\s*[(\[][^)\]]*[)\]]""")
    private val ARTIST_SEPARATORS = Regex(""",|&| feat\.? | ft\.? | x """, RegexOption.IGNORE_CASE)

    /**
     * Among candidates with the same title and an artist in common, the one closest in length, if it is
     * close enough. Closest rather than first: a release often has the same song twice, seconds apart.
     */
    fun match(title: String, artist: String, durationMs: Long, candidates: List<Candidate>): String? {
        val wantTitle = normalize(title).ifEmpty { return null }
        val wantArtists = artistKeys(listOf(artist))
        val byArtist = candidates.filter { c -> artistKeys(c.artists).any { it in wantArtists } }
        val same = byArtist.filter { normalize(it.title) == wantTitle }
        if (durationMs <= 0) return same.firstOrNull()?.id
        return same.filter { it.durationMs > 0 }
            .minByOrNull { abs(it.durationMs - durationMs) }
            ?.takeIf { abs(it.durationMs - durationMs) <= DURATION_SLACK_MS }
            ?.id
            // The catalogue may name a song in another language than the file ("항복" as "Surrender"): the
            // same artist and the same length to the second is that song.
            ?: byArtist.filter { it.durationMs > 0 && abs(it.durationMs - durationMs) <= SAME_RECORDING_MS }
                .minByOrNull { abs(it.durationMs - durationMs) }?.id
    }

    /**
     * Each artist and each of its parts, compared without case, punctuation or word order: "Earth, Wind &
     * Fire" is one artist to the catalogue and three parts to a split, and "Mori Calliope" is "Calliope Mori".
     */
    internal fun artistKeys(artists: List<String>): Set<String> = artists
        .flatMap { listOf(it) + it.split(ARTIST_SEPARATORS) }
        .map(::wordsInAnyOrder)
        .filter { it.isNotEmpty() }
        .toSet()

    private val WHITESPACE = Regex("\\s+")

    private fun wordsInAnyOrder(name: String): String =
        name.lowercase().split(WHITESPACE).map(::normalize).filter(String::isNotEmpty).sorted().joinToString("")

    /** For the log, when [match] found nothing: what the closest result got wrong. */
    fun why(title: String, artist: String, durationMs: Long, candidates: List<Candidate>): String {
        if (candidates.isEmpty()) return "the search found nothing"
        val wantTitle = normalize(title)
        val wantArtists = artistKeys(listOf(artist))
        val sameTitle = candidates.filter { normalize(it.title) == wantTitle }
        if (sameTitle.isEmpty()) return "no result of that title; first was '${candidates.first().title}' by ${candidates.first().artists}"
        val sameArtist = sameTitle.filter { c -> artistKeys(c.artists).any { it in wantArtists } }
        if (sameArtist.isEmpty()) return "that title only by ${sameTitle.first().artists}"
        val closest = sameArtist.minBy { abs(it.durationMs - durationMs) }
        return "closest length ${closest.durationMs}ms, ${abs(closest.durationMs - durationMs) / 1000}s off"
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
internal class CatalogueLookup(private val session: suspend () -> Session? = { AnonymousCatalogue.session() }) {

    private val albumTags = AlbumTagsLookup(session)

    /** [info] with its album details (#1038), for a file that lacks any of them. */
    suspend fun infoWithAlbum(id: String): TrackInfo? {
        val info = info(id) ?: return null
        if (refused != null) return info
        return info.copy(albumTags = albumTags.of(id))
    }

    /** What a file's tags are filled in from (#946). */
    data class TrackInfo(
        val title: String,
        val artist: String,
        val album: String?,
        val coverUrl: String?,
        /** Asked only when the file lacks an album field (#1038). */
        val albumTags: AlbumTags? = null,
    )

    /** The details of each track a search matched, so filling in its tags asks nothing more. */
    private val matched = java.util.concurrent.ConcurrentHashMap<String, TrackInfo>()

    /** Set by the first refused request: the rest of the run asks nothing more, and says so. */
    @Volatile var refused: String? = null
        private set

    suspend fun find(title: String, artist: String, durationMs: Long): String? {
        if (refused != null || title.isBlank()) return null
        return try {
            val sess = session() ?: return refuse("no session")
            val found = Song(sess).search("$artist $title", limit = searchResults).tracks.items
            val hits = found.map { hit ->
                TrackMatcher.Candidate(hit.id, hit.name, hit.artists.map { it.name }, hit.durationMs)
            }
            val id = TrackMatcher.match(title, artist, durationMs, hits)
            if (id == null) {
                val why = TrackMatcher.why(title, artist, durationMs, hits)
                LokiLogger.i("CatalogueLookup", "no match for '$artist - $title' (${durationMs}ms): $why")
            }
            id?.also {
                found.firstOrNull { hit -> hit.id == it }?.let { hit ->
                    matched[it] = TrackInfo(hit.name, hit.artists.joinToString(", ") { a -> a.name }, hit.album?.name, hit.album?.coverArtUrl)
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

    /** Deep enough that a game soundtrack's many tracks of one name still include the right one. */
    private val searchResults = 20

    private fun <T> refuse(why: String): T? {
        if (refused == null) LokiLogger.w("CatalogueLookup", "stopping the lookups: $why")
        refused = why
        return null
    }
}
