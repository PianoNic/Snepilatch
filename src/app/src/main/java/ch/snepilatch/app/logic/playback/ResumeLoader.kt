package ch.snepilatch.app.logic.playback

import ch.snepilatch.app.data.TrackInfo
import ch.snepilatch.app.logic.playback.engine.SpfyCdnResolver
import ch.snepilatch.app.logic.shared.AppSettings
import ch.snepilatch.app.logic.shared.LokiLogger
import kotify.cdn.StreamInfo
import kotify.cdn.StreamResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Loads a track at a position and plays it from there: the downloaded copy, the third-party source
 * when one is preferred, or the spfy CDN for the state machine's file id. The cold start and a
 * hand-back from another device share it. What the view model owns, the stream commit, the cache
 * key and the timing mark, stays behind [Hooks].
 */
class ResumeLoader(private val hooks: Hooks) {

    interface Hooks {
        val cdnResolver: SpfyCdnResolver?

        /** Marks when a play call went out, for the load timing log. */
        fun markPlayUrl()
        fun commitStream(uri: String, provider: String?)
        fun cacheKeyFor(uri: String, info: StreamInfo): String?
    }

    /**
     * Load [track] at [positionMs] and, unless [startPlaying] is off, play. [tag] prefixes the log
     * lines. Throws when the CDN path fails, so the caller picks its fallback.
     */
    suspend fun loadAt(fileId: String, track: TrackInfo, positionMs: Long, tag: String, startPlaying: Boolean = true) {
        val title = track.name.ifBlank { "Unknown" }
        val artist = track.artist.ifBlank { "Unknown" }
        val art = track.albumArt
        val resolver = checkNotNull(hooks.cdnResolver) { "CdnResolver not initialized" }
        // A downloaded copy plays from disk whatever the source, and this is the path the first
        // track after opening the app takes. The spfy branch below goes straight to the CDN, so
        // without this a downloaded track streamed on launch and only played locally once it had
        // been skipped to.
        if (loadLocal(track, title, artist, art, positionMs, tag, startPlaying) ||
            loadThirdParty(track, title, artist, art, positionMs, tag, startPlaying)
        ) {
            return
        }
        val stream = resolver.resolveForFileId(fileId)
        LokiLogger.i(TAG, "[$tag] resolved ${stream.mirrorCount} CDN mirrors")
        // Start ExoPlayer at the right position from the moment it's ready, no post-prepare seek
        // dance: setMediaItem(item, startPositionMs) guarantees STATE_READY fires at that position,
        // and playWhenReady=true makes audio start immediately.
        hooks.markPlayUrl()
        withContext(Dispatchers.Main) {
            MusicPlaybackService.instance?.playDrmUrl(
                stream.cdnUrl, stream.licenseUrl, stream.licenseHeaders, title, artist, art,
                startPlaying = startPlaying,
                startPositionMs = positionMs,
                pssh = stream.pssh,
            )
        }
        hooks.commitStream(track.uri, "Spotify CDN")
        LokiLogger.i(TAG, "[$tag] ExoPlayer loading at ${positionMs}ms${if (startPlaying) ", will start on STATE_READY" else ", paused"}")
    }

    /** The downloaded copy, resuming where the user left off. */
    private suspend fun loadLocal(
        track: TrackInfo,
        title: String,
        artist: String,
        art: String?,
        startPositionMs: Long,
        tag: String,
        startPlaying: Boolean,
    ): Boolean {
        val local = AudioSourceResolver.localOrNull(track.uri, title, artist) as? StreamResult.Success
            ?: return false
        hooks.markPlayUrl()
        withContext(Dispatchers.Main) {
            MusicPlaybackService.instance?.playUrl(
                local.info.url, title, artist, art,
                startPlaying = startPlaying, startPositionMs = startPositionMs
            )
        }
        hooks.commitStream(track.uri, AudioSourceResolver.LOCAL_PROVIDER)
        LokiLogger.i(TAG, "[$tag] playing the downloaded copy at ${startPositionMs}ms")
        return true
    }

    /**
     * The third-party chain, so resume-from-idle behaves like the rest of the lossless flow rather
     * than dropping to spfy's Widevine CDN.
     */
    private suspend fun loadThirdParty(
        track: TrackInfo,
        title: String,
        artist: String,
        art: String?,
        startPositionMs: Long,
        tag: String,
        startPlaying: Boolean,
    ): Boolean {
        if (AppSettings.preferredAudioSource.value == null) return false
        val query = listOf(artist, title).filter { it.isNotBlank() && it != "Unknown" }.joinToString(" ")
        val result = AudioSourceResolver.byQuery(track.uri, query, title, artist, track.durationMs)
        if (result !is StreamResult.Success) {
            LokiLogger.w(TAG, "[$tag] lossless resolve failed, falling back to the spfy CDN")
            return false
        }
        val info = result.info
        hooks.markPlayUrl()
        withContext(Dispatchers.Main) {
            val svc = MusicPlaybackService.instance ?: return@withContext
            val key = info.decryptionKey
            // An encrypted stream plays through the local proxy, which also carries its headers.
            if (key != null) {
                val proxied = svc.proxyUrlForDeezer(info.url, key, info.headers)
                svc.playUrl(proxied, title, artist, art, startPlaying = startPlaying, startPositionMs = startPositionMs)
            } else {
                svc.playUrl(
                    info.url, title, artist, art,
                    startPlaying = startPlaying, headers = info.headers, startPositionMs = startPositionMs,
                    cacheKey = hooks.cacheKeyFor(track.uri, info),
                )
            }
        }
        hooks.commitStream(track.uri, info.provider)
        LokiLogger.i(TAG, "[$tag] lossless (${info.provider}) loading at ${startPositionMs}ms")
        return true
    }

    private companion object {
        const val TAG = "ResumeLoader"
    }
}
