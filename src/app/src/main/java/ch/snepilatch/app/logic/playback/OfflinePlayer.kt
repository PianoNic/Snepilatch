package ch.snepilatch.app.logic.playback

import ch.snepilatch.app.data.TrackInfo
import ch.snepilatch.app.logic.shared.LokiLogger
import kotify.cdn.StreamResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/** What the offline player is on: the list it was started from, where in it, and how it plays. */
data class OfflinePlayback(
    val tracks: List<TrackInfo>,
    val index: Int,
    val isPlaying: Boolean,
    val durationMs: Long,
) {
    val current: TrackInfo? get() = tracks.getOrNull(index)
}

/**
 * The offline state machine. Without a session there is no Connect and no server-side state
 * machine, so this owns what the online one would: the list, the pointer into it, playing or
 * paused, the duration. It drives [MusicPlaybackService] straight from the downloaded files and
 * publishes [state]; the playback view model mirrors that into the one playback state every screen
 * reads, so the player UI needs no offline branch of its own. Process-scoped like
 * [ch.snepilatch.app.logic.shared.SessionHolder], so an offline cold launch reaches it too.
 *
 * #789 is the state and starting a track, #790 the transport; the queue, signal loss and the way
 * back to Connect follow (#791 to #793).
 */
object OfflinePlayer {

    private val _state = MutableStateFlow<OfflinePlayback?>(null)
    val state: StateFlow<OfflinePlayback?> = _state.asStateFlow()

    /** Seams for tests: where a track's local file comes from and which service plays it. */
    internal var localFile: (uri: String, title: String, artist: String) -> String? = ::localCopyOf
    internal var service: () -> MusicPlaybackService? = { MusicPlaybackService.instance }

    /** Start [tracks] at [index] from its downloaded copy. False when that track has none. */
    suspend fun play(tracks: List<TrackInfo>, index: Int): Boolean {
        val track = tracks.getOrNull(index) ?: return false
        val title = track.name.ifBlank { "Unknown" }
        val artist = track.artist.ifBlank { "Unknown" }
        val url = localFile(track.uri, title, artist) ?: return false
        withContext(Dispatchers.Main) {
            val svc = service() ?: return@withContext
            svc.stop()
            // Nothing to record: the file is already on disk.
            svc.stopCapture()
            svc.playUrl(url, title, artist, track.albumArt, startPlaying = true)
        }
        _state.value = OfflinePlayback(tracks, index, isPlaying = true, durationMs = track.durationMs)
        LokiLogger.i(TAG, "Playing the downloaded copy of ${track.uri} (${index + 1} of ${tracks.size})")
        return true
    }

    /** ExoPlayer knows the real length once the file is open; the index may not have had it. */
    fun durationKnown(durationMs: Long) {
        if (durationMs <= 0) return
        _state.update { it?.copy(durationMs = durationMs) }
    }

    /** The file ran out. Until the queue lands (#791), playback stops here. */
    fun ended() {
        _state.update { it?.copy(isPlaying = false) }
    }

    /** Pause or resume what is loaded; ExoPlayer keeps the position. */
    suspend fun togglePlayPause() {
        val s = _state.value ?: return
        withContext(Dispatchers.Main) {
            val svc = service() ?: return@withContext
            if (s.isPlaying) svc.syncPause() else svc.syncPlay(svc.getCurrentPosition())
        }
        _state.update { it?.copy(isPlaying = !s.isPlaying) }
    }

    suspend fun seekTo(positionMs: Long) {
        if (_state.value == null) return
        withContext(Dispatchers.Main) { service()?.syncSeek(positionMs) }
    }

    /** The next track of the list, if there is one; at the end nothing changes. */
    suspend fun next(): Boolean {
        val s = _state.value ?: return false
        return play(s.tracks, s.index + 1)
    }

    /**
     * Back to the start of the current track, or to the previous one when the track has only just
     * begun, the same threshold the online player uses.
     */
    suspend fun previous(): Boolean {
        val s = _state.value ?: return false
        val position = withContext(Dispatchers.Main) { service()?.getCurrentPosition() ?: 0L }
        if (position > PREV_RESTART_THRESHOLD_MS || s.index == 0) {
            seekTo(0L)
            return false
        }
        return play(s.tracks, s.index - 1)
    }

    fun clear() {
        _state.value = null
    }

    private fun localCopyOf(uri: String, title: String, artist: String): String? =
        (AudioSourceResolver.localOrNull(uri, title, artist) as? StreamResult.Success)?.info?.url

    private const val TAG = "OfflinePlayer"
    private const val PREV_RESTART_THRESHOLD_MS = 3000L
}
