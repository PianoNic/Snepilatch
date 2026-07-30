package ch.snepilatch.app.playback

import ch.snepilatch.app.download.DownloadFolder
import ch.snepilatch.app.download.Downloads
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
    suspend fun fromTrack(event: TrackChangeEvent, trackUri: String): StreamResult {
        val current = event.current
        return localOrNull(trackUri)
            ?: youTubeMusic(
                title = current?.name,
                artist = current?.artistName,
                durationMs = current?.durationMs ?: 0L,
                source = AppSettings.preferredAudioSource.value,
            )
            ?: cdn.resolveFromTrack(
                event,
                region = AppSettings.effectiveRegion(),
                preferredSource = AppSettings.preferredAudioSource.value,
            )
    }

    /** Resolve from metadata, for the cold-start, initial-track and pre-resolve paths. */
    suspend fun byQuery(
        trackUri: String,
        searchQuery: String?,
        title: String?,
        artist: String?,
        durationMs: Long,
        source: String? = AppSettings.preferredAudioSource.value,
    ): StreamResult = localOrNull(trackUri)
        ?: youTubeMusic(title, artist, durationMs, source)
        ?: cdn.resolveStreamUrl(
            trackUri.substringAfterLast(':'),
            region = AppSettings.effectiveRegion(),
            searchQuery = searchQuery,
            preferredSource = source,
        )

    /**
     * A downloaded copy wins over every source, including Spfy: the user asked for this track on
     * disk, so it plays from disk wherever it turns up. A row whose file has since been deleted from
     * the folder is dropped rather than played.
     */
    fun localOrNull(trackUri: String): StreamResult? {
        val local = Downloads.find(trackUri) ?: return null
        if (!DownloadFolder.exists(local.documentUri)) {
            Downloads.remove(trackUri)
            return null
        }
        return StreamResult.Success(
            StreamInfo(url = local.documentUri, provider = "Local", mimeType = local.mimeType)
        )
    }

    /**
     * Null unless YouTube Music is the selected source, so callers fall through to the chain. A miss
     * is a [StreamResult.Failure] rather than null: null would hand the track to Qobuz/Deezer, and
     * silently swapping the source the user picked is what #480 removed.
     */
    private suspend fun youTubeMusic(
        title: String?,
        artist: String?,
        durationMs: Long,
        source: String?,
    ): StreamResult? {
        if (source != AppSettings.SOURCE_YTM) return null
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
