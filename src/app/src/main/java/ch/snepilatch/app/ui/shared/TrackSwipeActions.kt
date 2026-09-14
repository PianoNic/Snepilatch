package ch.snepilatch.app.ui.shared

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import ch.snepilatch.app.R
import ch.snepilatch.app.data.PlayerShortcut
import ch.snepilatch.app.data.TrackInfo
import ch.snepilatch.app.logic.download.Downloads
import ch.snepilatch.app.logic.shared.AppSettings
import ch.snepilatch.app.logic.shared.JamHolder
import ch.snepilatch.app.logic.shared.LyricsTarget
import ch.snepilatch.app.logic.shared.shareSpfyUri
import ch.snepilatch.app.ui.theme.SnepilatchBlack
import ch.snepilatch.app.ui.theme.SnepilatchWhite
import ch.snepilatch.app.viewmodel.DetailRoutes
import ch.snepilatch.app.viewmodel.PlaybackViewModel
import kotlinx.coroutines.launch

/** Adds the configured left and right actions to a track row without changing its tap behavior. */
@Composable
fun SwipeableTrackRow(
    track: TrackInfo,
    vm: PlaybackViewModel,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val state = rememberSwipeToDismissBoxState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val leftAction by AppSettings.swipeLeftAction.collectAsState()
    val rightAction by AppSettings.swipeRightAction.collectAsState()
    val successAlpha = remember { Animatable(0f) }
    val confirmationColor = MaterialTheme.colorScheme.primary
    val currentUri by vm.currentTrackUri.collectAsState()
    val isLiked by vm.currentTrackLiked.collectAsState()
    val downloadedIndex by Downloads.index.collectAsState()
    val inFlight by Downloads.inProgress.collectAsState()
    val inJam by JamHolder.session.collectAsState()
    val isDownloaded = Downloads.isDownloaded(downloadedIndex, track.uri, track.name, track.artist)
    val isDownloading = track.uri in inFlight
    val trackIsLiked = currentUri == track.uri && isLiked
    var completedDirection by remember { mutableStateOf<SwipeToDismissBoxValue?>(null) }
    var showJam by remember { mutableStateOf(false) }
    var showCode by remember { mutableStateOf(false) }

    SwipeToDismissBox(
        state = state,
        modifier = modifier,
        onDismiss = { direction ->
            val action = swipeActionFor(direction, leftAction, rightAction)
            action?.let {
                runSwipeAction(
                    it, track, vm, context, trackIsLiked, isDownloaded, isDownloading,
                    inJam != null, onShowJam = { showJam = true }, onShowCode = { showCode = true },
                )
            }
            completedDirection = direction.takeUnless { it == SwipeToDismissBoxValue.Settled }
            scope.launch {
                successAlpha.snapTo(0f)
                successAlpha.animateTo(0.24f, tween(200, easing = FastOutSlowInEasing))
                successAlpha.animateTo(0f, tween(200, easing = FastOutSlowInEasing))
                completedDirection = null
                state.reset()
            }
        },
        backgroundContent = {
            val direction = completedDirection ?: state.dismissDirection
            SwipeActionBackground(
                direction, leftAction, rightAction, trackIsLiked, isDownloaded, isDownloading,
                inJam != null, confirmationColor, successAlpha.value,
            )
        },
        content = {
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(SnepilatchBlack)
            ) {
                content()
            }
        },
    )

    if (showJam) JamSheet(onDismiss = { showJam = false })
    if (showCode) {
        val accent = MaterialTheme.colorScheme.primary
        ScannableCodeSheet(track, accent) { showCode = false }
    }
}

private fun swipeActionFor(
    direction: SwipeToDismissBoxValue,
    leftAction: PlayerShortcut,
    rightAction: PlayerShortcut,
): PlayerShortcut? = when (direction) {
    SwipeToDismissBoxValue.EndToStart -> leftAction
    SwipeToDismissBoxValue.StartToEnd -> rightAction
    SwipeToDismissBoxValue.Settled -> null
}

@Suppress("LongParameterList")
private fun runSwipeAction(
    action: PlayerShortcut,
    track: TrackInfo,
    vm: PlaybackViewModel,
    context: android.content.Context,
    trackIsLiked: Boolean,
    isDownloaded: Boolean,
    isDownloading: Boolean,
    inJam: Boolean,
    onShowJam: () -> Unit,
    onShowCode: () -> Unit,
) {
    when (action) {
        PlayerShortcut.LIKE -> {
            track.uri
                .takeIf { it.startsWith("spotify:track:") }
                ?.removePrefix("spotify:track:")
                ?.let { if (trackIsLiked) vm.unlikeSong(it) else vm.likeSong(it) }
        }
        PlayerShortcut.LYRICS -> {
            LyricsTarget.track.value = track
            vm.openLyrics()
        }
        PlayerShortcut.ADD_TO_QUEUE -> vm.addToQueue(track.uri)
        PlayerShortcut.ADD_TO_PLAYLIST -> vm.showPlaylistPickerForTrack(track.uri)
        PlayerShortcut.QUEUE -> vm.openQueue()
        PlayerShortcut.ALBUM -> DetailRoutes.openAlbumForTrack(track.uri)
        PlayerShortcut.RADIO -> DetailRoutes.openRadio(track.uri)
        PlayerShortcut.DOWNLOAD -> if (!isDownloading) {
            if (isDownloaded) vm.removeDownload(track.uri) else vm.downloadTrack(track, context)
        }
        PlayerShortcut.JAM -> if (inJam) vm.openQueue() else onShowJam()
        PlayerShortcut.CODE -> onShowCode()
        PlayerShortcut.SHARE -> shareSpfyUri(context, track.uri, context.getString(R.string.share_track_chooser))
    }
}

@Composable
@Suppress("LongParameterList")
private fun SwipeActionBackground(
    direction: SwipeToDismissBoxValue,
    leftAction: PlayerShortcut,
    rightAction: PlayerShortcut,
    trackIsLiked: Boolean,
    isDownloaded: Boolean,
    isDownloading: Boolean,
    inJam: Boolean,
    confirmationColor: androidx.compose.ui.graphics.Color,
    successAlpha: Float,
) {
    val action = swipeActionFor(direction, leftAction, rightAction) ?: return
    val label = when {
        action == PlayerShortcut.LIKE && trackIsLiked -> stringResource(R.string.unlike)
        action == PlayerShortcut.DOWNLOAD && isDownloading -> stringResource(R.string.downloading)
        action == PlayerShortcut.DOWNLOAD && isDownloaded -> stringResource(R.string.remove_download)
        action == PlayerShortcut.JAM && inJam -> stringResource(R.string.jam)
        else -> stringResource(action.titleRes)
    }
    val icon = playerShortcutIcon(action, trackIsLiked, isDownloaded)
    val toPlaylist = direction == SwipeToDismissBoxValue.StartToEnd
    Row(
        Modifier
            .fillMaxSize()
            .background(SnepilatchBlack)
            .drawBehind { drawRect(confirmationColor.copy(alpha = successAlpha)) }
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (toPlaylist) Arrangement.Start else Arrangement.End,
    ) {
        if (toPlaylist) {
            Icon(icon, label, tint = SnepilatchWhite)
            Text(label, color = SnepilatchWhite, modifier = Modifier.padding(start = 8.dp))
        } else {
            Text(label, color = SnepilatchWhite, modifier = Modifier.padding(end = 8.dp))
            Icon(icon, label, tint = SnepilatchWhite)
        }
    }
}
