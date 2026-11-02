package ch.snepilatch.app.viewmodel

import ch.snepilatch.app.data.TrackInfo
import ch.snepilatch.app.logic.download.LocalFiles
import ch.snepilatch.app.logic.playback.OfflinePlayer
import ch.snepilatch.app.logic.shared.SessionHolder

/**
 * A local file plays on the offline engine, on this phone only (#1070): whatever Connect had here is paused
 * first, the server never hears of the file, and the engine owns playback until something else is played.
 * The list on the page is the queue.
 */
internal suspend fun PlaybackViewModel.startLocalPlayback(track: TrackInfo) {
    if (!isOffline.value && !localPlayback.value) {
        pauseAndWait()
        // The screen may show paused while the server still has this phone playing; it must not go on.
        val server = SessionHolder.playerState.value
        if (server?.is_active_device == true && server.isActuallyPlaying) SessionHolder.player?.localPause(playback.value.positionMs)
    }
    localPlayback.value = true
    val list = LocalFiles.tracks.value.map { it.toTrackInfo() }
    val index = list.indexOfFirst { it.uri == track.uri }.coerceAtLeast(0)
    OfflinePlayer.play(list, index, LocalFiles.LIBRARY_URI)
}

/** Something from the service was tapped: the engine steps aside and Connect's echo drives playback again. */
internal fun PlaybackViewModel.leaveLocalPlayback() {
    localPlayback.value = false
    OfflinePlayer.clear()
    clearStream()
}
