@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package ch.snepilatch.app.ui.components

import ch.snepilatch.app.R
import ch.snepilatch.app.download.DownloadQueue
import ch.snepilatch.app.download.Downloads
import ch.snepilatch.app.ui.theme.SpfyWhite
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.*
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlaylistRemove
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import android.os.Build
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.snepilatch.app.data.TrackInfo
import ch.snepilatch.app.ui.theme.SpfyElevated
import ch.snepilatch.app.ui.theme.SpfyGray
import ch.snepilatch.app.ui.theme.SpfyLightGray
import ch.snepilatch.app.util.formatTime
import androidx.lifecycle.viewmodel.compose.viewModel
import ch.snepilatch.app.viewmodel.ThemeController
import ch.snepilatch.app.viewmodel.DetailViewModel
import ch.snepilatch.app.viewmodel.PlaybackViewModel
import coil.compose.AsyncImage
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Match the app's transparent, edge-to-edge nav bar inside a ModalBottomSheet. The sheet
 * has its own window that re-enables the nav-bar contrast scrim the Activity turned off,
 * which shows as a static white backdrop behind the system buttons. Call at the top of the
 * sheet's content.
 */
@Composable
fun SheetNavBarFix() {
    val view = LocalView.current
    SideEffect {
        val window = (view.parent as? DialogWindowProvider)?.window ?: return@SideEffect
        // The scrim is all that's left from API 35 — the bar can't be tinted there — and this is the
        // only setter that still removes it. NOT deprecated, and NOT skippable on 35+: doing so is
        // what put the white backdrop back behind the buttons.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        // Deprecated and ignored from API 35; below that it's the only way to clear the bar.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            @Suppress("DEPRECATION")
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
        }
    }
}

// --- Shimmer effect ---

/**
 * A shimmer placeholder driven by a shared [phase] transition (see HomeShimmer). Reading phase.value
 * inside onDrawBehind registers a DRAW-phase snapshot read, so animation frames invalidate only the
 * draw, not composition — the box never recomposes as the sweep animates.
 */
@Composable
fun ShimmerBox(phase: State<Float>, modifier: Modifier) {
    Box(
        modifier.drawWithCache {
            onDrawBehind {
                val x = phase.value
                drawRect(
                    Brush.linearGradient(
                        colors = listOf(SpfyGray, SpfyElevated, SpfyGray),
                        start = Offset(x - 500f, 0f),
                        end = Offset(x, 0f)
                    )
                )
            }
        }
    )
}

// --- Image with placeholder ---

@Composable
fun SpfyImage(
    url: String?,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(8.dp),
    icon: ImageVector = Icons.Rounded.MusicNote
) {
    // AsyncImage instead of SubcomposeAsyncImage: subcomposition per row is expensive in a
    // scrolling list, and the old `loading` slot ran an infinite LoadingIndicator animation
    // in *every* not-yet-loaded row. A static placeholder box with a faint icon sits behind
    // the image; the opaque cropped artwork covers it once loaded (crossfade), and it stays
    // visible while loading or on error — same look, none of the per-row cost.
    Box(
        modifier
            .clip(shape)
            .background(SpfyGray),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, null, tint = SpfyLightGray.copy(alpha = 0.5f), modifier = Modifier.size(32.dp))
        if (!url.isNullOrEmpty()) {
            val ctx = androidx.compose.ui.platform.LocalContext.current
            AsyncImage(
                model = remember(url) { coil.request.ImageRequest.Builder(ctx).data(url).crossfade(300).build() },
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
    }
}

// --- Smooth playback position ---

/**
 * A playback position (ms) that advances at the display's native refresh rate instead of stepping
 * when the upstream [positionMs] StateFlow ticks.
 *
 * Each authoritative [positionMs] (and every play/pause flip) becomes an anchor; while playing, the
 * returned value advances from that anchor roughly every [PROGRESS_TICK_MS], read off the real frame
 * timestamp, so the progress bar glides with no visible steps. It stays honest because every upstream
 * tick re-anchors it to the true position (for local streaming that is ExoPlayer's own clock),
 * correcting any drift twice a second. Costs nothing when paused or off-screen — no frames are
 * requested.
 */
@Composable
fun rememberSmoothPositionMs(positionMs: Long, durationMs: Long, isPlaying: Boolean): State<Long> {
    // Returns the State rather than its value so the per-frame write only recomposes/redraws the
    // leaf that reads `.value` (ideally inside a draw-phase lambda), not the caller's whole body.
    val displayed = remember { mutableLongStateOf(positionMs) }
    LaunchedEffect(positionMs, isPlaying, durationMs) {
        if (!isPlaying) {
            displayed.longValue = positionMs
            return@LaunchedEffect
        }
        val cap = if (durationMs > 0) durationMs else Long.MAX_VALUE
        var anchorFrame = -1L
        while (true) {
            withFrameNanos { frame ->
                if (anchorFrame < 0) anchorFrame = frame
                displayed.longValue = (positionMs + (frame - anchorFrame) / 1_000_000L).coerceIn(0L, cap)
            }
            // Yield the display back between updates. withFrameNanos registers a Choreographer
            // callback, which schedules a frame — so looping it bare held the whole app at the panel's
            // full rate (~120fps, ~1 CPU core plus up to half of surfaceflinger) for as long as audio
            // played, on every screen. A progress bar advances about one pixel every 200ms, so 30
            // updates a second is already more than it can show. Same cadence P0-1 settled on for the
            // fluid background.
            delay(PROGRESS_TICK_MS)
        }
    }
    return displayed
}

/**
 * How often the smooth position advances while playing, in ms (~30fps). Not per-frame: see the loop
 * in [rememberSmoothPositionMs]. The value stays derived from the real frame timestamp, so throttling
 * the cadence costs accuracy nothing — only the number of frames requested.
 */
private const val PROGRESS_TICK_MS = 32L

// --- Track Row ---

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
            Text(track.name, color = if (isPlaying) accent else SpfyWhite, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(track.artist, color = SpfyLightGray, fontSize = 13.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (isDownloading) {
            if (trackPercent == null) {
                CircularProgressIndicator(color = accent, strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
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
            Text(formatTime(track.durationMs), color = SpfyLightGray, fontSize = 12.sp)
            Spacer(Modifier.width(4.dp))
        }
        IconButton(onClick = { showMenu = true }, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Rounded.MoreVert, stringResource(R.string.more), tint = SpfyLightGray, modifier = Modifier.size(20.dp))
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
            containerColor = SpfyElevated,
            dragHandle = {
                Box(
                    Modifier
                        .padding(vertical = 12.dp)
                        .width(40.dp)
                        .height(4.dp)
                        .background(SpfyLightGray.copy(alpha = 0.4f), RoundedCornerShape(2.dp))
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
                    Text(track.name, color = SpfyWhite, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(track.artist, color = SpfyLightGray, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = SpfyLightGray.copy(alpha = 0.15f))

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
                    vm.addToQueue(track.uri); showMenu = false
                },
                Triple(Icons.AutoMirrored.Rounded.PlaylistAdd, addToPlaylistLabel) {
                    showMenu = false; vm.showPlaylistPickerForTrack(track.uri)
                },
                onRemoveFromPlaylist?.let { remove ->
                    Triple(Icons.Rounded.PlaylistRemove, removeFromPlaylistLabel) {
                        showMenu = false; remove()
                    }
                },
                Triple(Icons.Rounded.Favorite, likeLabel) {
                    vm.likeSong(track.uri.removePrefix("spotify:track:")); showMenu = false
                },
                Triple(Icons.Rounded.Radio, songRadioLabel) {
                    showMenu = false; detailVm.openRadio(track.uri)
                },
                Triple(Icons.Rounded.Album, visitAlbumLabel) {
                    showMenu = false; detailVm.openAlbumForTrack(track.uri)
                },
                Triple(Icons.Rounded.Person, visitArtistLabel) {
                    showMenu = false; detailVm.openArtistForTrack(track.uri)
                },
                Triple(Icons.Rounded.Share, shareLabel) {
                    showMenu = false
                    val id = track.uri.removePrefix("spotify:track:")
                    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(android.content.Intent.EXTRA_TEXT, "https://open.spotify.com/track/$id")
                    }
                    context.startActivity(android.content.Intent.createChooser(intent, shareLabel))
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
                    Icon(icon, null, tint = SpfyWhite, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(16.dp))
                    Text(label, color = SpfyWhite, fontSize = 15.sp)
                }
            }
            Spacer(Modifier.navigationBarsPadding().height(12.dp))
        }
    }
}

// --- Reusable overflow (3-dots) context menu ---

/** One action row in an [OverflowMenu]. */
data class OverflowAction(val icon: ImageVector, val label: String, val onClick: () -> Unit)

/**
 * A 3-dots button that opens a bottom-sheet context menu (same styling as [TrackRow]'s), driven by a
 * caller-supplied list of [actions] with a header from [title]/[subtitle]/[imageUrl]. Used by the
 * search-result cards so songs/artists/albums/playlists all get a menu (go to artist/album, add to
 * playlist, share, …). Renders nothing when [actions] is empty.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OverflowMenu(
    title: String,
    subtitle: String,
    imageUrl: String?,
    circular: Boolean,
    actions: List<OverflowAction>,
    modifier: Modifier = Modifier
) {
    if (actions.isEmpty()) return
    var showMenu by remember { mutableStateOf(false) }
    IconButton(onClick = { showMenu = true }, modifier = modifier.size(36.dp)) {
        Icon(Icons.Rounded.MoreVert, stringResource(R.string.more), tint = SpfyLightGray, modifier = Modifier.size(20.dp))
    }
    if (showMenu) {
        val sheetState = rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        )
        ModalBottomSheet(
            onDismissRequest = { showMenu = false },
            sheetState = sheetState,
            containerColor = SpfyElevated,
            dragHandle = {
                Box(
                    Modifier
                        .padding(vertical = 12.dp)
                        .width(40.dp)
                        .height(4.dp)
                        .background(SpfyLightGray.copy(alpha = 0.4f), RoundedCornerShape(2.dp))
                )
            }
        ) {
            SheetNavBarFix()
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SpfyImage(
                    url = imageUrl,
                    modifier = Modifier.size(48.dp),
                    shape = if (circular) androidx.compose.foundation.shape.CircleShape else RoundedCornerShape(8.dp)
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(title, color = SpfyWhite, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (subtitle.isNotBlank()) {
                        Text(subtitle, color = SpfyLightGray, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = SpfyLightGray.copy(alpha = 0.15f))
            actions.forEach { action ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { action.onClick(); showMenu = false }
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(action.icon, null, tint = SpfyWhite, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(16.dp))
                    Text(action.label, color = SpfyWhite, fontSize = 15.sp)
                }
            }
            Spacer(Modifier.navigationBarsPadding().height(12.dp))
        }
    }
}

// --- Profile Info Item ---

@Composable
fun ProfileInfoItem(label: String, value: String, icon: ImageVector) {
    ListItem(
        headlineContent = { Text(label, color = SpfyWhite) },
        supportingContent = { Text(value, color = SpfyLightGray) },
        leadingContent = { Icon(icon, null, tint = SpfyLightGray) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}

/**
 * Three covers share one offset, keeping their spacing fixed throughout a drag and track change.
 */
@Composable
fun SlidingCoverImage(
    url: String?,
    modifier: Modifier = Modifier,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(8.dp),
    trackKey: Any? = url,
    forward: Boolean = true,
    onSwipePrevious: (() -> Unit)? = null,
    onSwipeNext: (() -> Unit)? = null,
    previousCoverUrl: String? = null,
    nextCoverUrl: String? = null,
    secondNextCoverUrl: String? = null,
    clipToFrame: Boolean = true,
    buttonSkip: Pair<Int, Int> = 0 to 0,
) {
    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(0f) }
    val gap = with(androidx.compose.ui.platform.LocalDensity.current) { 32.dp.toPx() }
    var width by remember { mutableFloatStateOf(0f) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    var swipeDirection by remember { mutableIntStateOf(0) }
    var settleJob by remember { mutableStateOf<Job?>(null) }
    var currentKey by remember { mutableStateOf(trackKey) }
    var currentUrl by remember { mutableStateOf(url) }
    var leftUrl by remember { mutableStateOf(previousCoverUrl) }
    var rightUrl by remember { mutableStateOf(nextCoverUrl) }
    val previousAction by androidx.compose.runtime.rememberUpdatedState(onSwipePrevious)
    val nextAction by androidx.compose.runtime.rememberUpdatedState(onSwipeNext)
    val latestPrevious by androidx.compose.runtime.rememberUpdatedState(previousCoverUrl)
    val latestNext by androidx.compose.runtime.rememberUpdatedState(nextCoverUrl)
    val context = androidx.compose.ui.platform.LocalContext.current
    var loadedCovers by remember { mutableStateOf(emptyMap<String, BitmapPainter>()) }
    val coverUrls = listOfNotNull(
        url, previousCoverUrl, nextCoverUrl, secondNextCoverUrl, leftUrl, currentUrl, rightUrl
    ).filter { it.isNotBlank() }.distinct()
    LaunchedEffect(coverUrls, width) {
        if (width <= 0f) return@LaunchedEffect
        loadedCovers = loadedCovers.filterKeys { it in coverUrls }
        coverUrls.filterNot { it in loadedCovers }.forEach { cover ->
            launch {
                val painter = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val result = coil.Coil.imageLoader(context).execute(
                        coil.request.ImageRequest.Builder(context)
                            .data(cover).size(width.toInt()).allowHardware(false).build()
                    )
                    (result as? coil.request.SuccessResult)?.drawable?.let {
                        BitmapPainter(it.toBitmap().asImageBitmap())
                    }
                }
                if (painter != null) loadedCovers = loadedCovers + (cover to painter)
            }
        }
    }

    LaunchedEffect(buttonSkip) {
        if (buttonSkip.first == 0) return@LaunchedEffect
        settleJob?.cancel()
        leftUrl = latestPrevious
        rightUrl = latestNext
        dragging = false
        swipeDirection = buttonSkip.second
        settleJob = scope.launch {
            offset.animateTo(buttonSkip.second * (width + gap), tween(220))
            delay(1000)
            swipeDirection = 0
            offset.animateTo(0f, tween(160))
        }
    }

    LaunchedEffect(trackKey) {
        if (trackKey == currentKey) return@LaunchedEffect
        val direction = swipeDirection
        val oldUrl = currentUrl
        val oldOffset = if (dragging) dragOffset else offset.value
        settleJob?.cancel()
        val movement = if (direction != 0) direction else if (forward) -1 else 1
        val incomingUrl = if (movement < 0) rightUrl else leftUrl
        // Move the strip and replace its contents together, before the next frame.
        offset.snapTo((if (direction != 0) oldOffset else 0f) - movement * (width + gap))
        dragging = false
        swipeDirection = 0
        currentKey = trackKey
        currentUrl = url?.takeIf { it in loadedCovers }
            ?: incomingUrl?.takeIf { it in loadedCovers } ?: url
        leftUrl = if (movement < 0) oldUrl else previousCoverUrl
        rightUrl = if (movement > 0) oldUrl else nextCoverUrl
        settleJob = scope.launch {
            offset.animateTo(0f, tween(if (direction != 0) 160 else 220))
        }
    }
    LaunchedEffect(url, loadedCovers[url]) {
        if (trackKey == currentKey && url in loadedCovers) currentUrl = url
    }
    LaunchedEffect(previousCoverUrl, nextCoverUrl, currentKey, dragging, swipeDirection) {
        if (!dragging && swipeDirection == 0) {
            settleJob?.join()
            leftUrl = previousCoverUrl
            rightUrl = nextCoverUrl
        }
    }

    Box(
        modifier
            .onSizeChanged { width = it.width.toFloat() }
            .then(if (clipToFrame) Modifier.clip(shape) else Modifier)
            .pointerInput(gap) {
                val tracker = androidx.compose.ui.input.pointer.util.VelocityTracker()
                detectHorizontalDragGestures(
                    onDragStart = {
                        tracker.resetTracking()
                        settleJob?.cancel()
                        swipeDirection = 0
                        if (offset.value == 0f) {
                            leftUrl = latestPrevious
                            rightUrl = latestNext
                        }
                        dragOffset = offset.value
                        dragging = true
                    },
                    onHorizontalDrag = { change, amount ->
                        tracker.addPosition(change.uptimeMillis, change.position)
                        change.consume()
                        dragOffset = (dragOffset + amount)
                            .coerceIn(-size.width - gap, size.width + gap)
                    },
                    onDragEnd = {
                        val release = dragOffset
                        val velocity = tracker.calculateVelocity().x
                        val direction = when {
                            velocity > 700f -> 1
                            velocity < -700f -> -1
                            release > size.width * 0.15f -> 1
                            release < -size.width * 0.15f -> -1
                            else -> 0
                        }
                        settleJob = scope.launch {
                            offset.snapTo(release)
                            dragging = false
                            if (direction == 0) {
                                offset.animateTo(0f, spring(stiffness = 600f))
                            } else {
                                swipeDirection = direction
                                if (direction > 0) previousAction?.invoke() else nextAction?.invoke()
                                offset.animateTo(direction * (size.width + gap), tween(160))
                                delay(250)
                                if (swipeDirection == direction) {
                                    swipeDirection = 0
                                    offset.animateTo(0f, spring(stiffness = 600f))
                                }
                            }
                        }
                    },
                    onDragCancel = {
                        val release = dragOffset
                        settleJob = scope.launch {
                            offset.snapTo(release)
                            dragging = false
                            offset.animateTo(0f, spring(stiffness = 600f))
                        }
                    }
                )
            }
    ) {
        val covers = listOf(leftUrl, currentUrl, rightUrl)
        covers.forEachIndexed { index, cover ->
            androidx.compose.foundation.Image(
                painter = loadedCovers[cover] ?: androidx.compose.ui.graphics.painter.ColorPainter(SpfyGray),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        translationX = (if (dragging) dragOffset else offset.value) +
                            (index - 1) * (size.width + gap)
                    }
                    .clip(shape)
                    .background(SpfyGray),
            )
        }
    }
}
