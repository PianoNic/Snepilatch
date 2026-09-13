package ch.snepilatch.app.ui.shared

import ch.snepilatch.app.R
import ch.snepilatch.app.logic.download.DownloadQueue
import ch.snepilatch.app.logic.download.Downloads
import ch.snepilatch.app.ui.theme.SnepilatchWhite
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.runtime.collectAsState
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.DownloadForOffline
import androidx.compose.material.icons.rounded.OfflinePin
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.*
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlaylistRemove
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.snepilatch.app.data.TrackInfo
import ch.snepilatch.app.ui.theme.SnepilatchElevated
import ch.snepilatch.app.ui.theme.SnepilatchLightGray
import ch.snepilatch.app.logic.shared.formatTime
import androidx.lifecycle.viewmodel.compose.viewModel
import ch.snepilatch.app.logic.shared.ThemeController
import ch.snepilatch.app.viewmodel.DetailViewModel
import ch.snepilatch.app.viewmodel.PlaybackViewModel
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import ch.snepilatch.app.logic.shared.shareSpfyUri

/**
 * [onRemoveFromPlaylist] adds a "Remove from this Playlist" entry. Passed in because only the caller
 * knows whether this list is a playlist the user may edit (see [ch.snepilatch.app.data.isPlaylistOwnedBy]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackRow(
    track: TrackInfo,
    vm: PlaybackViewModel,
    contextUri: String? = null,
    trackIndex: Int? = null,
    onRemoveFromPlaylist: (() -> Unit)? = null
) {
    val detailVm: DetailViewModel = viewModel()
    var showMenu by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    // Subscribe only to the two projections that affect a row (which track is current +
    // whether it's playing), not the whole PlaybackUiState — otherwise every position tick
    // recomposes every visible row and scrolling janks.
    val currentUri by vm.currentTrackUri.collectAsState()
    val playing by vm.isPlayingFlow.collectAsState()
    val isPlaying = currentUri == track.uri && playing
    val theme by ThemeController.themeColors.collectAsState()
    val accent = theme.primary
    // One list for the whole screen, so a row costs a scan rather than a query. Falls back to
    // title/artist like playback does, so a relinked track's checkmark agrees with what plays.
    val downloadedIndex by Downloads.index.collectAsState()
    val inFlight by Downloads.inProgress.collectAsState()
    val isDownloaded = Downloads.isDownloaded(downloadedIndex, track.uri, track.name, track.artist)
    val isDownloading = track.uri in inFlight
    // Keyed by uri so a row costs a lookup rather than a scan of the queue. Absent until the first
    // progress lands, which is why a row that has started still falls back to the spinner.
    val percent by DownloadQueue.progress.collectAsState()
    val trackPercent = percent[track.uri]

    Row(
        Modifier
            .fillMaxWidth()
            .clickable { vm.playTrack(track, contextUri, trackIndex) }
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SpfyImage(
            url = track.albumArt,
            modifier = Modifier.size(48.dp),
            shape = RoundedCornerShape(4.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                track.name, color = if (isPlaying) accent else SnepilatchWhite, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(track.artist, color = SnepilatchLightGray, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (isDownloading) {
            if (trackPercent == null) {
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
            } else {
                CircularProgressIndicator(
                    progress = { trackPercent.coerceIn(0, 100) / 100f },
                    color = accent,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(14.dp),
                )
            }
            Spacer(Modifier.width(6.dp))
        } else if (isDownloaded) {
            Icon(
                Icons.Rounded.OfflinePin,
                stringResource(R.string.downloaded_indicator),
                tint = accent,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(6.dp))
        }
        if (track.durationMs > 0) {
            Text(formatTime(track.durationMs), color = SnepilatchLightGray, fontSize = 12.sp)
            Spacer(Modifier.width(4.dp))
        }
        IconButton(onClick = { showMenu = true }, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Rounded.MoreVert, stringResource(R.string.more), tint = SnepilatchLightGray, modifier = Modifier.size(20.dp))
        }
    }

    // Bottom sheet menu
    if (showMenu) {
        val sheetState = rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        )
        ModalBottomSheet(
            onDismissRequest = { showMenu = false },
            sheetState = sheetState,
            containerColor = SnepilatchElevated,
            dragHandle = {
                Box(
                    Modifier
                        .padding(vertical = 12.dp)
                        .width(40.dp)
                        .height(4.dp)
                        .background(SnepilatchLightGray.copy(alpha = 0.4f), RoundedCornerShape(2.dp))
                )
            }
        ) {
            SheetNavBarFix()
            // Track header
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SpfyImage(url = track.albumArt, modifier = Modifier.size(48.dp), shape = RoundedCornerShape(8.dp))
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        track.name, color = SnepilatchWhite, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(track.artist, color = SnepilatchLightGray, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = SnepilatchLightGray.copy(alpha = 0.15f))

            val shareLabel = stringResource(R.string.share)
            val addToQueueLabel = stringResource(R.string.add_to_queue)
            val addToPlaylistLabel = stringResource(R.string.add_to_playlist)
            val likeLabel = stringResource(R.string.like)
            val visitAlbumLabel = stringResource(R.string.visit_album)
            val visitArtistLabel = stringResource(R.string.visit_artist)
            val removeFromPlaylistLabel = stringResource(R.string.remove_from_playlist)
            val songRadioLabel = stringResource(R.string.go_to_song_radio)
            val downloadLabel = if (isDownloaded) {
                stringResource(R.string.remove_download)
            } else {
                stringResource(R.string.download_track)
            }
            val items = listOfNotNull(
                Triple(
                    if (isDownloaded) Icons.Rounded.OfflinePin else Icons.Rounded.DownloadForOffline,
                    downloadLabel,
                ) {
                    when {
                        isDownloading -> Unit
                        isDownloaded -> vm.removeDownload(track.uri)
                        else -> vm.downloadTrack(track, context)
                    }
                    showMenu = false
                },
                Triple(Icons.AutoMirrored.Rounded.QueueMusic, addToQueueLabel) {
                    vm.addToQueue(track.uri)
                    showMenu = false
                },
                Triple(Icons.AutoMirrored.Rounded.PlaylistAdd, addToPlaylistLabel) {
                    showMenu = false
                    vm.showPlaylistPickerForTrack(track.uri)
                },
                onRemoveFromPlaylist?.let { remove ->
                    Triple(Icons.Rounded.PlaylistRemove, removeFromPlaylistLabel) {
                        showMenu = false
                        remove()
                    }
                },
                Triple(Icons.Rounded.Favorite, likeLabel) {
                    vm.likeSong(track.uri.removePrefix("spotify:track:"))
                    showMenu = false
                },
                Triple(Icons.Rounded.Radio, songRadioLabel) {
                    showMenu = false
                    detailVm.openRadio(track.uri)
                },
                Triple(Icons.Rounded.Album, visitAlbumLabel) {
                    showMenu = false
                    detailVm.openAlbumForTrack(track.uri)
                },
                Triple(Icons.Rounded.Person, visitArtistLabel) {
                    showMenu = false
                    detailVm.openArtistForTrack(track.uri)
                },
                Triple(Icons.Rounded.Share, shareLabel) {
                    showMenu = false
                    shareSpfyUri(context, track.uri, shareLabel)
                }
            )

            items.forEach { (icon, label, onClick) ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onClick() }
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(icon, null, tint = SnepilatchWhite, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(16.dp))
                    Text(label, color = SnepilatchWhite, fontSize = 15.sp)
                }
            }
            Spacer(Modifier.navigationBarsPadding().height(12.dp))
        }
    }
}

// --- Reusable overflow (3-dots) context menu ---
