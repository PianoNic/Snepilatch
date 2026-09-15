package ch.snepilatch.app.ui.shared

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.DownloadForOffline
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.OfflinePin
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Share
import androidx.compose.ui.graphics.vector.ImageVector
import ch.snepilatch.app.R
import ch.snepilatch.app.data.PlayerShortcut

/** The title of a [PlayerShortcut], shared by the player's action sheet, its configurable button and the settings picker. */
@StringRes
fun playerShortcutTitle(shortcut: PlayerShortcut): Int = when (shortcut) {
    PlayerShortcut.LIKE -> R.string.like
    PlayerShortcut.LYRICS -> R.string.lyrics
    PlayerShortcut.ADD_TO_QUEUE -> R.string.add_to_queue
    PlayerShortcut.ADD_TO_PLAYLIST -> R.string.add_to_playlist
    PlayerShortcut.QUEUE -> R.string.view_queue
    PlayerShortcut.ALBUM -> R.string.visit_album
    PlayerShortcut.RADIO -> R.string.go_to_song_radio
    PlayerShortcut.DOWNLOAD -> R.string.download_track
    PlayerShortcut.JAM -> R.string.join_jam
    PlayerShortcut.CODE -> R.string.show_code
    PlayerShortcut.SHARE -> R.string.share
}

/** The glyph of a [PlayerShortcut]; like and download change with the track's state, as the track rows do. */
fun playerShortcutIcon(shortcut: PlayerShortcut, isLiked: Boolean = false, isDownloaded: Boolean = false): ImageVector = when (shortcut) {
    PlayerShortcut.LIKE -> if (isLiked) Icons.Rounded.Favorite else Icons.Filled.FavoriteBorder
    PlayerShortcut.LYRICS -> Icons.Rounded.MusicNote
    PlayerShortcut.ADD_TO_QUEUE, PlayerShortcut.QUEUE -> Icons.AutoMirrored.Rounded.QueueMusic
    PlayerShortcut.ADD_TO_PLAYLIST -> Icons.AutoMirrored.Rounded.PlaylistAdd
    PlayerShortcut.ALBUM -> Icons.Rounded.Album
    PlayerShortcut.RADIO -> Icons.Rounded.Radio
    PlayerShortcut.DOWNLOAD -> if (isDownloaded) Icons.Rounded.OfflinePin else Icons.Rounded.DownloadForOffline
    PlayerShortcut.JAM -> Icons.Rounded.Groups
    PlayerShortcut.CODE -> Icons.Rounded.QrCode2
    PlayerShortcut.SHARE -> Icons.Rounded.Share
}
