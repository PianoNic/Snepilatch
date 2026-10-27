package ch.snepilatch.app.logic.relay

import ch.snepilatch.app.data.TrackInfo
import ch.snepilatch.app.logic.shared.SessionHolder
import ch.snepilatch.app.logic.shared.spfyId
import ch.snepilatch.app.viewmodel.PlaybackViewModel
import kotify.api.song.Song

/**
 * A remote-only guest plays nothing itself, so its player shows the host's playback instead: the
 * track (looked up once per change), position and pause state, the way a Spfy jam's controller sees
 * the host through its cluster.
 */
internal class RelayRemoteView(private val vm: PlaybackViewModel, private val serverNow: () -> Long) {
    private var shown: TrackInfo? = null

    suspend fun show(playback: RelayPlayback) {
        val uri = playback.trackUri
        if (uri != shown?.uri) shown = uri?.let { lookup(it) }
        val current = vm._playback.value
        vm._playback.value = current.copy(
            track = shown,
            isPlaying = !playback.paused,
            isPaused = playback.paused,
            positionMs = playback.positionAt(serverNow()),
            durationMs = playback.durationMs,
        )
    }

    private suspend fun lookup(uri: String): TrackInfo? {
        val session = SessionHolder.session ?: return null
        val song = runCatching { Song(session).getSong(spfyId(uri)) }.getOrNull() ?: return null
        return TrackInfo(
            uri = uri,
            name = song.name,
            artist = song.artists.joinToString(", ") { it.name },
            albumArt = song.album.coverArtUrl,
            durationMs = song.durationMs,
            albumName = song.album.name,
        )
    }
}
