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
    val mirrorCount: Int,
    // Base64 Widevine PSSH from the seektable. Many Spotify files don't embed a Widevine pssh box, so
    // we inject this one into ExoPlayer's DRM session (see PsshInjectingDrmSessionManager). Null if the
    // seektable lookup failed — playback then falls back to the file's own (possibly missing) pssh.
    val pssh: String? = null
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
            mirrorCount = cdnUrls.size,
            pssh = runCatching { spotifyPlayback.getPssh(fileId) }.getOrNull()
        )
    }

    /**
     * Wrap a previously-resolved CDN URL (from the pre-resolve cache) in the
     * Widevine license metadata needed to hand it to ExoPlayer, without
     * re-hitting getCdnUrls. Use when the caller already has a valid URL and
     * only needs fresh license headers.
     */
    suspend fun buildStreamForCachedUrl(cdnUrl: String, fileId: String?): SpotifyStream = SpotifyStream(
        cdnUrl = cdnUrl,
        licenseUrl = session.spclientUrl("widevine-license/v1/audio/license"),
        licenseHeaders = buildLicenseHeaders(),
        mirrorCount = 1,
        pssh = fileId?.let { id -> runCatching { spotifyPlayback.getPssh(id) }.getOrNull() }
    )

    /**
     * Fetch the audio file id for a track URI via `track-playback/v1/media` — the endpoint the web
     * player's local ListPlayer uses. This is the reliable self-resolve path: [fetchFileIdFromMetadata]
     * (`metadata/4/track`) returns only cover-art ids on many accounts. Used to start a tapped track
     * immediately, without waiting for the connect-state command echo to push the file id.
     */
    suspend fun fetchFileIdFromMedia(trackUri: String): String? =
        spotifyPlayback.resolveFileIdForUri(trackUri)

    /**
     * Fetch the file id for a track via the metadata API, preferring the
     * higher-quality MP4_256 variant and falling back to MP4_128.
     */
    suspend fun fetchFileIdFromMetadata(trackUri: String): String? {
        // metadata/4/track is track-only; a non-track uri (e.g. spotify:episode:) would build a
        // malformed gid lookup. Episodes resolve their audio from the state machine instead.
        if (!trackUri.startsWith("spotify:track:")) return null
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
