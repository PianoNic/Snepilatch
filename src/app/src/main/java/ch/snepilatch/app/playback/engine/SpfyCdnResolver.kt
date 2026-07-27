package ch.snepilatch.app.playback.engine

import kotify.cdn.SpfyPlayback
import kotify.session.Session

/**
 * Bundles a resolved Spfy CDN URL with the Widevine license metadata
 * needed to feed it into ExoPlayer.
 */
data class SpfyStream(
    val cdnUrl: String,
    val licenseUrl: String,
    val licenseHeaders: Map<String, String>,
    val mirrorCount: Int,
    // Base64 Widevine PSSH from the seektable. Many Spfy files don't embed a Widevine pssh box, so
    // we inject this one into ExoPlayer's DRM session (see PsshInjectingDrmSessionManager). Null if the
    // seektable lookup failed — playback then falls back to the file's own (possibly missing) pssh.
    val pssh: String? = null
)

/**
 * Resolves Spfy CDN URLs for a given file id and builds the Widevine
 * license request metadata. Extracted from PlaybackViewModel to remove
 * duplication between coldStartPlay and resolveAndPlay.
 */
class SpfyCdnResolver(
    private val session: Session,
    private val spfyPlayback: SpfyPlayback
) {
    /**
     * Resolve a CDN URL for a known file id plus Widevine license metadata.
     * Throws if no mirrors are returned by Spfy.
     *
     * [mirrorIndex] selects which of the mirrors `storage-resolve` returns to use (wrapping). The
     * default 0 is the primary. On a playback error we re-resolve with an incrementing index so a
     * retry lands on a DIFFERENT CDN edge — hammering the same dead mirror is what left a bad edge
     * stuck in silence.
     */
    suspend fun resolveForFileId(fileId: String, mirrorIndex: Int = 0): SpfyStream {
        val cdnUrls = spfyPlayback.getCdnUrls(fileId)
        if (cdnUrls.isEmpty()) throw IllegalStateException("No CDN mirrors for fileId=$fileId")
        val cdnUrl = cdnUrls[mirrorIndex.mod(cdnUrls.size)]
        return SpfyStream(
            cdnUrl = cdnUrl,
            licenseUrl = session.spclientUrl("widevine-license/v1/audio/license"),
            licenseHeaders = buildLicenseHeaders(),
            mirrorCount = cdnUrls.size,
            pssh = runCatching { spfyPlayback.getPssh(fileId) }.getOrNull()
        )
    }

    /**
     * Wrap a previously-resolved CDN URL (from the pre-resolve cache) in the
     * Widevine license metadata needed to hand it to ExoPlayer, without
     * re-hitting getCdnUrls. Use when the caller already has a valid URL and
     * only needs fresh license headers.
     */
    suspend fun buildStreamForCachedUrl(cdnUrl: String, fileId: String?): SpfyStream = SpfyStream(
        cdnUrl = cdnUrl,
        licenseUrl = session.spclientUrl("widevine-license/v1/audio/license"),
        licenseHeaders = buildLicenseHeaders(),
        mirrorCount = 1,
        pssh = fileId?.let { id -> runCatching { spfyPlayback.getPssh(id) }.getOrNull() }
    )

    /**
     * Resolve a podcast episode via the web player's `soundfinder/v1/unauth/episode` endpoint. The
     * result says whether the episode is passthrough (a direct DRM-free url) or Widevine-hosted (a
     * file id), so the caller can skip DRM entirely for passthrough episodes. Null if it doesn't resolve.
     */
    suspend fun resolveEpisode(episodeId: String): kotify.cdn.EpisodeResolveInfo? =
        spfyPlayback.resolveEpisode(episodeId)

    /**
     * Fetch the audio file id for a track URI via `track-playback/v1/media` — the endpoint the web
     * player's local ListPlayer uses. This is the reliable self-resolve path: [fetchFileIdFromMetadata]
     * (`metadata/4/track`) returns only cover-art ids on many accounts. Used to start a tapped track
     * immediately, without waiting for the connect-state command echo to push the file id.
     */
    suspend fun fetchFileIdFromMedia(trackUri: String): String? =
        spfyPlayback.resolveFileIdForUri(trackUri)

    /**
     * All mp4 audio (file id, numeric format) entries the media endpoint offers for a track — usually
     * both a premium MP4_256 (format 11) and a free-safe MP4_128 (format 10). Callers pick the quality
     * the account can license: a free account can only get a Widevine license for MP4_128, so handing
     * its CDM the 256 file id yields ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED. Empty if unresolvable.
     */
    suspend fun resolveMediaEntries(trackUri: String): List<Pair<String, String>> =
        spfyPlayback.resolveAudioEntriesForUri(trackUri)

    /**
     * Fetch the file id for a track via the metadata API, preferring the
     * higher-quality MP4_256 variant and falling back to MP4_128.
     */
    suspend fun fetchFileIdFromMetadata(trackUri: String): String? {
        // metadata/4/track is track-only; a non-track uri (e.g. spotify:episode:) would build a
        // malformed gid lookup. Episodes resolve their audio from the state machine instead.
        if (!trackUri.startsWith("spotify:track:")) return null
        val trackId = trackUri.removePrefix("spotify:track:")
        val gid = spfyPlayback.trackIdToGid(trackId)
        val meta = spfyPlayback.getTrackMetadata(gid)
        val mp4File = spfyPlayback.findFile(meta, SpfyPlayback.AudioQuality.MP4_128)
            ?: spfyPlayback.findFile(meta, SpfyPlayback.AudioQuality.MP4_256)
        return mp4File?.fileId
    }

    private fun buildLicenseHeaders(): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        session.baseClient.accessToken?.let { headers["Authorization"] = "Bearer $it" }
        session.baseClient.clientToken?.let { headers["client-token"] = it }
        return headers
    }
}
