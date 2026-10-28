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
        // No length to tell cuts apart: the one of exactly that title, not a remix of the name (#1053).
        if (durationMs <= 0) return (same.filter { fullTitle(it.title) == fullTitle(title) }.ifEmpty { same }).firstOrNull()?.id
        return same.filter { it.durationMs > 0 }
            .minByOrNull { abs(it.durationMs - durationMs) }
            ?.takeIf { abs(it.durationMs - durationMs) <= DURATION_SLACK_MS }
            ?.id
            // The catalogue may name a song in another language than the file ("항복" as "Surrender"): the
            // same artist and the same length to the second is that song.
            ?: byArtist.filter { it.durationMs > 0 && abs(it.durationMs - durationMs) <= SAME_RECORDING_MS }
                .minByOrNull { abs(it.durationMs - durationMs) }?.id
            // Or the artist under another name ("MARK" as "Mark Lee", "ittokan" as "一斗缶"): the same title
            // and the same length to the second is that song (#1053).
            ?: candidates
                .filter { normalize(it.title) == wantTitle }
                .filter { it.durationMs > 0 && abs(it.durationMs - durationMs) <= SAME_RECORDING_MS }
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

    /** When [match] found nothing: what the closest result got wrong, for the log and the user (#1053). */
    fun miss(title: String, artist: String, durationMs: Long, candidates: List<Candidate>): Miss {
        val wantTitle = normalize(title)
        val wantArtists = artistKeys(listOf(artist))
        val sameTitle = candidates.filter { normalize(it.title) == wantTitle }
        if (sameTitle.isEmpty()) return Miss.NotFound
        val sameArtist = sameTitle.filter { c -> artistKeys(c.artists).any { it in wantArtists } }
        if (sameArtist.isEmpty()) return Miss.OtherArtist(sameTitle.first().artists.joinToString(", "))
        // The one of exactly that title, "Children" rather than "Children - Undercatt Remix", is what it was downloaded as.
        val exact = sameArtist.filter { fullTitle(it.title) == fullTitle(title) }.ifEmpty { sameArtist }
        val closest = exact.minBy { abs(it.durationMs - durationMs) }
        return Miss.WrongLength(closest.id, fileMs = durationMs, catalogueMs = closest.durationMs)
    }

    private val FEATURES = Regex("""\s*[(\[](?:feat|ft|with)[^)\]]*[)\]]""", RegexOption.IGNORE_CASE)

    /** The title with its remix or version, only a featured artist left out. */
    private fun fullTitle(text: String): String = text.lowercase().replace(FEATURES, "").filter { it.isLetterOrDigit() }

    /** Case, "(feat. …)", "[Remastered]", " - Live" and punctuation do not tell two titles apart. */
    internal fun normalize(text: String): String =
        text.lowercase().replace(BRACKETS, "").substringBefore(" - ").filter { it.isLetterOrDigit() }
}

/** Why a file matched no track, best first: the order [FolderScan] keeps the most telling one by. */
internal sealed interface Miss {
    val rank: Int

    /**
     * The song is in the catalogue under this title and artist, but the file is another length: another
     * song or another cut of it, which a wrong source hit once downloaded. The file is broken.
     */
    data class WrongLength(val id: String, val fileMs: Long, val catalogueMs: Long) : Miss {
        override val rank = 0
    }

    /** The catalogue has the title only by [artists], at another length. */
    data class OtherArtist(val artists: String) : Miss {
        override val rank = 1
    }

    data object NotFound : Miss {
        override val rank = 2
    }

    /** Neither an id nor tags can be read from the file. */
    data object Unreadable : Miss {
        override val rank = 3
    }

    /** As it is remembered between migrations, the track id left out: the list shows the lengths only. */
    fun encode(): String = when (this) {
        is WrongLength -> "length	$fileMs	$catalogueMs"
        is OtherArtist -> "artist	$artists"
        NotFound -> "none"
        Unreadable -> "unreadable"
    }

    companion object {
        fun decode(text: String): Miss {
            val p = text.split('	')
            return when (p.firstOrNull()) {
                "length" -> WrongLength("", p.getOrNull(1)?.toLongOrNull() ?: 0, p.getOrNull(2)?.toLongOrNull() ?: 0)
                "artist" -> OtherArtist(p.getOrNull(1).orEmpty())
                "unreadable" -> Unreadable
                else -> NotFound
            }
        }
    }
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
        /** For a download of it, so the source's hit is checked against it (#1053). */
        val durationMs: Long = 0,
    )

    /** The details of each track a search returned, so filling in its tags or downloading it asks nothing more. */
    private val seen = java.util.concurrent.ConcurrentHashMap<String, TrackInfo>()

    /** Set by the first refused request: the rest of the run asks nothing more, and says so. */
    @Volatile var refused: String? = null
        private set

    suspend fun find(title: String, artist: String, durationMs: Long): String? = (search(title, artist, durationMs) as? Match)?.id

    /** The track [title] by [artist] of [durationMs] is, or why there is none; null when nothing was asked. */
    suspend fun search(title: String, artist: String, durationMs: Long): Outcome? {
        if (refused != null || title.isBlank()) return null
        return try {
            val sess = session() ?: return refuse("no session")
            val found = Song(sess).search("$artist $title", limit = searchResults).tracks.items
            found.forEach { hit ->
                seen[hit.id] = TrackInfo(
                    hit.name, hit.artists.joinToString(", ") { a -> a.name }, hit.album?.name, hit.album?.coverArtUrl,
                    durationMs = hit.durationMs,
                )
            }
            val hits = found.map { hit ->
                TrackMatcher.Candidate(hit.id, hit.name, hit.artists.map { it.name }, hit.durationMs)
            }
            val id = TrackMatcher.match(title, artist, durationMs, hits)
            if (id != null) return Match(id)
            val miss = TrackMatcher.miss(title, artist, durationMs, hits)
            LokiLogger.i("CatalogueLookup", "no match for '$artist - $title' (${durationMs}ms): $miss")
            Missed(miss)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            refuse("search for '$title' failed: ${e.message}")
        }
    }

    /** What [search] can answer: the track, or a [Miss]. */
    sealed interface Outcome
    data class Match(val id: String) : Outcome
    data class Missed(val miss: Miss) : Outcome

    /** The title, artists, album and cover of track [id]: from the search that matched it, or else its track page. */
    suspend fun info(id: String): TrackInfo? {
        seen[id]?.let { return it }
        if (refused != null) return null
        return try {
            val sess = session() ?: return refuse("no session")
            Song(sess).getSong(id)?.let { track ->
                TrackInfo(
                    track.name, track.artists.joinToString(", ") { it.name }, track.album.name, track.album.coverArtUrl,
                    durationMs = track.durationMs,
                )
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
