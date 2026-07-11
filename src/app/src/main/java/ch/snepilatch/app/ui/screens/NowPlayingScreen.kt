@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package ch.snepilatch.app.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.snepilatch.app.R
import coil.compose.AsyncImage
import android.graphics.SurfaceTexture
import android.net.Uri
import android.view.TextureView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import ch.snepilatch.app.ui.components.SheetNavBarFix
import ch.snepilatch.app.ui.components.SpotifyImage
import ch.snepilatch.app.ui.components.TightAlertDialog
import ch.snepilatch.app.ui.theme.*
import ch.snepilatch.app.util.formatTime
import ch.snepilatch.app.viewmodel.SpotifyViewModel

/**
 * The now-playing background: Canvas video when enabled, otherwise the album's
 * accent colour with the blurred album art over it, topped by a dark scrim.
 * Extracted so the expanding-player morph (SpotifyApp) can render the same *live*
 * background — video included — inside the growing card, anchored to it, instead
 * of a still. The video transform is recomputed on view resize so it stays
 * fit-cropped while the card grows.
 */
@Composable
fun PlayerBackground(vm: SpotifyViewModel, modifier: Modifier = Modifier) {
    val playback by vm.playback.collectAsState()
    val track = playback.track
    val theme by vm.themeColors.collectAsState()
    val animatedPrimary by animateColorAsState(theme.primary, tween(800), label = "bgPrimary")
    val canvasVideoUrl by vm.canvasUrl.collectAsState()
    val canvasOn by vm.canvasEnabled.collectAsState()
    val hasCanvas = canvasOn && canvasVideoUrl != null

    Box(modifier) {
        // Canvas video background (looping, muted)
        if (canvasOn && canvasVideoUrl != null) {
            val context = LocalContext.current
            val density = LocalDensity.current
            var canvasPlayer by remember { mutableStateOf<ExoPlayer?>(null) }
            var textureRef by remember { mutableStateOf<TextureView?>(null) }
            var surfaceReady by remember { mutableStateOf(false) }
            var videoSizePx by remember { mutableStateOf(0 to 0) }

            val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
            DisposableEffect(canvasVideoUrl, lifecycleOwner) {
                val player = ExoPlayer.Builder(context).build().apply {
                    setMediaItem(MediaItem.fromUri(Uri.parse(canvasVideoUrl!!)))
                    repeatMode = Player.REPEAT_MODE_ALL
                    volume = 0f
                    playWhenReady = true
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

                // Pause/resume canvas with activity lifecycle (handles backgrounding)
                val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                    when (event) {
                        androidx.lifecycle.Lifecycle.Event.ON_START,
                        androidx.lifecycle.Lifecycle.Event.ON_RESUME -> {
                            player.playWhenReady = true
                            // Re-attach surface to force frame refresh after resume
                            if (surfaceReady) {
                                textureRef?.let {
                                    player.clearVideoTextureView(it)
                                    player.setVideoTextureView(it)
                                }
                            }
                        }
                        androidx.lifecycle.Lifecycle.Event.ON_STOP -> {
                            player.playWhenReady = false
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

            // Size the TextureView to COVER the (growing) card at the video's aspect
            // ratio, then centre + clip it. The view's own bounds already match the
            // video aspect, so the raw TextureView fills them without distortion — no
            // per-frame matrix that loses the race with layout while the card resizes.
            BoxWithConstraints(Modifier.fillMaxSize().clipToBounds()) {
                val (vw, vh) = videoSizePx
                val boxW = constraints.maxWidth.toFloat()
                val boxH = constraints.maxHeight.toFloat()
                val coverMod = if (vw > 0 && vh > 0 && minOf(boxW, boxH) > 0f) {
                    val scale = maxOf(boxW / vw, boxH / vh)
                    // requiredSize, not size: the cover width can exceed the box
                    // (1080px) and must NOT be clamped to it, or the video gets
                    // squished horizontally. The overflow is clipped by clipToBounds.
                    Modifier.requiredSize(
                        with(density) { (vw * scale).toDp() },
                        with(density) { (vh * scale).toDp() }
                    )
                } else {
                    Modifier.fillMaxSize()
                }
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
        } else {
            // Solid one-colour base (the album's accent) under the blurred art.
            Box(Modifier.fillMaxSize().background(animatedPrimary))
            // Blurred album art.
            var displayedArt by remember { mutableStateOf(track?.albumArt) }
            if (track?.albumArt != null) displayedArt = track.albumArt
            androidx.compose.animation.Crossfade(
                targetState = displayedArt,
                animationSpec = tween(800),
                label = "bgArt"
            ) { artUrl ->
                if (artUrl != null) {
                    AsyncImage(
                        model = artUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().blur(80.dp)
                    )
                }
            }
        }
        // Dark overlay
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = if (hasCanvas) 0.35f else 0.45f))
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingScreen(
    vm: SpotifyViewModel,
    /** When false, the screen paints no background of its own — the morphing card
     *  in SpotifyApp supplies a card-anchored background that grows with it. */
    drawBackground: Boolean = true,
    /** Per-frame vertical drag (px, down = positive) for finger-tracked collapse;
     *  when set, replaces the standalone swipe-down-to-dismiss. */
    onMorphDrag: ((Float) -> Unit)? = null,
    /** Drag release with vertical velocity (px/s) so the morph can settle. */
    onMorphDragEnd: ((Float) -> Unit)? = null
) {
    val playback by vm.playback.collectAsState()
    val track = playback.track
    // While an ad is being skipped, KotifyClient plays a local silent clip; show a "Skipping ad…"
    // placeholder (no art, no metadata) instead of the lingering previous track's info.
    val displayTitle = when {
        playback.isAd -> stringResource(R.string.now_playing_skipping_ad)
        else -> track?.name ?: stringResource(R.string.now_playing_not_playing)
    }
    val displayArtist = if (playback.isAd) "" else track?.artist ?: ""
    val displayArtUrl: String? = if (playback.isAd) null else track?.albumArt
    val streamLoading by vm.isStreamLoading.collectAsState()
    val theme by vm.themeColors.collectAsState()

    val animatedPrimary by animateColorAsState(theme.primary, tween(800), label = "primary")
    val buttonBg = Color.White.copy(alpha = 0.12f)

    var showMore by remember { mutableStateOf(false) }
    var showPlaylistPicker by remember { mutableStateOf(false) }
    val shareContext = LocalContext.current
    val shareTrackLabel = stringResource(R.string.share_track_chooser)
    val canvasVideoUrl by vm.canvasUrl.collectAsState()
    val canvasOn by vm.canvasEnabled.collectAsState()
    val hasCanvas = canvasOn && canvasVideoUrl != null
    // Brighter text in canvas mode for readability over video
    val secondaryText = if (hasCanvas) SpotifyWhite.copy(alpha = 0.85f) else SpotifyLightGray
    val tertiaryText = if (hasCanvas) SpotifyWhite.copy(alpha = 0.65f) else SpotifyLightGray.copy(alpha = 0.7f)

    Box(Modifier.fillMaxSize()) {
        // Background (Canvas video, or album colour + blurred art + scrim). Skipped
        // during the morph, where the growing card renders the same PlayerBackground
        // card-anchored so the live background grows with the card.
        if (drawBackground) {
            PlayerBackground(vm, Modifier.fillMaxSize())
        }

        BoxWithConstraints(Modifier.fillMaxSize()) {
            val isLandscape = maxWidth > maxHeight

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
                    // Left side: Album art
                    Column(
                        Modifier
                            .weight(0.45f)
                            .fillMaxHeight(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        SpotifyImage(
                            url = displayArtUrl,
                            modifier = Modifier
                                .fillMaxHeight(0.85f)
                                .aspectRatio(1f),
                            shape = RoundedCornerShape(16.dp)
                        )
                    }

                    Spacer(Modifier.width(16.dp))

                    // Right side: Info + controls
                    Column(
                        Modifier
                            .weight(0.55f)
                            .fillMaxHeight(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Top bar
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
                                    contentColor = SpotifyWhite,
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
                                    color = SpotifyLightGray,
                                    fontSize = 10.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                ctx?.let {
                                    Text(
                                        it.name,
                                        color = SpotifyWhite,
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
                                onShowPlaylistPicker = { showPlaylistPicker = true },
                                vm = vm,
                                track = track,
                                shareContext = shareContext,
                                buttonBg = buttonBg
                            )
                        }

                        Spacer(Modifier.weight(0.3f))

                        // Track info + like
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                                Text(
                                    displayTitle,
                                    color = SpotifyWhite,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    displayArtist,
                                    color = SpotifyLightGray,
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                track?.albumName?.let {
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        it,
                                        color = SpotifyLightGray.copy(alpha = 0.7f),
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            val isLiked by vm.currentTrackLiked.collectAsState()
                            FilledIconToggleButton(
                                checked = isLiked,
                                onCheckedChange = { _ ->
                                    val id = track?.uri?.removePrefix("spotify:track:") ?: return@FilledIconToggleButton
                                    if (isLiked) vm.unlikeSong(id) else vm.likeSong(id)
                                },
                                modifier = Modifier.size(40.dp),
                                colors = IconButtonDefaults.filledIconToggleButtonColors(
                                    containerColor = buttonBg,
                                    contentColor = SpotifyWhite.copy(alpha = 0.7f),
                                    checkedContainerColor = buttonBg,
                                    checkedContentColor = animatedPrimary,
                                ),
                            ) {
                                Icon(
                                    if (isLiked) Icons.Rounded.Favorite else Icons.Filled.FavoriteBorder,
                                    stringResource(R.string.like),
                                    modifier = Modifier.size(24.dp),
                                )
                            }
                        }

                        Spacer(Modifier.height(8.dp))

                        // Progress bar
                        var seekDragging by remember { mutableStateOf(false) }
                        var seekDragValue by remember { mutableFloatStateOf(0f) }
                        val sliderValue = if (seekDragging) seekDragValue
                        else if (playback.durationMs > 0) (playback.positionMs.toFloat() / playback.durationMs) else 0f
                        Slider(
                            value = sliderValue,
                            onValueChange = { seekDragging = true; seekDragValue = it },
                            onValueChangeFinished = {
                                vm.seekTo((seekDragValue * playback.durationMs).toLong())
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
                                inactiveTrackColor = SpotifyWhite.copy(alpha = 0.15f)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(formatTime(playback.positionMs), color = SpotifyLightGray, fontSize = 11.sp)
                            Text(formatTime(playback.durationMs), color = SpotifyLightGray, fontSize = 11.sp)
                        }

                        Spacer(Modifier.weight(0.2f))

                        // Controls
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            FilledTonalIconToggleButton(
                                checked = playback.isShuffling,
                                onCheckedChange = { vm.toggleShuffle() },
                                colors = IconButtonDefaults.filledTonalIconToggleButtonColors(
                                    containerColor = buttonBg,
                                    contentColor = SpotifyWhite,
                                    checkedContainerColor = buttonBg,
                                    checkedContentColor = animatedPrimary,
                                ),
                                modifier = Modifier.size(44.dp),
                            ) {
                                Icon(Icons.Rounded.Shuffle, stringResource(R.string.shuffle), modifier = Modifier.size(20.dp))
                            }
                            FilledTonalIconButton(
                                onClick = { vm.skipPrevious() },
                                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                                    containerColor = buttonBg,
                                    contentColor = SpotifyWhite
                                ),
                                modifier = Modifier.size(48.dp),
                            ) {
                                Icon(Icons.Rounded.SkipPrevious, stringResource(R.string.previous), modifier = Modifier.size(28.dp))
                            }
                            FilledIconButton(
                                onClick = { if (!streamLoading) vm.togglePlayPause() },
                                colors = IconButtonDefaults.filledIconButtonColors(
                                    containerColor = if (streamLoading) animatedPrimary.copy(alpha = 0.5f) else animatedPrimary,
                                    contentColor = SpotifyWhite,
                                ),
                                modifier = Modifier.size(60.dp),
                            ) {
                                if (streamLoading) {
                                    LoadingIndicator(color = SpotifyWhite, modifier = Modifier.size(26.dp))
                                } else {
                                    Icon(
                                        if (playback.isPaused || !playback.isPlaying) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                                        stringResource(R.string.play_pause), modifier = Modifier.size(32.dp)
                                    )
                                }
                            }
                            val nextReady by vm.isNextReady.collectAsState()
                            val isCurrentlyStreaming by vm.isStreaming.collectAsState()
                            val nextLoading = !nextReady && isCurrentlyStreaming
                            FilledTonalIconButton(
                                onClick = { vm.skipNext() },
                                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                                    containerColor = buttonBg,
                                    contentColor = SpotifyWhite
                                ),
                                modifier = Modifier.size(48.dp),
                            ) {
                                if (nextLoading) {
                                    LoadingIndicator(color = SpotifyWhite, modifier = Modifier.size(20.dp))
                                } else {
                                    Icon(Icons.Rounded.SkipNext, stringResource(R.string.next), modifier = Modifier.size(28.dp))
                                }
                            }
                            FilledTonalIconToggleButton(
                                checked = playback.repeatMode != "off",
                                onCheckedChange = { vm.cycleRepeat() },
                                colors = IconButtonDefaults.filledTonalIconToggleButtonColors(
                                    containerColor = buttonBg,
                                    contentColor = SpotifyWhite,
                                    checkedContainerColor = buttonBg,
                                    checkedContentColor = animatedPrimary,
                                ),
                                modifier = Modifier.size(44.dp),
                            ) {
                                Icon(
                                    when (playback.repeatMode) { "track" -> Icons.Rounded.RepeatOne; else -> Icons.Rounded.Repeat },
                                    stringResource(R.string.repeat),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        Spacer(Modifier.weight(0.2f))

                        // Bottom bar: device button + stacked pills (left), share + queue (right)
                        val provider by vm.streamProvider.collectAsState()
                        val streaming by vm.isStreaming.collectAsState()
                        val audioOutput by vm.audioOutputName.collectAsState()
                        val audioType by vm.audioOutputType.collectAsState()
                        // Live audio output tracking
                        val audioCtx = LocalContext.current
                        DisposableEffect(Unit) {
                            val am = audioCtx.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
                            vm.updateAudioOutput(audioCtx)
                            val cb = object : android.media.AudioDeviceCallback() {
                                override fun onAudioDevicesAdded(added: Array<out android.media.AudioDeviceInfo>?) { vm.updateAudioOutput(audioCtx) }
                                override fun onAudioDevicesRemoved(removed: Array<out android.media.AudioDeviceInfo>?) { vm.updateAudioOutput(audioCtx) }
                            }
                            am.registerAudioDeviceCallback(cb, null)
                            onDispose { am.unregisterAudioDeviceCallback(cb) }
                        }
                        val audioIcon = when (audioType) {
                            "bluetooth" -> Icons.Rounded.Bluetooth
                            "wired" -> Icons.Rounded.Headphones
                            "usb" -> Icons.Rounded.Usb
                            else -> Icons.Rounded.Speaker
                        }
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                FilledTonalIconToggleButton(
                                    checked = streaming,
                                    onCheckedChange = { vm.loadDevices(); vm.showDevices.value = true },
                                    modifier = Modifier.size(38.dp),
                                    colors = IconButtonDefaults.filledTonalIconToggleButtonColors(
                                        containerColor = buttonBg,
                                        contentColor = SpotifyWhite,
                                        checkedContainerColor = animatedPrimary,
                                        checkedContentColor = SpotifyWhite,
                                    ),
                                ) {
                                    Icon(audioIcon, stringResource(R.string.audio_output), modifier = Modifier.size(20.dp))
                                }
                                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    audioOutput?.let { InfoPill(audioIcon, it) }
                                    provider?.let { SourcePill(it) }
                                }
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                FilledTonalIconButton(
                                    onClick = {
                                        track?.uri?.removePrefix("spotify:track:")?.let { id ->
                                            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                                type = "text/plain"
                                                putExtra(android.content.Intent.EXTRA_TEXT, "https://open.spotify.com/track/$id")
                                            }
                                            shareContext.startActivity(android.content.Intent.createChooser(intent, shareTrackLabel))
                                        }
                                    },
                                    modifier = Modifier.size(38.dp),
                                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                                        containerColor = buttonBg,
                                        contentColor = SpotifyWhite,
                                    ),
                                ) {
                                    Icon(Icons.Rounded.Share, stringResource(R.string.share), modifier = Modifier.size(20.dp))
                                }
                                FilledTonalIconButton(
                                    onClick = { vm.openQueue() },
                                    modifier = Modifier.size(38.dp),
                                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                                        containerColor = buttonBg,
                                        contentColor = SpotifyWhite,
                                    ),
                                ) {
                                    Icon(
                                        Icons.AutoMirrored.Rounded.QueueMusic,
                                        stringResource(R.string.queue),
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }

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

                    // Top bar: down arrow, "Now playing", three-dot menu
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
                                    contentColor = SpotifyWhite,
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
                                        color = SpotifyWhite,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            // EQ button — opens system equalizer
                            FilledTonalIconButton(
                                onClick = {
                                    try {
                                        val eqIntent = android.content.Intent(android.media.audiofx.AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL)
                                        eqIntent.putExtra(android.media.audiofx.AudioEffect.EXTRA_PACKAGE_NAME, shareContext.packageName)
                                        eqIntent.putExtra(android.media.audiofx.AudioEffect.EXTRA_CONTENT_TYPE, android.media.audiofx.AudioEffect.CONTENT_TYPE_MUSIC)
                                        shareContext.startActivity(eqIntent)
                                    } catch (_: Exception) {
                                        // No EQ app installed
                                    }
                                },
                                modifier = Modifier.size(44.dp),
                                colors = IconButtonDefaults.filledTonalIconButtonColors(
                                    containerColor = buttonBg,
                                    contentColor = SpotifyWhite,
                                ),
                            ) {
                                Icon(Icons.Rounded.Tune, stringResource(R.string.equalizer), modifier = Modifier.size(22.dp))
                            }
                    }

                    Spacer(Modifier.weight(0.3f))

                    if (hasCanvas) {
                        // Invisible placeholder — same size as album art to keep layout stable
                        Box(Modifier.fillMaxWidth().aspectRatio(1f))
                    } else {
                        // Album art — large with rounded corners
                        SpotifyImage(
                            url = displayArtUrl,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f),
                            shape = RoundedCornerShape(16.dp)
                        )
                    }

                    Spacer(Modifier.height(32.dp))

                    // Track info + like button
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f).padding(end = 12.dp)) {
                            MarqueeText(
                                text = displayTitle,
                                color = SpotifyWhite,
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.height(2.dp))
                            MarqueeText(
                                text = displayArtist,
                                color = secondaryText,
                                fontSize = 15.sp,
                                modifier = Modifier.clickable { vm.openArtistFromCurrentTrack() }
                            )
                            track?.albumName?.takeIf { !playback.isAd }?.let {
                                Spacer(Modifier.height(2.dp))
                                MarqueeText(
                                    text = it,
                                    color = tertiaryText,
                                    fontSize = 13.sp,
                                    modifier = Modifier.clickable { vm.openAlbumFromCurrentTrack() }
                                )
                            }
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val isLiked by vm.currentTrackLiked.collectAsState()
                            FilledIconToggleButton(
                                checked = isLiked,
                                onCheckedChange = { _ ->
                                    val id = track?.uri?.removePrefix("spotify:track:") ?: return@FilledIconToggleButton
                                    if (isLiked) vm.unlikeSong(id) else vm.likeSong(id)
                                },
                                modifier = Modifier.size(48.dp),
                                colors = IconButtonDefaults.filledIconToggleButtonColors(
                                    containerColor = buttonBg,
                                    contentColor = SpotifyWhite.copy(alpha = 0.7f),
                                    checkedContainerColor = buttonBg,
                                    checkedContentColor = animatedPrimary,
                                ),
                            ) {
                                Icon(
                                    if (isLiked) Icons.Rounded.Favorite else Icons.Filled.FavoriteBorder,
                                    stringResource(R.string.like),
                                    modifier = Modifier.size(28.dp),
                                )
                            }
                            NowPlayingMenu(
                                showMore = showMore,
                                onShowMore = { showMore = it },
                                onShowPlaylistPicker = { showPlaylistPicker = true },
                                vm = vm,
                                track = track,
                                shareContext = shareContext,
                                buttonBg = buttonBg
                            )
                        }
                    }

                    Spacer(Modifier.height(20.dp))

                    // Progress bar — thick rounded bar
                    var seekDragging by remember { mutableStateOf(false) }
                    var seekDragValue by remember { mutableFloatStateOf(0f) }
                    val sliderValue = if (seekDragging) seekDragValue
                        else if (playback.durationMs > 0) (playback.positionMs.toFloat() / playback.durationMs) else 0f
                    Slider(
                        value = sliderValue,
                        onValueChange = { seekDragging = true; seekDragValue = it },
                        onValueChangeFinished = {
                            vm.seekTo((seekDragValue * playback.durationMs).toLong())
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
                            inactiveTrackColor = SpotifyWhite.copy(alpha = 0.15f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(formatTime(playback.positionMs), color = secondaryText, fontSize = 12.sp)
                        Text(formatTime(playback.durationMs), color = secondaryText, fontSize = 12.sp)
                    }

                    Spacer(Modifier.weight(0.15f))

                    // Controls — with dark circular backgrounds
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Shuffle
                        FilledTonalIconToggleButton(
                            checked = playback.isShuffling,
                            onCheckedChange = { vm.toggleShuffle() },
                            colors = IconButtonDefaults.filledTonalIconToggleButtonColors(
                                containerColor = buttonBg,
                                contentColor = SpotifyWhite,
                                checkedContainerColor = buttonBg,
                                checkedContentColor = animatedPrimary,
                            ),
                            modifier = Modifier.size(52.dp),
                        ) {
                            Icon(Icons.Rounded.Shuffle, stringResource(R.string.shuffle), modifier = Modifier.size(22.dp))
                        }
                        // Previous
                        FilledTonalIconButton(
                            onClick = { vm.skipPrevious() },
                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = buttonBg,
                                contentColor = SpotifyWhite
                            ),
                            modifier = Modifier.size(56.dp),
                        ) {
                            Icon(Icons.Rounded.SkipPrevious, stringResource(R.string.previous), modifier = Modifier.size(32.dp))
                        }
                        // Play/Pause — large prominent button
                        FilledIconButton(
                            onClick = { if (!streamLoading) vm.togglePlayPause() },
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = if (streamLoading) animatedPrimary.copy(alpha = 0.5f) else animatedPrimary,
                                contentColor = SpotifyWhite,
                            ),
                            modifier = Modifier.size(72.dp),
                        ) {
                            if (streamLoading) {
                                LoadingIndicator(
                                    color = SpotifyWhite,
                                    modifier = Modifier.size(30.dp)
                                )
                            } else {
                                Icon(
                                    if (playback.isPaused || !playback.isPlaying) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                                    stringResource(R.string.play_pause), modifier = Modifier.size(38.dp)
                                )
                            }
                        }
                        // Next
                        val nextReady by vm.isNextReady.collectAsState()
                        val isCurrentlyStreaming by vm.isStreaming.collectAsState()
                        val nextLoading = !nextReady && isCurrentlyStreaming
                        FilledTonalIconButton(
                            onClick = { vm.skipNext() },
                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = buttonBg,
                                contentColor = SpotifyWhite
                            ),
                            modifier = Modifier.size(56.dp),
                        ) {
                            if (nextLoading) {
                                LoadingIndicator(color = SpotifyWhite, modifier = Modifier.size(22.dp))
                            } else {
                                Icon(Icons.Rounded.SkipNext, stringResource(R.string.next), modifier = Modifier.size(32.dp))
                            }
                        }
                        // Repeat
                        FilledTonalIconToggleButton(
                            checked = playback.repeatMode != "off",
                            onCheckedChange = { vm.cycleRepeat() },
                            colors = IconButtonDefaults.filledTonalIconToggleButtonColors(
                                containerColor = buttonBg,
                                contentColor = SpotifyWhite,
                                checkedContainerColor = buttonBg,
                                checkedContentColor = animatedPrimary,
                            ),
                            modifier = Modifier.size(52.dp),
                        ) {
                            Icon(
                                when (playback.repeatMode) { "track" -> Icons.Rounded.RepeatOne; else -> Icons.Rounded.Repeat },
                                stringResource(R.string.repeat),
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }

                    Spacer(Modifier.weight(0.2f))

                    // Bottom bar: device button + stacked pills (left), share + queue (right)
                    val provider by vm.streamProvider.collectAsState()
                    val streaming by vm.isStreaming.collectAsState()
                    val audioOutput by vm.audioOutputName.collectAsState()
                    val audioType by vm.audioOutputType.collectAsState()
                    val audioCtx2 = LocalContext.current
                    DisposableEffect(Unit) {
                        val am = audioCtx2.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
                        vm.updateAudioOutput(audioCtx2)
                        val cb = object : android.media.AudioDeviceCallback() {
                            override fun onAudioDevicesAdded(added: Array<out android.media.AudioDeviceInfo>?) { vm.updateAudioOutput(audioCtx2) }
                            override fun onAudioDevicesRemoved(removed: Array<out android.media.AudioDeviceInfo>?) { vm.updateAudioOutput(audioCtx2) }
                        }
                        am.registerAudioDeviceCallback(cb, null)
                        onDispose { am.unregisterAudioDeviceCallback(cb) }
                    }
                    val audioIcon = when (audioType) {
                        "bluetooth" -> Icons.Rounded.Bluetooth
                        "wired" -> Icons.Rounded.Headphones
                        "usb" -> Icons.Rounded.Usb
                        else -> Icons.Rounded.Speaker
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilledTonalIconToggleButton(
                                checked = streaming,
                                onCheckedChange = { vm.loadDevices(); vm.showDevices.value = true },
                                modifier = Modifier.size(44.dp),
                                colors = IconButtonDefaults.filledTonalIconToggleButtonColors(
                                    containerColor = buttonBg,
                                    contentColor = SpotifyWhite,
                                    checkedContainerColor = animatedPrimary,
                                    checkedContentColor = SpotifyWhite,
                                ),
                            ) {
                                Icon(audioIcon, stringResource(R.string.audio_output), modifier = Modifier.size(22.dp))
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                audioOutput?.let { InfoPill(audioIcon, it) }
                                provider?.let { SourcePill(it) }
                            }
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            FilledTonalIconButton(
                                onClick = {
                                    track?.uri?.removePrefix("spotify:track:")?.let { id ->
                                        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(android.content.Intent.EXTRA_TEXT, "https://open.spotify.com/track/$id")
                                        }
                                        shareContext.startActivity(android.content.Intent.createChooser(intent, shareTrackLabel))
                                    }
                                },
                                modifier = Modifier.size(44.dp),
                                colors = IconButtonDefaults.filledTonalIconButtonColors(
                                    containerColor = buttonBg,
                                    contentColor = SpotifyWhite,
                                ),
                            ) {
                                Icon(Icons.Rounded.Share, stringResource(R.string.share), modifier = Modifier.size(22.dp))
                            }
                            FilledTonalIconButton(
                                onClick = { vm.openQueue() },
                                modifier = Modifier.size(44.dp),
                                colors = IconButtonDefaults.filledTonalIconButtonColors(
                                    containerColor = buttonBg,
                                    contentColor = SpotifyWhite,
                                ),
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Rounded.QueueMusic,
                                    stringResource(R.string.queue),
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                }
            }
        }
    } // end Box

    // Playlist picker dialog
    if (showPlaylistPicker) {
        val libraryItems by vm.library.collectAsState()
        val playlists = libraryItems.filter { it.type == "playlist" }
        TightAlertDialog(
            onDismissRequest = { showPlaylistPicker = false },
            title = { Text(stringResource(R.string.add_to_playlist), color = SpotifyWhite) },
            containerColor = SpotifyGray,
            text = {
                // TightAlertDialog wraps `text` in a height-bounded verticalScroll Box, which
                // gives its child infinite max height — a LazyColumn there throws "infinity
                // maximum height". Use a plain Column; the dialog provides the scrolling.
                Column(Modifier.fillMaxWidth()) {
                    playlists.forEach { playlist ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    track?.uri?.let { uri ->
                                        val playlistId = playlist.uri.removePrefix("spotify:playlist:")
                                        vm.addTrackToPlaylist(playlistId, uri)
                                    }
                                    showPlaylistPicker = false
                                }
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            SpotifyImage(
                                url = playlist.imageUrl,
                                modifier = Modifier.size(44.dp),
                                shape = RoundedCornerShape(4.dp)
                            )
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(playlist.name, color = SpotifyWhite, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                playlist.owner?.let {
                                    Text(it, color = SpotifyLightGray, fontSize = 12.sp, maxLines = 1)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showPlaylistPicker = false }) {
                    Text(stringResource(R.string.cancel), color = SpotifyLightGray)
                }
            }
        )
    }
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
        Icon(icon, null, tint = SpotifyWhite, modifier = Modifier.size(9.dp))
        Text(text, color = SpotifyWhite, fontSize = 9.sp, maxLines = 1)
    }
}

@Composable
private fun SourcePill(provider: String) {
    val label = when (provider) {
        "qobuz" -> "Qobuz"
        "deezer" -> "Deezer"
        else -> provider.replaceFirstChar { it.uppercase() }
    }
    Row(
        Modifier
            .height(20.dp)
            .background(Color.White.copy(alpha = 0.10f), RoundedCornerShape(50))
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Icon(Icons.Rounded.MusicNote, null, tint = SpotifyWhite, modifier = Modifier.size(9.dp))
        Text(label, color = SpotifyWhite, fontSize = 9.sp, maxLines = 1)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NowPlayingMenu(
    showMore: Boolean,
    onShowMore: (Boolean) -> Unit,
    onShowPlaylistPicker: () -> Unit,
    vm: SpotifyViewModel,
    track: ch.snepilatch.app.data.TrackInfo?,
    shareContext: android.content.Context,
    buttonBg: Color
) {
    FilledTonalIconButton(
        onClick = { onShowMore(true) },
        modifier = Modifier.size(44.dp),
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = buttonBg,
            contentColor = SpotifyWhite,
        ),
    ) {
        Icon(Icons.Rounded.MoreVert, stringResource(R.string.more), modifier = Modifier.size(22.dp))
    }

    if (showMore) {
        val sheetState = rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        )
        ModalBottomSheet(
            onDismissRequest = { onShowMore(false) },
            sheetState = sheetState,
            containerColor = SpotifyElevated,
            dragHandle = {
                Box(
                    Modifier
                        .padding(vertical = 12.dp)
                        .width(40.dp)
                        .height(4.dp)
                        .background(SpotifyLightGray.copy(alpha = 0.4f), RoundedCornerShape(2.dp))
                )
            }
        ) {
            SheetNavBarFix()
            // Track header
            track?.let { t ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SpotifyImage(
                        url = t.albumArt,
                        modifier = Modifier.size(48.dp),
                        shape = RoundedCornerShape(8.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(t.name, color = SpotifyWhite, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(t.artist, color = SpotifyLightGray, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = SpotifyLightGray.copy(alpha = 0.15f))
            }

            val shareTrackLabel = stringResource(R.string.share_track_chooser)
            val lyricsLabel = stringResource(R.string.lyrics)
            val addQueueLabel = stringResource(R.string.add_to_queue)
            val addPlaylistLabel = stringResource(R.string.add_to_playlist)
            val viewQueueLabel = stringResource(R.string.view_queue)
            val visitAlbumLabel = stringResource(R.string.visit_album)
            val devicesLabel = stringResource(R.string.devices)
            val shareLabel = stringResource(R.string.share)
            val items = listOf(
                Triple(Icons.Rounded.MusicNote, lyricsLabel) {
                    onShowMore(false); vm.openLyrics()
                },
                Triple(Icons.AutoMirrored.Rounded.QueueMusic, addQueueLabel) {
                    track?.uri?.let { vm.addToQueue(it) }; onShowMore(false)
                },
                Triple(Icons.AutoMirrored.Rounded.PlaylistAdd, addPlaylistLabel) {
                    onShowMore(false); onShowPlaylistPicker()
                },
                Triple(Icons.AutoMirrored.Rounded.QueueMusic, viewQueueLabel) {
                    vm.openQueue(); onShowMore(false)
                },
                Triple(Icons.Rounded.Album, visitAlbumLabel) {
                    onShowMore(false); vm.openAlbumFromCurrentTrack()
                },
                Triple(Icons.Rounded.Devices, devicesLabel) {
                    onShowMore(false); vm.loadDevices(); vm.showDevices.value = true
                },
                Triple(Icons.Rounded.Share, shareLabel) {
                    onShowMore(false)
                    track?.uri?.removePrefix("spotify:track:")?.let { id ->
                        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(android.content.Intent.EXTRA_TEXT, "https://open.spotify.com/track/$id")
                        }
                        shareContext.startActivity(android.content.Intent.createChooser(intent, shareTrackLabel))
                    }
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
                    Icon(icon, null, tint = SpotifyWhite, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(16.dp))
                    Text(label, color = SpotifyWhite, fontSize = 15.sp)
                }
            }
            Spacer(Modifier.navigationBarsPadding().height(12.dp))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MarqueeText(
    text: String,
    color: androidx.compose.ui.graphics.Color,
    fontSize: androidx.compose.ui.unit.TextUnit,
    fontWeight: FontWeight? = null,
    modifier: Modifier = Modifier
) {
    Text(
        text = text,
        color = color,
        fontSize = fontSize,
        fontWeight = fontWeight,
        maxLines = 1,
        modifier = modifier.basicMarquee(
            iterations = Int.MAX_VALUE,
            velocity = 40.dp
        )
    )
}
