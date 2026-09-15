package ch.snepilatch.app.viewmodel

import androidx.lifecycle.viewModelScope
import ch.snepilatch.app.R
import ch.snepilatch.app.logic.shared.LokiLogger
import ch.snepilatch.app.logic.shared.SessionHolder
import ch.snepilatch.app.logic.shared.spfyId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotify.api.playlist.Playlist

/**
 * Taking the playing track out of the playlist it came from (#851). Only the playlist screen could
 * do this before, so a track added twice from the player could not be undone from there.
 */

/** True when the player may offer the removal: own playlist, and the row it plays is known. */
fun PlaybackViewModel.canRemovePlayingFromPlaylist(): Boolean {
    val context = playingContext.value ?: return false
    return context.ownedByUser &&
        context.uri?.contains(":playlist:") == true &&
        playback.value.track?.uid != null
}

/**
 * Remove the playing track's row from the playlist it plays from. Keyed on the row's uid, so a
 * playlist holding the song twice loses only the copy that is playing. Playback is left alone,
 * which is what the web player does as well.
 */
fun PlaybackViewModel.removePlayingFromPlaylist() {
    if (!canRemovePlayingFromPlaylist()) return
    val contextUri = playingContext.value?.uri ?: return
    val uid = playback.value.track?.uid ?: return
    val playlistId = spfyId(contextUri)
    viewModelScope.launch(Dispatchers.IO) {
        val session = SessionHolder.session ?: return@launch
        try {
            Playlist(session).removeFromPlaylist(playlistId, listOf(uid))
            // Our own write leaves the cached pages describing a playlist that no longer has it.
            SessionHolder.playlistStore?.invalidate(playlistId)
            emitMessage(R.string.removed_from_playlist)
        } catch (e: Exception) {
            LokiLogger.e("Playback", "removePlayingFromPlaylist", e)
            emitMessage(R.string.error_remove_playlist)
        }
    }
}
