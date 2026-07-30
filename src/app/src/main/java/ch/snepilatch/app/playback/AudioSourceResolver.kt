package ch.snepilatch.app.playback

import ch.snepilatch.app.viewmodel.AppSettings
import kotify.api.playerstatus.TrackChangeEvent
import kotify.cdn.CdnPlayback
import kotify.cdn.StreamInfo
import kotify.cdn.StreamResult

/**
 * Resolves a track to a playable stream for whichever audio source is selected, so callers never
 * branch on [AppSettings.preferredAudioSource] themselves. YouTube Music resolves in-app via
 * [YouTubeMusicSource]; everything else goes to KotifyClient's Qobuz/Deezer chain.
 *
 * The Spfy CDN path is not here: it is Widevine DRM and needs a different player call, so
 * PlaybackViewModel keeps it.
 */
object AudioSourceResolver {

    private val cdn = CdnPlayback()

    /** Resolve from a track-change event, which already carries title, artist and duration. */
    suspend fun fromTrack(event: TrackChangeEvent): StreamResult {
        val current = event.current
        return youTubeMusic(current?.name, current?.artistName, current?.durationMs ?: 0L)
            ?: cdn.resolveFromTrack(
                event,
                region = AppSettings.effectiveRegion(),
                preferredSource = AppSettings.preferredAudioSource.value,
            )
    }

    /** Resolve from metadata, for the cold-start, initial-track and pre-resolve paths. */
    suspend fun byQuery(
        trackId: String,
        searchQuery: String?,
        title: String?,
        artist: String?,
        durationMs: Long,
    ): StreamResult = youTubeMusic(title, artist, durationMs)
        ?: cdn.resolveStreamUrl(
            trackId,
            region = AppSettings.effectiveRegion(),
            searchQuery = searchQuery,
            preferredSource = AppSettings.preferredAudioSource.value,
        )

    /**
     * Null unless YouTube Music is the selected source, so callers fall through to the chain. A miss
     * is a [StreamResult.Failure] rather than null: null would hand the track to Qobuz/Deezer, and
     * silently swapping the source the user picked is what #480 removed.
     */
    private suspend fun youTubeMusic(title: String?, artist: String?, durationMs: Long): StreamResult? {
        if (AppSettings.preferredAudioSource.value != AppSettings.SOURCE_YTM) return null
        val stream = YouTubeMusicSource.resolve(
            title = title.orEmpty(),
            artist = artist.orEmpty(),
            region = AppSettings.effectiveRegion(),
            durationMs = durationMs,
        ) ?: return StreamResult.Failure(
            "No YouTube Music match for ${listOfNotNull(artist, title).joinToString(" ")}"
        )
        return StreamResult.Success(
            StreamInfo(
                url = stream.url,
                provider = "YouTube Music",
                mimeType = stream.mimeType,
                headers = stream.headers,
            )
        )
    }
}
