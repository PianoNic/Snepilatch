package ch.snepilatch.app.logic.playback

import ch.snepilatch.app.logic.download.LocalFiles
import ch.snepilatch.app.logic.download.DownloadFolder
import ch.snepilatch.app.logic.download.Downloads
import ch.snepilatch.app.logic.shared.LokiLogger
import ch.snepilatch.app.logic.shared.AppSettings
import kotify.api.playerstatus.TrackChangeEvent
import kotify.cdn.CdnPlayback
import kotify.cdn.StreamInfo
import kotify.cdn.StreamResult
import ch.snepilatch.app.logic.shared.spfyId

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
    private const val SOURCES_KEPT = 200

    /**
     * Marks a [StreamInfo] whose url is a SAF document rather than a network url. Callers check it to
     * keep a local file off the HTTP data source and out of the playback cache.
     */
    const val LOCAL_PROVIDER = "Local"

    private val cdn = CdnPlayback()

    /**
     * The file sources a track is tried on before Spfy's own stream, in the user's order (#1132). Empty
     * when Spfy comes first: then Spfy plays, and the sources after it are PlaybackViewModel's fallback.
     */
    fun fileSourcesFirst(order: List<String> = AppSettings.sourceOrder.value): List<String> =
        order.takeWhile { it != AppSettings.SOURCE_SPFY }

    // Which source each recently resolved track came from, so its cache entry and a save of what was
    // just heard look under that source, not the first in the order.
    private val sourceByTrack = object : LinkedHashMap<String, String>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > SOURCES_KEPT
    }

    /** The source [trackUri] last resolved from, or null when it has not resolved from one. */
    fun sourceOf(trackUri: String): String? = synchronized(sourceByTrack) { sourceByTrack[trackUri] }

    private fun remember(trackUri: String, source: String, result: StreamResult): StreamResult {
        if (result is StreamResult.Success) synchronized(sourceByTrack) { sourceByTrack[trackUri] = source }
        return result
    }

    /** Resolve on one file [source] from a track-change event, which already carries title, artist and duration. */
    suspend fun fromTrack(event: TrackChangeEvent, trackUri: String, source: String): StreamResult {
        val current = event.current
        val result = youTubeMusic(
            title = current?.name,
            artist = current?.artistName,
            durationMs = current?.durationMs ?: 0L,
            source = source,
        ) ?: cdn.resolveFromTrack(event, region = AppSettings.effectiveRegion(), preferredSource = source)
        return remember(trackUri, source, result)
    }

    /**
     * Resolve from metadata, for the cold-start, initial-track and pre-resolve paths: a downloaded copy,
     * else [sources] in turn until one has it. A null source is Spfy's own chain, as before the order.
     * [forDownload] lets the lossless chain ask Apple Music first (#1133).
     */
    suspend fun byQuery(
        trackUri: String,
        searchQuery: String?,
        title: String?,
        artist: String?,
        durationMs: Long,
        sources: List<String?> = fileSourcesFirst().ifEmpty { listOf(null) },
        forDownload: Boolean = false,
    ): StreamResult {
        localOrNull(trackUri, title, artist)?.let { return it }
        var result: StreamResult = StreamResult.Failure("No source to try")
        for (source in sources) {
            result = oneByQuery(trackUri, searchQuery, title, artist, durationMs, source, forDownload)
            if (result is StreamResult.Success) return if (source == null) result else remember(trackUri, source, result)
        }
        return result
    }

    private suspend fun oneByQuery(
        trackUri: String,
        searchQuery: String?,
        title: String?,
        artist: String?,
        durationMs: Long,
        source: String?,
        forDownload: Boolean,
    ): StreamResult = youTubeMusic(title, artist, durationMs, source)
        // With the title, artist and length the lossless hit is checked against the track (#1053); a first
        // hit taken as it came was often another song of the name, and was downloaded as this one.
        ?: if (!title.isNullOrBlank()) {
            cdn.resolveTrack(
                title, artist.orEmpty(), durationMs,
                region = AppSettings.effectiveRegion(), preferredSource = source, forDownload = forDownload,
            )
        } else {
            cdn.resolveStreamUrl(
                spfyId(trackUri),
                region = AppSettings.effectiveRegion(),
                searchQuery = searchQuery,
                preferredSource = source,
            )
        }

    /**
     * A downloaded copy wins over every source, including Spfy: the user asked for this track on
     * disk, so it plays from disk wherever it turns up. A row whose file has since been deleted from
     * the folder is dropped rather than played.
     */
    fun localOrNull(trackUri: String, title: String? = null, artist: String? = null): StreamResult? {
        if (trackUri.startsWith(LocalFiles.URI_PREFIX)) {
            val file = LocalFiles.find(trackUri) ?: return null
            return StreamResult.Success(StreamInfo(url = file.documentUri, provider = LOCAL_PROVIDER, mimeType = file.mimeType))
        }
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
