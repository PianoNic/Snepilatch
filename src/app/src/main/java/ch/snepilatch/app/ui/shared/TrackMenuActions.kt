package ch.snepilatch.app.ui.shared

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlaylistRemove
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Share
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import ch.snepilatch.app.R
import ch.snepilatch.app.data.TrackInfo
import ch.snepilatch.app.logic.shared.shareSpfyUri
import ch.snepilatch.app.viewmodel.DetailViewModel
import ch.snepilatch.app.viewmodel.PlaybackViewModel

/**
 * The context menu of a track wherever it opens: queue, playlist, an optional remove-from-playlist,
 * like, then the pages it makes sense to visit from here, and share. [close] runs before anything
 * navigates away, the way every row did by hand.
 */
@Composable
fun trackMenuActions(
    track: TrackInfo,
    vm: PlaybackViewModel,
    detailVm: DetailViewModel,
    close: () -> Unit,
    options: TrackMenuOptions = TrackMenuOptions(),
): List<MenuAction> {
    val context = LocalContext.current
    val shareLabel = stringResource(R.string.share)
    return listOfNotNull(
        MenuAction(Icons.AutoMirrored.Rounded.QueueMusic, stringResource(R.string.add_to_queue)) {
            vm.addToQueue(track.uri)
            close()
        },
        MenuAction(Icons.AutoMirrored.Rounded.PlaylistAdd, stringResource(R.string.add_to_playlist)) {
            close()
            vm.showPlaylistPickerForTrack(track.uri)
        },
        options.removeFromPlaylist?.let { remove ->
            MenuAction(Icons.Rounded.PlaylistRemove, stringResource(R.string.remove_from_playlist)) {
                close()
                remove()
            }
        },
        MenuAction(Icons.Rounded.Favorite, stringResource(R.string.like)) {
            vm.likeSong(track.uri.removePrefix("spotify:track:"))
            close()
        },
        MenuAction(Icons.Rounded.Radio, stringResource(R.string.go_to_song_radio)) {
            close()
            detailVm.openRadio(track.uri)
        }.takeIf { options.radio },
        MenuAction(Icons.Rounded.Album, stringResource(R.string.visit_album)) {
            close()
            detailVm.openAlbumForTrack(track.uri)
        }.takeIf { options.visitAlbum },
        MenuAction(Icons.Rounded.Person, stringResource(R.string.visit_artist)) {
            close()
            detailVm.openArtistForTrack(track.uri)
        }.takeIf { options.visitArtist },
        MenuAction(Icons.Rounded.Share, shareLabel) {
            close()
            shareSpfyUri(context, track.uri, shareLabel)
        },
    )
}
