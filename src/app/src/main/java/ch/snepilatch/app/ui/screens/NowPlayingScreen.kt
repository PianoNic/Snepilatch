@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package ch.snepilatch.app.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.delay
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.TextUnit
import ch.snepilatch.app.R
import ch.snepilatch.app.data.PlayerShortcut
import android.graphics.SurfaceTexture
import android.net.Uri
import android.view.TextureView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.clip
import ch.snepilatch.app.ui.shared.CoverNeighbours
import ch.snepilatch.app.ui.shared.CoverTrack
import ch.snepilatch.app.ui.shared.FlipCard
import ch.snepilatch.app.ui.shared.SlidingCoverImage
import ch.snepilatch.app.ui.shared.InfiniPlayTimeline
import ch.snepilatch.app.ui.shared.rememberSmoothPositionMs
import ch.snepilatch.app.ui.theme.*
import ch.snepilatch.app.logic.shared.formatTime
import androidx.lifecycle.viewmodel.compose.viewModel
import ch.snepilatch.app.logic.shared.ThemeController
import ch.snepilatch.app.viewmodel.LibraryViewModel
import ch.snepilatch.app.logic.shared.AppSettings
import ch.snepilatch.app.viewmodel.PlaybackViewModel
import ch.snepilatch.app.viewmodel.canRemovePlayingFromPlaylist
import ch.snepilatch.app.viewmodel.removePlayingFromPlaylist
import ch.snepilatch.app.logic.shared.shareSpfyUri
import ch.snepilatch.app.ui.shared.LikeToggleButton
import ch.snepilatch.app.ui.shared.PlaylistPickerDialog
import ch.snepilatch.app.ui.shared.PlayerAction
import ch.snepilatch.app.ui.shared.PlayerOverlay
import ch.snepilatch.app.ui.shared.rememberPlayerActions
import ch.snepilatch.app.ui.shared.EntityMenuSheet
import ch.snepilatch.app.ui.shared.MenuAction
import ch.snepilatch.app.ui.shared.jamAllowsControls

/**
 * The seek bar + elapsed/duration labels (or the infiniPlay remix timeline), pulled into its own leaf
 * composable. rememberSmoothPositionMs updates once per display frame while playing; keeping that read
 * — and the eager Slider `value`/formatTime reads it feeds — inside this leaf confines the per-frame
 * invalidation here instead of recomposing the whole orientation Column each frame.
 */
@Composable
private fun PlaybackProgress(
    vm: PlaybackViewModel,
    animatedPrimary: Color,
    timeColor: Color,
    timeFontSize: TextUnit,
) {
    // Self-contained on narrow projections: positionFlow (the only 2Hz source) is collected here so
    // its ticks recompose this leaf, not the ~400-line orientation Column that hosts it.
    val positionMs by vm.positionFlow.collectAsState()
    val durationMs by vm.durationFlow.collectAsState()
    val isPlaying by vm.isPlayingFlow.collectAsState()
    val infiniPlayOn by vm.infiniPlayEnabled.collectAsState()
    val infiniPlayViz by vm.infiniPlayViz.collectAsState()
    var infiniPlayElapsedMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(infiniPlayOn) {
        infiniPlayElapsedMs = 0L
        while (infiniPlayOn) {
            kotlinx.coroutines.delay(1000)
            infiniPlayElapsedMs += 1000
        }
    }
    var seekDragging by remember { mutableStateOf(false) }
    var seekDragValue by remember { mutableFloatStateOf(0f) }
    val smoothPos = rememberSmoothPositionMs(positionMs, durationMs, isPlaying)
    val sliderValue = if (seekDragging) seekDragValue
        else if (durationMs > 0) (smoothPos.value.toFloat() / durationMs) else 0f
    // Elapsed label at ~1Hz: the inner derived tracks whole seconds (recomputes per frame but only
    // does integer division), the outer only re-runs formatTime when the second actually changes.
    val elapsedSec by remember { derivedStateOf { smoothPos.value / 1000 } }
    val elapsedLabel by remember { derivedStateOf { formatTime(elapsedSec * 1000) } }
    if (infiniPlayOn) {
        InfiniPlayTimeline(
            viz = infiniPlayViz,
            primary = animatedPrimary,
            modifier = Modifier.fillMaxWidth()
        )
    } else {
        Slider(
            value = sliderValue,
            onValueChange = { seekDragging = true; seekDragValue = it },
            onValueChangeFinished = {
                vm.seekTo((seekDragValue * durationMs).toLong())
                seekDragging = false
            },
            thumb = {
                Box(
                    Modifier
                        .size(width = 6.dp, height = 30.dp)
                        .background(animatedPrimary, RoundedCornerShape(3.dp))
                )
            },
            colors = SliderDefaults.colors(
                thumbColor = animatedPrimary,
                activeTrackColor = animatedPrimary,
                inactiveTrackColor = SnepilatchWhite.copy(alpha = 0.15f)
            ),
            modifier = Modifier.fillMaxWidth()
        )
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(if (infiniPlayOn) "" else elapsedLabel, color = timeColor, fontSize = timeFontSize)
        Text(if (infiniPlayOn) formatTime(infiniPlayElapsedMs) else formatTime(durationMs), color = timeColor, fontSize = timeFontSize)
    }
}

/**
 * The now-playing background: Canvas video when enabled, otherwise the album's
 * accent colour with the blurred album art over it, topped by a dark scrim.
 * Extracted so the expanding-player morph (SpfyApp) can render the same *live*
 * background — video included — inside the growing card, anchored to it, instead
 * of a still. The video transform is recomputed on view resize so it stays
 * fit-cropped while the card grows.
 */
/**
 * Sizes the canvas video view to COVER a [boxW]x[boxH] box at the clip's own aspect ratio.
 * requiredSize, not size: the cover width can exceed the box (1080px) and must NOT be clamped to it,
 * or the video gets squished horizontally. The overflow is clipped by the caller's clipToBounds.
 */
private fun coverModifier(vw: Int, vh: Int, boxW: Float, boxH: Float, density: Density): Modifier {
    if (vw <= 0 || vh <= 0 || minOf(boxW, boxH) <= 0f) return Modifier.fillMaxSize()
    val scale = maxOf(boxW / vw, boxH / vh)
    return Modifier.requiredSize(
        with(density) { (vw * scale).toDp() },
        with(density) { (vh * scale).toDp() }
    )
}

/**
 * The looping, muted Canvas clip behind the player, rendered into a TextureView sized to COVER the
 * (possibly growing) card. Split out of PlayerBackground so the decoder/lifecycle wiring stays
 * readable and isolated. [audioPlaying] gates the decoder: it runs only while audio actually plays
 * and the screen is foreground, so a paused song freezes the last frame instead of decoding forever.
 */
@Composable
private fun CanvasVideoBackground(canvasVideoUrl: String, audioPlaying: Boolean) {
    val context = LocalContext.current
    val density = LocalDensity.current
    var canvasPlayer by remember { mutableStateOf<ExoPlayer?>(null) }
    var textureRef by remember { mutableStateOf<TextureView?>(null) }
    var surfaceReady by remember { mutableStateOf(false) }
    var videoSizePx by remember { mutableStateOf(0 to 0) }
    // Tracks whether the player screen is foreground (STARTED+). Combined with audioPlaying it
    // decides whether the video decoder runs — so a paused song no longer decodes indefinitely.
    var isForeground by remember { mutableStateOf(true) }

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(canvasVideoUrl, lifecycleOwner) {
        val player = ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.parse(canvasVideoUrl)))
            repeatMode = Player.REPEAT_MODE_ALL
            volume = 0f
            playWhenReady = audioPlaying
            addListener(object : Player.Listener {
                override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                    videoSizePx = videoSize.width to videoSize.height
                }
            })
            prepare()
        }
        canvasPlayer = player
        if (surfaceReady) {
            textureRef?.let { player.setVideoTextureView(it) }
        }

        // Track foreground state with the activity lifecycle (handles backgrounding); the
        // playWhenReady value itself is driven by the LaunchedEffect below (audio-gated).
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_START,
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> {
                    isForeground = true
                    // Re-attach surface to force frame refresh after resume
                    if (surfaceReady) {
                        textureRef?.let {
                            player.clearVideoTextureView(it)
                            player.setVideoTextureView(it)
                        }
                    }
                }
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> {
                    isForeground = false
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            player.release()
            canvasPlayer = null
        }
    }

    // Freeze the canvas video whenever audio is paused or the screen is backgrounded. This second
    // ExoPlayer otherwise keeps hardware-decoding the looping clip every frame regardless of
    // playback state — a continuous decoder + GPU-composite load as hot as the fluid warp.
    LaunchedEffect(canvasPlayer, audioPlaying, isForeground) {
        canvasPlayer?.playWhenReady = audioPlaying && isForeground
    }

    // Size the TextureView to COVER the (growing) card at the video's aspect
    // ratio, then centre + clip it. The view's own bounds already match the
    // video aspect, so the raw TextureView fills them without distortion — no
    // per-frame matrix that loses the race with layout while the card resizes.
    BoxWithConstraints(Modifier.fillMaxSize().clipToBounds()) {
        val (vw, vh) = videoSizePx
        val coverMod = coverModifier(
            vw, vh, constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat(), density
        )
        AndroidView(
            factory = { ctx ->
                TextureView(ctx).apply {
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(
                            surface: SurfaceTexture, width: Int, height: Int
                        ) {
                            surfaceReady = true
                            textureRef = this@apply
                            canvasPlayer?.setVideoTextureView(this@apply)
                        }
                        override fun onSurfaceTextureSizeChanged(
                            surface: SurfaceTexture, width: Int, height: Int
                        ) {}
                        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                            surfaceReady = false
                            canvasPlayer?.clearVideoTextureView(this@apply)
                            return true
                        }
                        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
                    }
                    textureRef = this
                }
            },
            update = { texture ->
                if (surfaceReady) {
                    canvasPlayer?.setVideoTextureView(texture)
                }
            },
            modifier = coverMod.align(Alignment.Center)
        )
    }
}

@Composable
fun PlayerBackground(vm: PlaybackViewModel, modifier: Modifier = Modifier) {
    // Narrow projections only — the background never shows position, so it must not recompose at 2Hz.
    val track by vm.currentTrack.collectAsState()
    val isPlaying by vm.isPlayingFlow.collectAsState()
    val isPaused by vm.isPausedFlow.collectAsState()
    val theme by ThemeController.themeColors.collectAsState()
    val animatedPrimary by animateColorAsState(theme.primary, tween(800), label = "bgPrimary")
    val animatedPrimaryDark by animateColorAsState(theme.primaryDark, tween(800), label = "bgPrimaryDark")
    val gradientBg by AppSettings.playerGradientBg.collectAsState()
    val canvasVideoUrl by vm.canvasUrl.collectAsState()
    val canvasOn by AppSettings.canvasEnabled.collectAsState()
    val hasCanvas = canvasOn && canvasVideoUrl != null
    // Whether audio is actively playing (not merely non-paused): gates the Canvas video decoder below.
    val audioPlaying = isPlaying && !isPaused

    Box(modifier) {
        val url = canvasVideoUrl
        if (canvasOn && url != null) {
            CanvasVideoBackground(url, audioPlaying)
        } else {
            AlbumBackdrop(
                gradientBg, animatedPrimary, animatedPrimaryDark, track?.albumArt,
                isPlaying = audioPlaying
            )
        }
        // Dark overlay — lighter over the gradient (it already darkens toward the bottom) so the album
        // colour stays vivid; heavier over the blurred art / canvas for text legibility.
        val overlayAlpha = when {
            hasCanvas -> 0.35f
            gradientBg -> 0.18f
            // The fluid Kawarp background already darkens itself (brightness 0.65), so a lighter scrim.
            else -> 0.28f
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = overlayAlpha))
        )
        // Bottom-weighted scrim — canvas only. The looping video is bright and busy, so the
        // title/artist/context/progress/transport in the lower half need a gradient behind them to
        // stay legible (echo/Spfy do the same). The fluid/gradient/blur backdrops already darken
        // themselves, so the extra band there just looked heavy — skip it. Fade starts high up the
        // frame and ramps to dark at the bottom so there's no hard edge.
        if (hasCanvas) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        androidx.compose.ui.graphics.Brush.verticalGradient(
                            // Transparent at the very top, already noticeably dark by the vertical
                            // centre, ramping to a strong scrim at the bottom so the text/controls read
                            // clearly over a bright canvas video.
                            0.0f to Color.Transparent,
                            0.5f to Color.Black.copy(alpha = 0.45f),
                            1.0f to Color.Black.copy(alpha = 0.92f)
                        )
                    )
            )
        }
    }
}

/** The non-canvas player backdrop: an album-colour gradient (Spfy/YTM/Metrolist style) when
 *  [gradient] is on, otherwise a fluid, flowing warp of the album art (spicy-lyrics style) over the
 *  accent colour — falling back to a static blur on pre-Android-13 devices. */
@Composable
private fun AlbumBackdrop(gradient: Boolean, top: Color, mid: Color, artUrl: String?, isPlaying: Boolean) {
    if (gradient) {
        Box(
            Modifier.fillMaxSize().background(
                androidx.compose.ui.graphics.Brush.verticalGradient(listOf(top, mid, SnepilatchBlack))
            )
        )
    } else {
        ch.snepilatch.app.ui.shared.FluidAlbumBackground(
            artUrl = artUrl,
            isPlaying = isPlaying,
            baseColor = top,
            modifier = Modifier.fillMaxSize()
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingScreen(
    vm: PlaybackViewModel,
    /** When false, the screen paints no background of its own — the morphing card
     *  in SpfyApp supplies a card-anchored background that grows with it. */
    drawBackground: Boolean = true,
    /** Per-frame vertical drag (px, down = positive) for finger-tracked collapse;
     *  when set, replaces the standalone swipe-down-to-dismiss. */
    onMorphDrag: ((Float) -> Unit)? = null,
    /** Drag release with vertical velocity (px/s) so the morph can settle. */
    onMorphDragEnd: ((Float) -> Unit)? = null
) {
    val libraryVm: LibraryViewModel = viewModel()
    // Narrow projections only — positionMs (the 2Hz field) is read exclusively inside PlaybackProgress,
    // so the ~400-line orientation Columns below never recompose on position ticks.
    val track by vm.currentTrack.collectAsState()
    val isPlaying by vm.isPlayingFlow.collectAsState()
    val isPaused by vm.isPausedFlow.collectAsState()
    val isAd by vm.isAdFlow.collectAsState()
    val isShuffling by vm.isShufflingFlow.collectAsState()
    val repeatMode by vm.repeatModeFlow.collectAsState()
    val skippedBack by vm.skippedBack.collectAsState()
    var buttonSkip by remember { mutableStateOf(0 to 0) }
    val onButtonSkip: (Int) -> Unit = { direction ->
        // The button restarts past 3 s like the web player's; only a swipe forces the previous track.
        // The cover strip only moves when the track does.
        val changesTrack = if (direction > 0) vm.skipPrevious() else { vm.skipNext(); true }
        if (changesTrack) buttonSkip = buttonSkip.first + 1 to direction
    }
    // While an ad is being skipped we keep the CURRENT song frozen on screen (cover/title/progress)
    // and show a loading spinner (see spinnerActive) — so the ~2.5s ad skip reads as "loading the next
    // track", not an interruption. `track` is unchanged during an ad, so no blanking is needed.
    val displayTitle = track?.name ?: stringResource(R.string.now_playing_not_playing)
    val displayArtist = track?.artist ?: ""
    val displayArtUrl: String? = track?.albumArt
    // The cover turned over to its lyrics side (#817); kept across rotations, not across app restarts.
    var coverFlipped by rememberSaveable { mutableStateOf(false) }
    val streamLoading by vm.isStreamLoading.collectAsState()
    // Spinner spans the whole ad skip: isAd covers the ad dwell, streamLoading the post-ad resolve.
    val spinnerActive = streamLoading || isAd
    val theme by ThemeController.themeColors.collectAsState()

    val nextPreview by vm.nextTrackPreview.collectAsState()
    val secondNextPreview by vm.secondNextTrackPreview.collectAsState()
    val previousPreview by vm.prevTrackPreview.collectAsState()

    val animatedPrimary by animateColorAsState(theme.primary, tween(800), label = "primary")
    val buttonBg = Color.White.copy(alpha = 0.12f)

    var showMore by remember { mutableStateOf(false) }
    var showPlaylistPicker by remember { mutableStateOf(false) }
    var showJam by remember { mutableStateOf(false) }
    var showCode by remember { mutableStateOf(false) }
    val playerActions = rememberPlayerActions(vm, track) {
        when (it) {
            PlayerOverlay.PlaylistPicker -> showPlaylistPicker = true
            PlayerOverlay.Jam -> showJam = true
            PlayerOverlay.Code -> showCode = true
        }
    }
    val shareContext = LocalContext.current
    val shareTrackLabel = stringResource(R.string.share_track_chooser)
    val canvasVideoUrl by vm.canvasUrl.collectAsState()
    val canvasOn by AppSettings.canvasEnabled.collectAsState()
    val hasCanvas = canvasOn && canvasVideoUrl != null
    // Brighter text in canvas mode for readability over video
    val secondaryText = if (hasCanvas) SnepilatchWhite.copy(alpha = 0.85f) else SnepilatchLightGray
    val tertiaryText = if (hasCanvas) SnepilatchWhite.copy(alpha = 0.65f) else SnepilatchLightGray.copy(alpha = 0.7f)

    Box(Modifier.fillMaxSize()) {
        // Background (Canvas video, or album colour + blurred art + scrim). Skipped
        // during the morph, where the growing card renders the same PlayerBackground
        // card-anchored so the live background grows with the card.
        if (drawBackground) {
            PlayerBackground(vm, Modifier.fillMaxSize())
        }

        BoxWithConstraints(Modifier.fillMaxSize()) {
            val isLandscape = maxWidth > maxHeight
            AudioDeviceEffect(vm)

            if (isLandscape) {
                // === LANDSCAPE LAYOUT ===
                Row(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectHorizontalDragGestures { _, dragAmount ->
                                if (dragAmount > 15) vm.goBack()
                            }
                        }
                        .statusBarsPadding()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        Modifier
                            .weight(0.45f)
                            .fillMaxHeight(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        FlipCard(
                            flipped = coverFlipped,
                            onTap = { coverFlipped = !coverFlipped },
                            modifier = Modifier
                                .fillMaxHeight(0.85f)
                                .aspectRatio(1f),
                            back = { CoverLyrics(vm, Modifier.clip(RoundedCornerShape(16.dp)), visible = coverFlipped) },
                        ) {
                            SlidingCoverImage(
                                url = displayArtUrl,
                                modifier = Modifier.fillMaxSize(),
                                track = CoverTrack(track?.uri, forward = !skippedBack, buttonSkip = buttonSkip),
                                shape = RoundedCornerShape(16.dp),
                                neighbours = CoverNeighbours(previousPreview?.albumArt, nextPreview?.albumArt, secondNextPreview?.albumArt),
                                onSwipe = { if (it > 0) vm.skipPrevious(forceTrackChange = true) else vm.skipNext() },
                            )
                        }
                    }

                    Spacer(Modifier.width(16.dp))

                    Column(
                        Modifier
                            .weight(0.55f)
                            .fillMaxHeight(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            FilledTonalIconButton(
                                onClick = { vm.goBack() },
                                modifier = Modifier.size(38.dp),
                                colors = IconButtonDefaults.filledTonalIconButtonColors(
                                    containerColor = buttonBg,
                                    contentColor = SnepilatchWhite,
                                ),
                            ) {
                                Icon(Icons.Rounded.KeyboardArrowDown, stringResource(R.string.close), modifier = Modifier.size(24.dp))
                            }
                            val ctx by vm.playingContext.collectAsState()
                            // weight(1f) so the header text takes only the space between the two
                            // buttons; without it a long album name grows to its full width and
                            // pushes the right-hand menu button off-screen instead of ellipsizing.
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                            ) {
                                Text(
                                    ctx?.let { stringResource(R.string.now_playing_playing_from, it.type) } ?: stringResource(R.string.now_playing),
                                    color = SnepilatchLightGray,
                                    fontSize = 10.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                ctx?.let {
                                    Text(
                                        it.name,
                                        color = SnepilatchWhite,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            NowPlayingMenu(
                                showMore = showMore,
                                onShowMore = { showMore = it },
                                actions = playerActions,
                                track = track,
                                buttonBg = buttonBg,
                                vm = vm,
                            )
                        }

                        Spacer(Modifier.weight(0.3f))

                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                                Text(
                                    displayTitle,
                                    color = SnepilatchWhite,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    displayArtist,
                                    color = SnepilatchLightGray,
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    track?.albumName.orEmpty(),
                                    color = SnepilatchLightGray.copy(alpha = 0.7f),
                                    fontSize = 11.sp,
                                    minLines = 1,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            PlayerShortcutButton(playerActions, vm, track, buttonBg, animatedPrimary, 40.dp, 24.dp)
                        }

                        Spacer(Modifier.height(8.dp))

                        // Progress bar — or the infiniPlay remix map while remixing. Extracted to a leaf so
                        // its per-frame smooth-position updates recompose only the bar, not this Column.
                        PlaybackProgress(
                            vm = vm,
                            animatedPrimary = animatedPrimary,
                            timeColor = SnepilatchLightGray,
                            timeFontSize = 11.sp,
                        )

                        Spacer(Modifier.weight(0.2f))

                        PlayerControls(vm, animatedPrimary, buttonBg, spinnerActive, compact = true, onSkip = onButtonSkip)

                        Spacer(Modifier.weight(0.2f))

                        PlayerBottomBar(vm, animatedPrimary, buttonBg, shareTrackLabel, compact = true)

                        Spacer(Modifier.height(8.dp))
                    }
                }
            } else {
                // === PORTRAIT LAYOUT ===
                Column(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(onMorphDrag) {
                            if (onMorphDrag != null) {
                                // Finger-tracked collapse driven by the parent morph.
                                val tracker = androidx.compose.ui.input.pointer.util.VelocityTracker()
                                detectVerticalDragGestures(
                                    onDragStart = { tracker.resetTracking() },
                                    onDragEnd = { onMorphDragEnd?.invoke(tracker.calculateVelocity().y) },
                                    onDragCancel = { onMorphDragEnd?.invoke(0f) }
                                ) { change, dragAmount ->
                                    tracker.addPosition(change.uptimeMillis, change.position)
                                    onMorphDrag(dragAmount)
                                }
                            } else {
                                var totalDrag = 0f
                                var handled = false
                                detectVerticalDragGestures(
                                    onDragStart = { totalDrag = 0f; handled = false },
                                    onDragEnd = { if (totalDrag > 80 && !handled) { handled = true; vm.goBack() } },
                                    onDragCancel = { totalDrag = 0f; handled = false }
                                ) { _, dragAmount ->
                                    totalDrag += dragAmount
                                }
                            }
                        }
                        .navigationBarsPadding()
                        .padding(horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(Modifier.statusBarsPadding().height(8.dp))

                    Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            FilledTonalIconButton(
                                onClick = { vm.goBack() },
                                modifier = Modifier.size(44.dp),
                                colors = IconButtonDefaults.filledTonalIconButtonColors(
                                    containerColor = buttonBg,
                                    contentColor = SnepilatchWhite,
                                ),
                            ) {
                                Icon(Icons.Rounded.KeyboardArrowDown, stringResource(R.string.close), modifier = Modifier.size(28.dp))
                            }
                            val ctx by vm.playingContext.collectAsState()
                            // weight(1f) so a long album name ellipsizes instead of pushing the
                            // right-hand EQ button off-screen (see the portrait header above).
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 8.dp)
                                    .clickable(enabled = ctx?.uri != null) { vm.navigateToContext() }
                            ) {
                                Text(
                                    ctx?.let { stringResource(R.string.now_playing_playing_from, it.type) } ?: stringResource(R.string.now_playing),
                                    color = secondaryText,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                ctx?.let {
                                    Text(
                                        it.name,
                                        color = SnepilatchWhite,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            // EQ button: our screen when the in-app EQ owns the session, the system
                            // effect panel otherwise (see PlaybackViewModel.openEqualizer).
                            FilledTonalIconButton(
                                onClick = { vm.openEqualizer(shareContext) },
                                modifier = Modifier.size(44.dp),
                                colors = IconButtonDefaults.filledTonalIconButtonColors(
                                    containerColor = buttonBg,
                                    contentColor = SnepilatchWhite,
                                ),
                            ) {
                                Icon(Icons.Rounded.Tune, stringResource(R.string.equalizer), modifier = Modifier.size(22.dp))
                            }
                    }

                    Spacer(Modifier.weight(0.3f))

                    // Keep the cover-sized swipe target over Canvas while leaving the video visible.
                    FlipCard(
                        flipped = coverFlipped,
                        onTap = { coverFlipped = !coverFlipped },
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f),
                        back = { CoverLyrics(vm, Modifier.clip(RoundedCornerShape(16.dp)), visible = coverFlipped) },
                    ) {
                        SlidingCoverImage(
                            url = displayArtUrl,
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer { alpha = if (hasCanvas) 0f else 1f },
                            track = CoverTrack(track?.uri, forward = !skippedBack, buttonSkip = buttonSkip),
                            shape = RoundedCornerShape(16.dp),
                            neighbours = CoverNeighbours(previousPreview?.albumArt, nextPreview?.albumArt, secondNextPreview?.albumArt),
                            onSwipe = { if (it > 0) vm.skipPrevious(forceTrackChange = true) else vm.skipNext() },
                            clipToFrame = false,
                        )
                    }

                    Spacer(Modifier.height(32.dp))

                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f).padding(end = 12.dp)) {
                            MarqueeText(
                                text = displayTitle,
                                color = SnepilatchWhite,
                                fontSize = 22.sp,
                                isPlaying = isPlaying,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.height(2.dp))
                            MarqueeText(
                                text = displayArtist,
                                color = secondaryText,
                                fontSize = 15.sp,
                                isPlaying = isPlaying,
                                modifier = Modifier.clickable { vm.openArtistFromCurrentTrack() }
                            )
                            Spacer(Modifier.height(2.dp))
                            val albumName = track?.albumName?.takeIf { !isAd }
                            MarqueeText(
                                text = albumName.orEmpty(),
                                color = tertiaryText,
                                fontSize = 13.sp,
                                isPlaying = isPlaying,
                                modifier = Modifier.clickable(enabled = albumName != null) {
                                    vm.openAlbumFromCurrentTrack()
                                }
                            )
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            PlayerShortcutButton(playerActions, vm, track, buttonBg, animatedPrimary, 48.dp, 28.dp)
                            NowPlayingMenu(
                                showMore = showMore,
                                onShowMore = { showMore = it },
                                actions = playerActions,
                                track = track,
                                buttonBg = buttonBg,
                                vm = vm,
                            )
                        }
                    }

                    Spacer(Modifier.height(20.dp))

                    // Progress bar — thick rounded bar, or the infiniPlay remix map while remixing. Extracted
                    // to a leaf so its per-frame smooth-position updates recompose only the bar, not this Column.
                    PlaybackProgress(
                        vm = vm,
                        animatedPrimary = animatedPrimary,
                        timeColor = secondaryText,
                        timeFontSize = 12.sp,
                    )

                    Spacer(Modifier.weight(0.15f))

                    PlayerControls(vm, animatedPrimary, buttonBg, spinnerActive, compact = false, onSkip = onButtonSkip)

                    Spacer(Modifier.weight(0.2f))

                    PlayerBottomBar(vm, animatedPrimary, buttonBg, shareTrackLabel, compact = false)

                    Spacer(Modifier.height(12.dp))
                }
            }
        }
    } // end Box

    // Playlist picker dialog
    if (showJam) {
        ch.snepilatch.app.ui.shared.JamSheet(onDismiss = { showJam = false })
    }
    if (showCode) {
        track?.let { ch.snepilatch.app.ui.shared.ScannableCodeSheet(it, animatedPrimary) { showCode = false } }
    }

    if (showPlaylistPicker) {
        val libraryItems by libraryVm.library.collectAsState()
        PlaylistPickerDialog(
            playlists = libraryItems.filter { it.type == "playlist" },
            onPick = { playlist ->
                track?.uri?.let { uri -> vm.addTrackToPlaylist(playlist.uri.removePrefix("spotify:playlist:"), uri) }
                showPlaylistPicker = false
            },
            onDismiss = { showPlaylistPicker = false },
        )
    }
}

/** The button beside the track details: the like toggle by default, or whichever [PlayerAction] the setting picked. */
@Composable
private fun PlayerShortcutButton(
    actions: List<PlayerAction>,
    vm: PlaybackViewModel,
    track: ch.snepilatch.app.data.TrackInfo?,
    buttonBg: Color,
    accent: Color,
    size: Dp,
    iconSize: Dp,
) {
    val shortcut by AppSettings.playerShortcut.collectAsState()
    if (shortcut == PlayerShortcut.LIKE) {
        val isLiked by vm.currentTrackLiked.collectAsState()
        LikeToggleButton(isLiked, track?.uri, vm, buttonBg, accent, size, iconSize)
        return
    }
    val action = actions.first { it.shortcut == shortcut }
    FilledTonalIconButton(
        onClick = action.run,
        enabled = action.enabled,
        modifier = Modifier.size(size),
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = buttonBg,
            contentColor = SnepilatchWhite.copy(alpha = 0.7f),
        ),
    ) { Icon(action.icon, action.label, modifier = Modifier.size(iconSize)) }
}

@Composable
private fun InfoPill(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(
        Modifier
            .height(20.dp)
            .background(Color.White.copy(alpha = 0.10f), RoundedCornerShape(50))
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Icon(icon, null, tint = SnepilatchWhite, modifier = Modifier.size(9.dp))
        Text(text, color = SnepilatchWhite, fontSize = 9.sp, maxLines = 1)
    }
}

/**
 * The audio-source pill. Always visible: when a stream is active it names the source (Spfy CDN,
 * Qobuz, …); when nothing is streaming locally ([provider] == null — idle, or playing on a remote
 * Connect device) it shows a dimmed "No CDN" idle state instead of vanishing.
 */
/** Keep the audio-output name current while the player is shown (registers an AudioDeviceCallback). */
@Composable
private fun AudioDeviceEffect(vm: PlaybackViewModel) {
    val ctx = LocalContext.current
    DisposableEffect(Unit) {
        val am = ctx.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
        vm.updateAudioOutput(ctx)
        val cb = object : android.media.AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out android.media.AudioDeviceInfo>?) { vm.updateAudioOutput(ctx) }
            override fun onAudioDevicesRemoved(removed: Array<out android.media.AudioDeviceInfo>?) { vm.updateAudioOutput(ctx) }
        }
        am.registerAudioDeviceCallback(cb, null)
        onDispose { am.unregisterAudioDeviceCallback(cb) }
    }
}

/** A tonal icon button at a fixed size with the standard player button colours. */
@Composable
private fun TonalIconBtn(onClick: () -> Unit, size: Dp, buttonBg: Color, enabled: Boolean = true, content: @Composable () -> Unit) {
    FilledTonalIconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(size),
        colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = buttonBg, contentColor = SnepilatchWhite),
    ) { content() }
}

@Composable
private fun TonalIconToggle(
    checked: Boolean,
    onToggle: () -> Unit,
    size: Dp,
    buttonBg: Color,
    accent: Color,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    FilledTonalIconToggleButton(
        checked = checked,
        onCheckedChange = { onToggle() },
        enabled = enabled,
        modifier = Modifier.size(size),
        colors = IconButtonDefaults.filledTonalIconToggleButtonColors(
            containerColor = buttonBg,
            contentColor = SnepilatchWhite,
            checkedContainerColor = accent.copy(alpha = 0.45f),
            checkedContentColor = SnepilatchWhite,
        ),
    ) { content() }
}

/** The 5-button transport row (shuffle, prev, play/pause, next, repeat), shared by both orientations.
 *  compact = landscape (smaller controls). Collects its play-state projections internally so caller
 *  bodies don't recompose on those flows. */
@Composable
private fun PlayerControls(
    vm: PlaybackViewModel,
    animatedPrimary: Color,
    buttonBg: Color,
    spinnerActive: Boolean,
    compact: Boolean,
    onSkip: (Int) -> Unit,
) {
    val isPlaying by vm.isPlayingFlow.collectAsState()
    val isPaused by vm.isPausedFlow.collectAsState()
    val isShuffling by vm.isShufflingFlow.collectAsState()
    val shuffleMode by vm.shuffleModeFlow.collectAsState()
    val repeatMode by vm.repeatModeFlow.collectAsState()
    val canToggleShuffle by vm.canToggleShuffleFlow.collectAsState()
    val canToggleRepeat by vm.canToggleRepeatFlow.collectAsState()
    val nextReady by vm.isNextReady.collectAsState()
    val isCurrentlyStreaming by vm.isStreaming.collectAsState()
    val nextLoading = !nextReady && isCurrentlyStreaming
    // A jam whose host turned guest controls off: the transport is shown but does nothing, like the official app.
    val jamControls = jamAllowsControls()
    val sideBtn = if (compact) 44.dp else 52.dp
    val skipBtn = if (compact) 48.dp else 56.dp
    val playBtn = if (compact) 60.dp else 72.dp
    val sideIcon = if (compact) 20.dp else 22.dp
    val skipIcon = if (compact) 28.dp else 32.dp
    val playIcon = if (compact) 32.dp else 38.dp
    val playSpinnerSize = if (compact) 26.dp else 30.dp
    val nextSpinnerSize = if (compact) 20.dp else 22.dp
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        TonalIconToggle(
            isShuffling, { vm.toggleShuffle() }, sideBtn, buttonBg, animatedPrimary, enabled = canToggleShuffle
        ) {
            Icon(
                if (shuffleMode == "smart") ImageVector.vectorResource(R.drawable.ic_shuffle_smart) else Icons.Rounded.Shuffle,
                stringResource(R.string.shuffle),
                modifier = Modifier.size(sideIcon)
            )
        }
        TonalIconBtn({ onSkip(1) }, skipBtn, buttonBg, enabled = jamControls) {
            Icon(Icons.Rounded.SkipPrevious, stringResource(R.string.previous), modifier = Modifier.size(skipIcon))
        }
        FilledIconButton(
            onClick = { if (!spinnerActive) vm.togglePlayPause() },
            enabled = jamControls,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = if (spinnerActive) animatedPrimary.copy(alpha = 0.5f) else animatedPrimary,
                contentColor = SnepilatchWhite,
            ),
            modifier = Modifier.size(playBtn),
        ) {
            if (spinnerActive) {
                LoadingIndicator(color = SnepilatchWhite, modifier = Modifier.size(playSpinnerSize))
            } else {
                Icon(
                    if (isPaused || !isPlaying) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                    stringResource(R.string.play_pause), modifier = Modifier.size(playIcon)
                )
            }
        }
        TonalIconBtn({ onSkip(-1) }, skipBtn, buttonBg, enabled = jamControls) {
            if (nextLoading) {
                LoadingIndicator(color = SnepilatchWhite, modifier = Modifier.size(nextSpinnerSize))
            } else {
                Icon(Icons.Rounded.SkipNext, stringResource(R.string.next), modifier = Modifier.size(skipIcon))
            }
        }
        TonalIconToggle(
            repeatMode != "off", { vm.cycleRepeat() }, sideBtn, buttonBg, animatedPrimary, enabled = canToggleRepeat
        ) {
            Icon(
                when (repeatMode) { "track" -> Icons.Rounded.RepeatOne; else -> Icons.Rounded.Repeat },
                stringResource(R.string.repeat),
                modifier = Modifier.size(sideIcon)
            )
        }
    }
}

/** Bottom bar: device-output toggle + stacked pills (left), share + queue (right). Shared by both
 *  orientations; compact = landscape. Collects its audio/provider projections internally. */
@Composable
private fun PlayerBottomBar(
    vm: PlaybackViewModel,
    animatedPrimary: Color,
    buttonBg: Color,
    shareTrackLabel: String,
    compact: Boolean,
) {
    val track by vm.currentTrack.collectAsState()
    val provider by vm.streamProvider.collectAsState()
    val streaming by vm.isStreaming.collectAsState()
    val audioOutput by vm.audioOutputName.collectAsState()
    val audioType by vm.audioOutputType.collectAsState()
    val activeDevice by vm.activeDeviceName.collectAsState()
    val context = LocalContext.current
    val audioIcon = when (audioType) {
        "bluetooth" -> Icons.Rounded.Bluetooth
        "wired" -> Icons.Rounded.Headphones
        "usb" -> Icons.Rounded.Usb
        else -> Icons.Rounded.Speaker
    }
    // Playing on another Spfy Connect device: show it with a computer icon + name instead of the
    // (then-irrelevant) local Bluetooth/wired output.
    val remoteDevice = if (!streaming) activeDevice?.takeIf { it != android.os.Build.MODEL } else null
    val outIcon = if (remoteDevice != null) Icons.Rounded.Computer else audioIcon
    val actionBtn = if (compact) 38.dp else 44.dp
    val actionIcon = if (compact) 20.dp else 22.dp
    val leftSpacing = if (compact) 6.dp else 8.dp
    val rightSpacing = if (compact) 10.dp else 12.dp
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(leftSpacing)
        ) {
            FilledTonalIconToggleButton(
                checked = streaming,
                onCheckedChange = { vm.loadDevices(); vm.showDevices.value = true },
                modifier = Modifier.size(actionBtn),
                colors = IconButtonDefaults.filledTonalIconToggleButtonColors(
                    containerColor = buttonBg,
                    contentColor = SnepilatchWhite,
                    checkedContainerColor = animatedPrimary,
                    checkedContentColor = SnepilatchWhite,
                ),
            ) {
                Icon(outIcon, stringResource(R.string.audio_output), modifier = Modifier.size(actionIcon))
            }
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                if (remoteDevice != null) {
                    InfoPill(Icons.Rounded.Computer, remoteDevice)
                } else {
                    audioOutput?.let { InfoPill(audioIcon, it) }
                }
                // Always show the source pill (idle "No CDN" state when nothing streams).
                SourcePill(provider)
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(rightSpacing)
        ) {
            TonalIconBtn({ track?.uri?.let { shareSpfyUri(context, it, shareTrackLabel) } }, actionBtn, buttonBg) {
                Icon(Icons.Rounded.Share, stringResource(R.string.share), modifier = Modifier.size(actionIcon))
            }
            TonalIconBtn({ vm.openQueue() }, actionBtn, buttonBg) {
                Icon(
                    Icons.AutoMirrored.Rounded.QueueMusic,
                    stringResource(R.string.queue),
                    modifier = Modifier.size(actionIcon)
                )
            }
        }
    }
}

@Composable
private fun SourcePill(provider: String?) {
    val jamVm: ch.snepilatch.app.viewmodel.JamViewModel = viewModel()
    val jam by jamVm.jam.collectAsState()
    val inJam = jam != null
    val active = provider != null || inJam
    val label = when {
        inJam -> stringResource(R.string.jam_connected)
        provider == null -> "No CDN"
        provider == "qobuz" -> "Qobuz"
        provider == "deezer" -> "Deezer"
        else -> provider.replaceFirstChar { it.uppercase() }
    }
    val icon = when {
        inJam -> Icons.Rounded.Groups
        active -> Icons.Rounded.MusicNote
        else -> Icons.Rounded.CloudOff
    }
    val bgAlpha = if (active) 0.10f else 0.06f
    val fgAlpha = if (active) 1f else 0.55f
    Row(
        Modifier
            .height(20.dp)
            .background(Color.White.copy(alpha = bgAlpha), RoundedCornerShape(50))
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Icon(icon, null, tint = SnepilatchWhite.copy(alpha = fgAlpha), modifier = Modifier.size(9.dp))
        Text(label, color = SnepilatchWhite.copy(alpha = fgAlpha), fontSize = 9.sp, maxLines = 1)
    }
}

/** What the three dots menu can open on top of the player. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NowPlayingMenu(
    showMore: Boolean,
    onShowMore: (Boolean) -> Unit,
    actions: List<PlayerAction>,
    track: ch.snepilatch.app.data.TrackInfo?,
    buttonBg: Color,
    vm: PlaybackViewModel,
) {
    FilledTonalIconButton(
        onClick = { onShowMore(true) },
        modifier = Modifier.size(44.dp),
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = buttonBg,
            contentColor = SnepilatchWhite,
        ),
    ) {
        Icon(Icons.Rounded.MoreVert, stringResource(R.string.more), modifier = Modifier.size(22.dp))
    }

    if (showMore) {
        // Only here, not in the shared action table: it depends on where the track is playing from,
        // so it is nothing a configurable button or a row swipe could carry (#851).
        val canRemove = vm.canRemovePlayingFromPlaylist()
        val removeLabel = stringResource(R.string.remove_from_playlist)
        val items = actions.flatMap { action ->
            listOf(MenuAction(action.icon, action.label, action.tint) { onShowMore(false); action.run() }) +
                if (canRemove && action.shortcut == PlayerShortcut.ADD_TO_PLAYLIST) {
                    listOf(MenuAction(Icons.Rounded.PlaylistRemove, removeLabel) { onShowMore(false); vm.removePlayingFromPlaylist() })
                } else {
                    emptyList()
                }
        }
        EntityMenuSheet(
            imageUrl = track?.albumArt,
            title = track?.name,
            subtitle = track?.artist,
            actions = items,
            onDismiss = { onShowMore(false) },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MarqueeText(
    text: String,
    color: androidx.compose.ui.graphics.Color,
    fontSize: androidx.compose.ui.unit.TextUnit,
    isPlaying: Boolean,
    fontWeight: FontWeight? = null,
    modifier: Modifier = Modifier
) {
    // Same behaviour as the basicMarquee this replaces — the title drives all the way through, a
    // second copy follows a gap behind it, and there is a pause between passes — but not the same
    // cost (#856). basicMarquee scrolls by re-laying-out the text on every frame, which held the
    // whole player at the panel's 120Hz for as long as the song played: 83% of a core against 16%
    // for a title that fits. Here the slide is a translation only ever read inside graphicsLayer,
    // so a step moves the layer and nothing above it composes or measures again, it reads the real
    // frame clock so the motion keeps its speed, and it steps on every second refresh.
    var textWidth by remember { mutableIntStateOf(0) }
    var boxWidth by remember { mutableIntStateOf(0) }
    val overflowing = boxWidth > 0 && textWidth > boxWidth
    val density = LocalDensity.current
    val gapPx = with(density) { MARQUEE_GAP.toPx() }
    val speedPxPerSecond = with(density) { MARQUEE_VELOCITY.toPx() }
    val offset = remember { mutableFloatStateOf(0f) }
    val loopPx = textWidth + gapPx

    LaunchedEffect(text, isPlaying, overflowing, loopPx) {
        offset.floatValue = 0f
        if (!isPlaying || !overflowing || loopPx <= 0f) return@LaunchedEffect
        while (true) {
            delay(MARQUEE_REPEAT_DELAY_MS)
            var travelled = 0f
            var pending = 0f
            var previous = withFrameNanos { it }
            while (travelled < loopPx) {
                withFrameNanos { now ->
                    pending += (now - previous) / 1_000_000_000f
                    previous = now
                    // Every step is worth exactly the time that passed, so the slide keeps its speed;
                    // holding them back until half a frame has built up moves the text on every second
                    // refresh of a 120Hz panel, which still reads as smooth and costs half as much.
                    if (pending >= MARQUEE_MIN_STEP_SECONDS) {
                        travelled = (travelled + pending * speedPxPerSecond).coerceAtMost(loopPx)
                        offset.floatValue = travelled
                        pending = 0f
                    }
                }
            }
            // A whole loop on: the trailing copy now sits exactly where the first one started, so
            // going back to zero is invisible and the next pass carries straight on.
            offset.floatValue = 0f
        }
    }

    Box(modifier.fillMaxWidth().clipToBounds().onSizeChanged { boxWidth = it.width }) {
        Row(
            Modifier
                .wrapContentWidth(Alignment.Start, unbounded = true)
                .graphicsLayer { translationX = -offset.floatValue },
        ) {
            MarqueeLabel(text, color, fontSize, fontWeight) { textWidth = it }
            if (overflowing) {
                Spacer(Modifier.width(MARQUEE_GAP))
                MarqueeLabel(text, color, fontSize, fontWeight)
            }
        }
    }
}

/** One copy of the sliding text; the first reports its width, which is what the loop is measured on. */
@Composable
private fun MarqueeLabel(
    text: String,
    color: androidx.compose.ui.graphics.Color,
    fontSize: androidx.compose.ui.unit.TextUnit,
    fontWeight: FontWeight?,
    onWidth: ((Int) -> Unit)? = null,
) {
    Text(
        text = text,
        color = color,
        fontSize = fontSize,
        fontWeight = fontWeight,
        softWrap = false,
        maxLines = 1,
        modifier = if (onWidth == null) Modifier else Modifier.onSizeChanged { onWidth(it.width) },
    )
}

/** How fast a title that does not fit slides, the gap before its repeat, the wait between passes. */
private val MARQUEE_VELOCITY = 40.dp
private val MARQUEE_GAP = 48.dp
private const val MARQUEE_REPEAT_DELAY_MS = 1200L
private const val MARQUEE_MIN_STEP_SECONDS = 0.015f
