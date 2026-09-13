package ch.snepilatch.app.logic.playback

import ch.snepilatch.app.data.PlaybackUiState
import ch.snepilatch.app.data.TrackInfo
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Writes the offline engine's state into the flows every screen reads, so the mini player, the
 * full player, the position bar and the queue sheet need no offline branch of their own.
 */
object OfflineMirror {

    /** Applies [s]; true when the current track is a different one than before. */
    fun apply(
        s: OfflinePlayback,
        playback: MutableStateFlow<PlaybackUiState>,
        queue: MutableStateFlow<List<TrackInfo>>,
        queuedCount: MutableStateFlow<Int>,
        nextPreview: MutableStateFlow<TrackInfo?>,
        prevPreview: MutableStateFlow<TrackInfo?>,
    ): Boolean {
        val cur = playback.value
        val sameTrack = cur.track?.uri == s.current?.uri
        playback.value = cur.copy(
            track = s.current,
            isPlaying = s.isPlaying,
            isPaused = !s.isPlaying,
            durationMs = s.durationMs,
            positionMs = if (sameTrack) cur.positionMs else 0L,
            shuffleMode = if (s.shuffle) "on" else "off",
            isShuffling = s.shuffle,
            repeatMode = s.repeat,
        )
        queue.value = s.upcoming
        queuedCount.value = 0
        nextPreview.value = s.upcoming.firstOrNull()
        prevPreview.value = s.tracks.getOrNull(s.index - 1)
        return !sameTrack
    }
}
