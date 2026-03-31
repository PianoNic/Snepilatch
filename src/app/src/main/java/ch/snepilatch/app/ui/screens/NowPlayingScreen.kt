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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import android.graphics.SurfaceTexture
import android.net.Uri
import android.view.TextureView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import ch.snepilatch.app.ui.components.SpotifyImage
import ch.snepilatch.app.ui.theme.*
import ch.snepilatch.app.util.formatTime
import ch.snepilatch.app.viewmodel.SpotifyViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingScreen(vm: SpotifyViewModel) {
    val playback by vm.playback.collectAsState()
    val track = playback.track
    val streamLoading by vm.isStreamLoading.collectAsState()
    val theme by vm.themeColors.collectAsState()

    val animatedGradientTop by animateColorAsState(theme.gradientTop, tween(800), label = "gradTop")
    val animatedGradientBottom by animateColorAsState(theme.gradientBottom, tween(800), label = "gradBot")
    val animatedPrimary by animateColorAsState(theme.primary, tween(800), label = "primary")
    val buttonBg = Color.White.copy(alpha = 0.12f)

    var showMore by remember { mutableStateOf(false) }
    var showPlaylistPicker by remember { mutableStateOf(false) }
    val shareContext = LocalContext.current
    val canvasVideoUrl by vm.canvasUrl.collectAsState()
    val canvasOn by vm.canvasEnabled.collectAsState()
    val hasCanvas = canvasOn && canvasVideoUrl != null
    // Brighter text in canvas mode for readability over video
    val secondaryText = if (hasCanvas) SpotifyWhite.copy(alpha = 0.85f) else SpotifyLightGray
    val tertiaryText = if (hasCanvas) SpotifyWhite.copy(alpha = 0.65f) else SpotifyLightGray.copy(alpha = 0.7f)

    Box(Modifier.fillMaxSize()) {
        // Background layer (hazeSource for blur panels)
        Box(Modifier.fillMaxSize()) {
        // Canvas video background (looping, muted)
        if (canvasOn && canvasVideoUrl != null) {
            val context = LocalContext.current
            var canvasPlayer by remember { mutableStateOf<ExoPlayer?>(null) }

            var textureRef by remember { mutableStateOf<TextureView?>(null) }
            var surfaceReady by remember { mutableStateOf(false) }

            val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
            DisposableEffect(canvasVideoUrl, lifecycleOwner) {
                val player = ExoPlayer.Builder(context).build().apply {
                    setMediaItem(MediaItem.fromUri(Uri.parse(canvasVideoUrl!!)))
                    repeatMode = Player.REPEAT_MODE_ALL
                    volume = 0f
                    playWhenReady = true
                    addListener(object : Player.Listener {
                        override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                            val tv = textureRef ?: return
                            val viewW = tv.width.toFloat()
                            val viewH = tv.height.toFloat()
                            val videoW = videoSize.width.toFloat()
                            val videoH = videoSize.height.toFloat()
                            if (viewW == 0f || viewH == 0f || videoW == 0f || videoH == 0f) return
                            val scale = maxOf(viewW / videoW, viewH / videoH)
                            val scaledW = videoW * scale
                            val scaledH = videoH * scale
                            val matrix = android.graphics.Matrix()
                            matrix.setScale(scaledW / viewW, scaledH / viewH)
                            matrix.postTranslate((viewW - scaledW) / 2f, (viewH - scaledH) / 2f)
                            tv.setTransform(matrix)
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
                modifier = Modifier.fillMaxSize()
            )
        } else {
            // Blurred album art background — animated crossfade
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
                } else {
                    Box(Modifier.fillMaxSize().background(Color.Black))
                }
            }
        }
        // Dark overlay
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = if (hasCanvas) 0.35f else 0.45f))
        )
        } // end hazeSource Box

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
                            url = track?.albumArt,
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
                            Box(
                                Modifier
                                    .size(38.dp)
                                    .background(buttonBg, CircleShape)
                                    .clip(CircleShape)
                                    .clickable { vm.goBack() },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.KeyboardArrowDown, "Close", tint = SpotifyWhite, modifier = Modifier.size(24.dp))
                            }
                            val ctx by vm.playingContext.collectAsState()
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    ctx?.let { "Playing from ${it.type}" } ?: "Now playing",
                                    color = SpotifyLightGray,
                                    fontSize = 10.sp
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
                                    track?.name ?: "Not Playing",
                                    color = SpotifyWhite,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    track?.artist ?: "",
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
                            Box(
                                Modifier
                                    .size(40.dp)
                                    .background(buttonBg, CircleShape)
                                    .clip(CircleShape)
                                    .clickable {
                                        val id = track?.uri?.removePrefix("spotify:track:") ?: return@clickable
                                        if (isLiked) vm.unlikeSong(id) else vm.likeSong(id)
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    if (isLiked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                    "Like",
                                    tint = if (isLiked) animatedPrimary else SpotifyWhite.copy(alpha = 0.7f),
                                    modifier = Modifier.size(24.dp)
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
                            Box(
                                Modifier.size(44.dp).background(buttonBg, CircleShape).clip(CircleShape).clickable { vm.toggleShuffle() },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Shuffle, "Shuffle",
                                    tint = if (playback.isShuffling) animatedPrimary else SpotifyWhite,
                                    modifier = Modifier.size(20.dp))
                            }
                            Box(
                                Modifier.size(48.dp).background(buttonBg, CircleShape).clip(CircleShape).clickable { vm.skipPrevious() },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.SkipPrevious, "Previous", tint = SpotifyWhite, modifier = Modifier.size(28.dp))
                            }
                            Box(
                                Modifier
                                    .size(60.dp)
                                    .background(if (streamLoading) animatedPrimary.copy(alpha = 0.5f) else animatedPrimary, CircleShape)
                                    .clip(CircleShape)
                                    .clickable { if (!streamLoading) vm.togglePlayPause() },
                                contentAlignment = Alignment.Center
                            ) {
                                if (streamLoading) {
                                    CircularProgressIndicator(color = SpotifyWhite, strokeWidth = 3.dp, modifier = Modifier.size(26.dp))
                                } else {
                                    Icon(
                                        if (playback.isPaused || !playback.isPlaying) Icons.Default.PlayArrow else Icons.Default.Pause,
                                        "Play/Pause", tint = SpotifyWhite, modifier = Modifier.size(32.dp)
                                    )
                                }
                            }
                            val nextReady by vm.isNextReady.collectAsState()
                            val isCurrentlyStreaming by vm.isStreaming.collectAsState()
                            val nextLoading = !nextReady && isCurrentlyStreaming
                            Box(
                                Modifier.size(48.dp).background(buttonBg, CircleShape).clip(CircleShape).clickable { vm.skipNext() },
                                contentAlignment = Alignment.Center
                            ) {
                                if (nextLoading) {
                                    CircularProgressIndicator(color = SpotifyWhite, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                                } else {
                                    Icon(Icons.Default.SkipNext, "Next", tint = SpotifyWhite, modifier = Modifier.size(28.dp))
                                }
                            }
                            Box(
                                Modifier.size(44.dp).background(buttonBg, CircleShape).clip(CircleShape).clickable { vm.cycleRepeat() },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    when (playback.repeatMode) { "track" -> Icons.Default.RepeatOne; else -> Icons.Default.Repeat },
                                    "Repeat",
                                    tint = if (playback.repeatMode != "off") animatedPrimary else SpotifyWhite,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        Spacer(Modifier.weight(0.2f))

                        // Bottom bar: device button + stacked pills (left), share + queue (right)
                        val activeDevice by vm.activeDeviceName.collectAsState()
                        val provider by vm.streamProvider.collectAsState()
                        val streaming by vm.isStreaming.collectAsState()
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Box(
                                    Modifier
                                        .size(38.dp)
                                        .background(if (streaming) animatedPrimary else buttonBg, CircleShape)
                                        .clip(CircleShape)
                                        .clickable { vm.loadDevices(); vm.showDevices.value = true },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.Devices, "Devices", tint = SpotifyWhite, modifier = Modifier.size(20.dp))
                                }
                                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    activeDevice?.let { InfoPill(Icons.Default.Devices, it) }
                                    provider?.let { SourcePill(it) }
                                }
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Box(
                                    Modifier
                                        .size(38.dp)
                                        .background(buttonBg, CircleShape)
                                        .clip(CircleShape)
                                        .clickable {
                                            track?.uri?.removePrefix("spotify:track:")?.let { id ->
                                                val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                                    type = "text/plain"
                                                    putExtra(android.content.Intent.EXTRA_TEXT, "https://open.spotify.com/track/$id")
                                                }
                                                shareContext.startActivity(android.content.Intent.createChooser(intent, "Share track"))
                                            }
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.Share, "Share", tint = SpotifyWhite, modifier = Modifier.size(20.dp))
                                }
                                Box(
                                    Modifier
                                        .size(38.dp)
                                        .background(buttonBg, CircleShape)
                                        .clip(CircleShape)
                                        .clickable { vm.openQueue() },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.QueueMusic, "Queue", tint = SpotifyWhite, modifier = Modifier.size(20.dp))
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
                        .pointerInput(Unit) {
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
                            Box(
                                Modifier
                                    .size(44.dp)
                                    .background(buttonBg, CircleShape)
                                    .clip(CircleShape)
                                    .clickable { vm.goBack() },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.KeyboardArrowDown, "Close", tint = SpotifyWhite, modifier = Modifier.size(28.dp))
                            }
                            val ctx by vm.playingContext.collectAsState()
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.clickable(enabled = ctx?.uri != null) { vm.navigateToContext() }
                            ) {
                                Text(
                                    ctx?.let { "Playing from ${it.type}" } ?: "Now playing",
                                    color = secondaryText,
                                    fontSize = 11.sp
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
                            Box(
                                Modifier
                                    .size(44.dp)
                                    .background(buttonBg, CircleShape)
                                    .clip(CircleShape)
                                    .clickable {
                                        try {
                                            val eqIntent = android.content.Intent(android.media.audiofx.AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL)
                                            eqIntent.putExtra(android.media.audiofx.AudioEffect.EXTRA_PACKAGE_NAME, shareContext.packageName)
                                            eqIntent.putExtra(android.media.audiofx.AudioEffect.EXTRA_CONTENT_TYPE, android.media.audiofx.AudioEffect.CONTENT_TYPE_MUSIC)
                                            shareContext.startActivity(eqIntent)
                                        } catch (_: Exception) {
                                            // No EQ app installed
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Tune, "Equalizer", tint = SpotifyWhite, modifier = Modifier.size(22.dp))
                            }
                    }

                    Spacer(Modifier.weight(0.3f))

                    if (hasCanvas) {
                        // Invisible placeholder — same size as album art to keep layout stable
                        Box(Modifier.fillMaxWidth().aspectRatio(1f))
                    } else {
                        // Album art — large with rounded corners
                        SpotifyImage(
                            url = track?.albumArt,
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
                                text = track?.name ?: "Not Playing",
                                color = SpotifyWhite,
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.height(2.dp))
                            MarqueeText(
                                text = track?.artist ?: "",
                                color = secondaryText,
                                fontSize = 15.sp,
                                modifier = Modifier.clickable { vm.openArtistFromCurrentTrack() }
                            )
                            track?.albumName?.let {
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
                            Box(
                                Modifier
                                    .size(48.dp)
                                    .background(buttonBg, CircleShape)
                                    .clip(CircleShape)
                                    .clickable {
                                        val id = track?.uri?.removePrefix("spotify:track:") ?: return@clickable
                                        if (isLiked) vm.unlikeSong(id) else vm.likeSong(id)
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    if (isLiked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                    "Like",
                                    tint = if (isLiked) animatedPrimary else SpotifyWhite.copy(alpha = 0.7f),
                                    modifier = Modifier.size(28.dp)
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
                        Box(
                            Modifier
                                .size(52.dp)
                                .background(buttonBg, CircleShape)
                                .clip(CircleShape)
                                .clickable { vm.toggleShuffle() },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Shuffle, "Shuffle",
                                tint = if (playback.isShuffling) animatedPrimary else SpotifyWhite,
                                modifier = Modifier.size(22.dp))
                        }
                        // Previous
                        Box(
                            Modifier
                                .size(56.dp)
                                .background(buttonBg, CircleShape)
                                .clip(CircleShape)
                                .clickable { vm.skipPrevious() },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.SkipPrevious, "Previous", tint = SpotifyWhite, modifier = Modifier.size(32.dp))
                        }
                        // Play/Pause — large prominent button
                        Box(
                            Modifier
                                .size(72.dp)
                                .background(
                                    if (streamLoading) animatedPrimary.copy(alpha = 0.5f) else animatedPrimary,
                                    CircleShape
                                )
                                .clip(CircleShape)
                                .clickable { if (!streamLoading) vm.togglePlayPause() },
                            contentAlignment = Alignment.Center
                        ) {
                            if (streamLoading) {
                                CircularProgressIndicator(
                                    color = SpotifyWhite,
                                    strokeWidth = 3.dp,
                                    modifier = Modifier.size(30.dp)
                                )
                            } else {
                                Icon(
                                    if (playback.isPaused || !playback.isPlaying) Icons.Default.PlayArrow else Icons.Default.Pause,
                                    "Play/Pause", tint = SpotifyWhite, modifier = Modifier.size(38.dp)
                                )
                            }
                        }
                        // Next
                        val nextReady by vm.isNextReady.collectAsState()
                        val isCurrentlyStreaming by vm.isStreaming.collectAsState()
                        val nextLoading = !nextReady && isCurrentlyStreaming
                        Box(
                            Modifier
                                .size(56.dp)
                                .background(buttonBg, CircleShape)
                                .clip(CircleShape)
                                .clickable { vm.skipNext() },
                            contentAlignment = Alignment.Center
                        ) {
                            if (nextLoading) {
                                CircularProgressIndicator(color = SpotifyWhite, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                            } else {
                                Icon(Icons.Default.SkipNext, "Next", tint = SpotifyWhite, modifier = Modifier.size(32.dp))
                            }
                        }
                        // Repeat
                        Box(
                            Modifier
                                .size(52.dp)
                                .background(buttonBg, CircleShape)
                                .clip(CircleShape)
                                .clickable { vm.cycleRepeat() },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                when (playback.repeatMode) { "track" -> Icons.Default.RepeatOne; else -> Icons.Default.Repeat },
                                "Repeat",
                                tint = if (playback.repeatMode != "off") animatedPrimary else SpotifyWhite,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }

                    Spacer(Modifier.weight(0.2f))

                    // Bottom bar: device button + stacked pills (left), share + queue (right)
                    val activeDevice by vm.activeDeviceName.collectAsState()
                    val provider by vm.streamProvider.collectAsState()
                    val streaming by vm.isStreaming.collectAsState()
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                Modifier
                                    .size(44.dp)
                                    .background(if (streaming) animatedPrimary else buttonBg, CircleShape)
                                    .clip(CircleShape)
                                    .clickable { vm.loadDevices(); vm.showDevices.value = true },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Devices, "Devices", tint = SpotifyWhite, modifier = Modifier.size(22.dp))
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                activeDevice?.let { InfoPill(Icons.Default.Devices, it) }
                                provider?.let { SourcePill(it) }
                            }
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Box(
                                Modifier
                                    .size(44.dp)
                                    .background(buttonBg, CircleShape)
                                    .clip(CircleShape)
                                    .clickable {
                                        track?.uri?.removePrefix("spotify:track:")?.let { id ->
                                            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                                type = "text/plain"
                                                putExtra(android.content.Intent.EXTRA_TEXT, "https://open.spotify.com/track/$id")
                                            }
                                            shareContext.startActivity(android.content.Intent.createChooser(intent, "Share track"))
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Share, "Share", tint = SpotifyWhite, modifier = Modifier.size(22.dp))
                            }
                            Box(
                                Modifier
                                    .size(44.dp)
                                    .background(buttonBg, CircleShape)
                                    .clip(CircleShape)
                                    .clickable { vm.openQueue() },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.AutoMirrored.Filled.QueueMusic, "Queue", tint = SpotifyWhite, modifier = Modifier.size(22.dp))
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
        AlertDialog(
            onDismissRequest = { showPlaylistPicker = false },
            title = { Text("Add to Playlist", color = SpotifyWhite) },
            containerColor = SpotifyGray,
            text = {
                LazyColumn(Modifier.fillMaxWidth()) {
                    items(playlists) { playlist ->
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
                    Text("Cancel", color = SpotifyLightGray)
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
        "tidal" -> "Tidal"
        "qobuz" -> "Qobuz"
        "youtube" -> "YouTube Music"
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
        Icon(Icons.Default.MusicNote, null, tint = SpotifyWhite, modifier = Modifier.size(9.dp))
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
    Box(
        Modifier
            .size(44.dp)
            .background(buttonBg, CircleShape)
            .clip(CircleShape)
            .clickable { onShowMore(true) },
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Default.MoreVert, "More", tint = SpotifyWhite, modifier = Modifier.size(22.dp))
    }

    if (showMore) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
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

            val items = listOf(
                Triple(Icons.Default.MusicNote, "Lyrics") { onShowMore(false); vm.openLyrics() },
                Triple(Icons.AutoMirrored.Filled.QueueMusic, "Add to Queue") { track?.uri?.let { vm.addToQueue(it) }; onShowMore(false) },
                Triple(Icons.AutoMirrored.Filled.PlaylistAdd, "Add to Playlist") { onShowMore(false); onShowPlaylistPicker() },
                Triple(Icons.AutoMirrored.Filled.QueueMusic, "View Queue") { vm.openQueue(); onShowMore(false) },
                Triple(Icons.Default.Album, "Visit Album") { onShowMore(false); vm.openAlbumFromCurrentTrack() },
                Triple(Icons.Default.Devices, "Devices") { onShowMore(false); vm.loadDevices(); vm.showDevices.value = true },
                Triple(Icons.Default.Share, "Share") {
                    onShowMore(false)
                    track?.uri?.removePrefix("spotify:track:")?.let { id ->
                        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(android.content.Intent.EXTRA_TEXT, "https://open.spotify.com/track/$id")
                        }
                        shareContext.startActivity(android.content.Intent.createChooser(intent, "Share track"))
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
