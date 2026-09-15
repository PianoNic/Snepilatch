package ch.snepilatch.app.ui.shared

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import ch.snepilatch.app.R
import ch.snepilatch.app.data.PlayerShortcut
import ch.snepilatch.app.data.TrackInfo
import ch.snepilatch.app.logic.download.Downloads
import ch.snepilatch.app.logic.shared.JamHolder
import ch.snepilatch.app.logic.shared.shareSpfyUri
import ch.snepilatch.app.viewmodel.DetailRoutes
import ch.snepilatch.app.viewmodel.PlaybackViewModel

/**
 * One action per [PlayerShortcut] except LIKE (which keeps its toggle button). The player's three
 * dots sheet, its configurable button and the swipes on a track row all consume this, so they never
 * drift.
 */
class PlayerAction(
    val shortcut: PlayerShortcut,
    val icon: ImageVector,
    val label: String,
    val enabled: Boolean,
    val run: () -> Unit,
)

/** The sheets an action opens. The caller hosts them, since the player and a list row show them differently. */
enum class PlayerOverlay { PlaylistPicker, Jam, Code }

/** What the actions act on; one holder instead of the same seven values passed to every action. */
class PlayerActionScope(
    val vm: PlaybackViewModel,
    val track: TrackInfo?,
    val context: Context,
    val shareTrackLabel: String,
    val inJam: Boolean,
    val isDownloaded: Boolean,
    val onOpen: (PlayerOverlay) -> Unit,
) {
    fun run(shortcut: PlayerShortcut) {
        val uri = track?.uri
        when (shortcut) {
            PlayerShortcut.LIKE -> Unit
            PlayerShortcut.LYRICS -> vm.openLyrics()
            PlayerShortcut.ADD_TO_QUEUE -> uri?.let { vm.addToQueue(it) }
            PlayerShortcut.ADD_TO_PLAYLIST -> onOpen(PlayerOverlay.PlaylistPicker)
            PlayerShortcut.QUEUE -> vm.openQueue()
            // Via the router: the actions are built outside a composable that owns a DetailViewModel.
            PlayerShortcut.ALBUM -> uri?.let { DetailRoutes.openAlbumForTrack(it) }
            PlayerShortcut.RADIO -> uri?.let { DetailRoutes.openRadio(it) }
            PlayerShortcut.DOWNLOAD -> track?.let { if (isDownloaded) vm.removeDownload(it.uri) else vm.downloadTrack(it, context) }
            PlayerShortcut.JAM -> if (inJam) vm.openQueue() else onOpen(PlayerOverlay.Jam)
            PlayerShortcut.CODE -> onOpen(PlayerOverlay.Code)
            PlayerShortcut.SHARE -> uri?.let { shareSpfyUri(context, it, shareTrackLabel) }
        }
    }
}

/** The actions for [track], with the download label and glyph following what is on disk. */
@Composable
fun rememberPlayerActions(vm: PlaybackViewModel, track: TrackInfo?, onOpen: (PlayerOverlay) -> Unit): List<PlayerAction> {
    val inJam = JamHolder.session.collectAsState().value != null
    // Falls back to title/artist like playback does, so a relinked track's label agrees with what is
    // actually on disk. In-flight counts as well, like every track row: isDownloaded is still false
    // while the fetch runs, so without it a second tap started a second download of the same track.
    val downloadedIndex by Downloads.index.collectAsState()
    val inFlight by Downloads.inProgress.collectAsState()
    val isDownloaded = track?.let { Downloads.isDownloaded(downloadedIndex, it.uri, it.name, it.artist) } == true
    val isDownloading = track?.uri?.let { it in inFlight } == true
    val scope = PlayerActionScope(
        vm, track, LocalContext.current, stringResource(R.string.share_track_chooser), inJam, isDownloaded, onOpen,
    )
    return PlayerShortcut.entries.filter { it != PlayerShortcut.LIKE }.map { shortcut ->
        val label = when {
            shortcut == PlayerShortcut.DOWNLOAD && isDownloading -> stringResource(R.string.downloading)
            shortcut == PlayerShortcut.DOWNLOAD && isDownloaded -> stringResource(R.string.remove_download)
            shortcut == PlayerShortcut.JAM && inJam -> stringResource(R.string.jam)
            else -> stringResource(playerShortcutTitle(shortcut))
        }
        val enabled = (!shortcut.requiresTrack || track != null) && !(shortcut == PlayerShortcut.DOWNLOAD && isDownloading)
        PlayerAction(shortcut, playerShortcutIcon(shortcut, isDownloaded = isDownloaded), label, enabled) {
            if (enabled) scope.run(shortcut)
        }
    }
}
