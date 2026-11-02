package ch.snepilatch.app.viewmodel

import androidx.lifecycle.viewModelScope
import ch.snepilatch.app.R
import ch.snepilatch.app.logic.shared.AppMessages
import ch.snepilatch.app.logic.shared.LokiLogger
import ch.snepilatch.app.logic.shared.SessionHolder
import kotify.api.playlist.Playlist
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val TAG = "PlaylistAdds"

/** Adding [uris] to a playlist that already holds some: [newUris] are the ones it does not. */
data class DuplicateAdd(val playlistId: String, val playlistName: String, val uris: List<String>, val newUris: List<String>)

/**
 * Add many tracks to a playlist in a single API call, as the detail header's "Add to Playlist" does for a
 * whole album, playlist or Liked Songs. As in the web player's add menu, what the playlist already holds
 * is asked about first (#710), and a check that fails does not stop the add.
 */
fun PlaybackViewModel.addTracksToPlaylist(playlistId: String, trackUris: List<String>, playlistName: String) {
    if (trackUris.isEmpty()) return
    writing {
        val duplicates = try {
            it.duplicatesIn(playlistId, trackUris)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LokiLogger.w(TAG, "Duplicate check failed: ${e.message}")
            emptyList()
        }
        if (duplicates.isEmpty()) {
            it.write(playlistId, trackUris, playlistName)
        } else {
            duplicateAdd.value = DuplicateAdd(playlistId, playlistName, trackUris, trackUris - duplicates.toSet())
        }
    }
}

/** The user's answer to [PlaybackViewModel.duplicateAdd]: add [trackUris] whatever the playlist holds. */
fun PlaybackViewModel.addDespiteDuplicates(add: DuplicateAdd, trackUris: List<String>) {
    duplicateAdd.value = null
    writing { it.write(add.playlistId, trackUris, add.playlistName) }
}

/** The "Saved in" sheet changed whether [trackUri] is liked; the player's heart follows. */
fun PlaybackViewModel.likedChanged(trackUri: String, liked: Boolean) {
    if (playback.value.track?.uri == trackUri) currentTrackLiked.value = liked
}

private fun PlaybackViewModel.writing(block: suspend (Playlist) -> Unit) {
    val session = SessionHolder.session ?: return
    viewModelScope.launch(Dispatchers.IO) {
        try {
            block(Playlist(session))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LokiLogger.e(TAG, "add to playlist", e)
            AppMessages.show(R.string.error_add_playlist)
        }
    }
}

private suspend fun Playlist.write(playlistId: String, trackUris: List<String>, playlistName: String) {
    addToPlaylist(playlistId, trackUris)
    LokiLogger.i(TAG, "Added ${trackUris.size} tracks to playlist $playlistId")
    AppMessages.show(R.string.added_to_playlist, playlistName)
}
