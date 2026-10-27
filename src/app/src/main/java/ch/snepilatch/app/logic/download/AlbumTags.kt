package ch.snepilatch.app.logic.download

import ch.snepilatch.app.logic.shared.LokiLogger
import kotify.api.album.Album
import kotify.api.album.AlbumInfo
import kotify.api.song.Song
import kotify.config.DeviceProfile
import kotify.session.Session
import kotify.session.SessionConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * What players group an album by and order it with (#1038): the album's own artist, the track and disc
 * numbers with their totals, and the release date. Without the album artist a player falls back to each
 * track's artists, and an album with guests splits into several.
 */
internal data class AlbumTags(
    val albumArtist: String?,
    val trackNumber: Int?,
    val trackTotal: Int?,
    val discNumber: Int?,
    val discTotal: Int?,
    val date: String?,
)

/**
 * The catalogue session the downloads' lookups share: anonymous, so the account makes none of them (#930).
 * Made on first use and kept; one that cannot start is not kept, so the next lookup tries again.
 */
internal object AnonymousCatalogue {
    private var session: Session? = null
    private val starting = Mutex()

    suspend fun session(): Session = starting.withLock {
        session ?: Session(
            SessionConfig(identifier = "anonymous-catalogue", anonymous = true, deviceProfile = DeviceProfile.CHROME_ANDROID_DESKTOP)
        ).also {
            it.load()
            session = it
        }
    }
}

/**
 * [AlbumTags] of a track from its track page and its album's, the album asked once for all its tracks,
 * as when a whole album is downloaded or migrated.
 */
internal class AlbumTagsLookup(private val session: suspend () -> Session? = { AnonymousCatalogue.session() }) {

    private val albums = ConcurrentHashMap<String, AlbumInfo>()

    /** Null when the track page cannot be had; an album page that cannot leaves only its fields out. */
    suspend fun of(trackId: String): AlbumTags? = try {
        val sess = session()
        sess?.let { Song(it).getSong(trackId) }?.let { track ->
            val albumId = track.album.id.ifBlank { null }
            val album = albumId?.let { id -> albums[id] ?: albumOf(sess, id) }
            val disc = album?.tracks?.firstOrNull { it.uri == track.uri }?.discNumber
            AlbumTags(
                albumArtist = album?.artists?.joinToString(", ") { it.name }?.ifBlank { null },
                trackNumber = track.trackNumber.takeIf { it > 0 },
                // Per disc, as players count a track's total; the album's total when the discs are not known.
                trackTotal = disc?.let { d -> album.tracks.count { it.discNumber == d }.takeIf { it > 0 } } ?: track.album.totalTracks,
                discNumber = disc,
                discTotal = album?.tracks?.maxOfOrNull { it.discNumber }?.takeIf { it > 0 },
                date = releaseDate(track.album.releaseDateIso, track.album.releaseDatePrecision, track.album.releaseYear),
            )
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        LokiLogger.w("AlbumTags", "no album details for $trackId: ${e.message}")
        null
    }

    private suspend fun albumOf(session: Session, id: String): AlbumInfo? =
        runCatching { Album(session).getAlbum(id) }.getOrNull()?.also { albums[id] = it }

    /** "2022-08-29", "2022-08" or "2022", as precise as the catalogue knows it. */
    internal fun releaseDate(iso: String?, precision: String?, year: Int?): String? = when {
        iso.isNullOrBlank() -> year?.toString()
        precision.equals("YEAR", ignoreCase = true) -> iso.take(4)
        precision.equals("MONTH", ignoreCase = true) -> iso.take(7)
        else -> iso.take(10)
    }
}
