package ch.snepilatch.app.playback

import ch.snepilatch.app.download.DownloadFolder
import ch.snepilatch.app.download.Downloads
import ch.snepilatch.app.util.LokiLogger
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

    private const val TAG = "AudioSource"

    /**
     * Marks a [StreamInfo] whose url is a SAF document rather than a network url. Callers check it to
     * keep a local file off the HTTP data source and out of the playback cache.
     */
    const val LOCAL_PROVIDER = "Local"

    private val cdn = CdnPlayback()

    /** Resolve from a track-change event, which already carries title, artist and duration. */
    suspend fun fromTrack(event: TrackChangeEvent, trackUri: String): StreamResult {
        val current = event.current
        return localOrNull(trackUri, current?.name, current?.artistName)
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
    ): StreamResult = localOrNull(trackUri, title, artist)
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
    fun localOrNull(trackUri: String, title: String? = null, artist: String? = null): StreamResult? {
        // Without a folder there is nothing to open. The grant can be revoked from system settings,
        // and then exists() throws a SecurityException that reads back as "cannot tell" below — which
        // would hand playback an unopenable document uri and burn a playback-error retry per track.
        if (!DownloadFolder.isConfigured) return null
        // The uri is the reliable key; title/artist only stand in when it misses, because the same
        // song is in the catalogue under more than one id (separate releases, or Spotify's per-market
        // instances). Without it a downloaded track streams whenever it turns up under the other id.
        val local = Downloads.find(trackUri)
            ?: title?.takeIf { it.isNotBlank() }?.let { Downloads.findByMetadata(it, artist.orEmpty()) }
                ?.also { LokiLogger.i(TAG, "HIT  $trackUri via title/artist, downloaded as ${it.trackUri}") }
        if (local == null) {
            // No relink hint here: the metadata lookup above already ran and missed, so asking again
            // could only produce the same null while costing another walk of every indexed row.
            LokiLogger.i(TAG, "MISS $trackUri (${Downloads.downloaded.value.size} downloaded)")
            return null
        }
        // Only a definite "not there" drops the row. An inconclusive check means we cannot tell, and
        // forgetting a download the user still has is far worse than trying to play it and failing.
        when (DownloadFolder.exists(local.documentUri)) {
            false -> {
                // The row's own uri, not the requested one — a metadata match found it under another.
                LokiLogger.w(TAG, "GONE $trackUri, file missing from the folder, dropping the row")
                Downloads.remove(local.trackUri)
                return null
            }
            null -> LokiLogger.w(TAG, "cannot check the folder yet, using the indexed copy anyway")
            true -> Unit
        }
        LokiLogger.i(TAG, "HIT  $trackUri -> ${local.documentUri.substringAfterLast("%2F")}")
        return StreamResult.Success(
            StreamInfo(url = local.documentUri, provider = LOCAL_PROVIDER, mimeType = local.mimeType)
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
