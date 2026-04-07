package ch.snepilatch.app.util

/**
 * Spotify's internal APIs (cluster state, metadata) return album/avatar
 * artwork as `spotify:image:<id>` URIs. Neither ExoPlayer, Coil, nor
 * java.net.URL can load those directly — they need to be rewritten to
 * the public `i.scdn.co` CDN first. This helper normalizes them.
 *
 * Passthrough for null, blank, and already-http(s) values.
 */
fun normalizeSpotifyImageUrl(raw: String?): String? {
    if (raw.isNullOrBlank()) return raw
    return if (raw.startsWith("spotify:image:")) {
        "https://i.scdn.co/image/" + raw.removePrefix("spotify:image:")
    } else raw
}
