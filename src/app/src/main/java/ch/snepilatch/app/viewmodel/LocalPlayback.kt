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
    pauseConnect()
    localPlayback.value = true
    val list = LocalFiles.tracks.value.map { it.toTrackInfo() }
    val index = list.indexOfFirst { it.uri == track.uri }.coerceAtLeast(0)
    OfflinePlayer.play(list, index, LocalFiles.LIBRARY_URI)
}

/**
 * Every download from the files, online too, so it costs no data and rides out a flaky connection (#895).
 * Offline nothing has to be left: the engine already owns playback and hands back to Connect when the signal returns.
 */
internal suspend fun PlaybackViewModel.startDownloadsPlayback(tracks: List<TrackInfo>, shuffle: Boolean) {
    if (tracks.isEmpty()) return
    val online = !isOffline.value
    if (online) {
        pauseConnect()
        localPlayback.value = true
    }
    val started = OfflinePlayer.play(tracks, if (shuffle) tracks.indices.random() else 0)
    if (started && shuffle) OfflinePlayer.toggleShuffle()
    if (!started && online) localPlayback.value = false
}

/** The screen may show paused while the server still has this phone playing; it must not go on. */
private suspend fun PlaybackViewModel.pauseConnect() {
    if (isOffline.value || localPlayback.value) return
    pauseAndWait()
    val server = SessionHolder.playerState.value
    if (server?.is_active_device == true && server.isActuallyPlaying) SessionHolder.player?.localPause(playback.value.positionMs)
}

/** Something from the service was tapped: the engine steps aside and Connect's echo drives playback again. */
internal fun PlaybackViewModel.leaveLocalPlayback() {
    localPlayback.value = false
    OfflinePlayer.clear()
    clearStream()
}
