package ch.snepilatch.app.playback.engine

import kotify.cdn.SpotifyPlayback
import kotify.session.Session

/**
 * Bundles a resolved Spotify CDN URL with the Widevine license metadata
 * needed to feed it into ExoPlayer.
 */
data class SpotifyStream(
    val cdnUrl: String,
    val licenseUrl: String,
    val licenseHeaders: Map<String, String>,
    val mirrorCount: Int
)

/**
 * Resolves Spotify CDN URLs for a given file id and builds the Widevine
 * license request metadata. Extracted from SpotifyViewModel to remove
 * duplication between coldStartPlay and resolveAndPlay.
 */
class SpotifyCdnResolver(
    private val session: Session,
    private val spotifyPlayback: SpotifyPlayback
) {
    /**
     * Resolve a CDN URL for a known file id plus Widevine license metadata.
     * Throws if no mirrors are returned by Spotify.
     */
    suspend fun resolveForFileId(fileId: String): SpotifyStream {
        val cdnUrls = spotifyPlayback.getCdnUrls(fileId)
        val cdnUrl = cdnUrls.firstOrNull()
            ?: throw IllegalStateException("No CDN mirrors for fileId=$fileId")
        return SpotifyStream(
            cdnUrl = cdnUrl,
            licenseUrl = session.spclientUrl("widevine-license/v1/audio/license"),
            licenseHeaders = buildLicenseHeaders(),
            mirrorCount = cdnUrls.size
        )
    }

    /**
     * Fetch the file id for a track via the metadata API, preferring the
     * higher-quality MP4_256 variant and falling back to MP4_128.
     */
    suspend fun fetchFileIdFromMetadata(trackUri: String): String? {
        val trackId = trackUri.removePrefix("spotify:track:")
        val gid = spotifyPlayback.trackIdToGid(trackId)
        val meta = spotifyPlayback.getTrackMetadata(gid)
        val mp4File = spotifyPlayback.findFile(meta, SpotifyPlayback.AudioQuality.MP4_128)
            ?: spotifyPlayback.findFile(meta, SpotifyPlayback.AudioQuality.MP4_256)
        return mp4File?.fileId
    }

    private fun buildLicenseHeaders(): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        session.baseClient.accessToken?.let { headers["Authorization"] = "Bearer $it" }
        session.baseClient.clientToken?.let { headers["client-token"] = it }
        return headers
    }
}
