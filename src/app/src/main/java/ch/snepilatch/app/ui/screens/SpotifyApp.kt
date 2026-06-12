package ch.snepilatch.app.ui.screens

import ch.snepilatch.app.ui.theme.SpotifyWhite
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.ui.draw.shadow
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.snepilatch.app.R
import ch.snepilatch.app.data.Screen
import ch.snepilatch.app.ui.components.BottomNav
import ch.snepilatch.app.ui.components.DevicesDialog
import ch.snepilatch.app.ui.components.MiniPlayer
import ch.snepilatch.app.ui.components.MiniPlayerContent
import ch.snepilatch.app.ui.components.miniCardBaseColor
import ch.snepilatch.app.ui.components.SpotifyImage
import ch.snepilatch.app.ui.components.TightAlertDialog
import ch.snepilatch.app.ui.theme.SpotifyGray
import ch.snepilatch.app.ui.theme.SpotifyLightGray
import ch.snepilatch.app.viewmodel.SpotifyViewModel
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.util.lerp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Dp height of the bottom overlay (MiniPlayer + BottomNav). Screens use this for bottom padding. */
val LocalBottomOverlayHeight = compositionLocalOf { mutableStateOf(0.dp) }

/**
 * The mini-player card's padding — must mirror MiniPlayer's
 * `padding(horizontal = 12.dp, vertical = 6.dp)`. The morph insets the bar bounds
 * by this to find the visible card rect, and the drag maps finger travel to the
 * card's top (`miniBounds.top + [MorphCardPadV]`), so all three uses must agree.
 */
private val MorphCardPadH = 12.dp
private val MorphCardPadV = 6.dp

// --- App Shell ---

@Composable
fun SpotifyApp(vm: SpotifyViewModel) {
    val screen by vm.currentScreen.collectAsState()
    val playback by vm.playback.collectAsState()
    val showDevices by vm.showDevices.collectAsState()
    val hazeState = remember { HazeState() }
    val snackbarHostState = remember { SnackbarHostState() }
    val bottomOverlayHeight = remember { mutableStateOf(0.dp) }
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    val hasTrack = playback.track != null
    // 0f = mini bar, 1f = full player. Single source of truth for the morph.
    val expand = remember { Animatable(0f) }
    // The full player counts as "open" for NOW_PLAYING and for LYRICS (which
    // overlays it), so toggling lyrics doesn't collapse + re-expand it behind.
    val expandedTarget = hasTrack && (screen == Screen.NOW_PLAYING || screen == Screen.LYRICS)
    // Settle the spring whenever the committed screen changes (tap, back, queue,
    // lyrics). Interactive drags snapTo directly, then commit a screen change so
    // this effect finishes to the same target.
    LaunchedEffect(expandedTarget) {
        expand.animateTo(
            if (expandedTarget) 1f else 0f,
            animationSpec = spring(dampingRatio = 0.9f, stiffness = 380f)
        )
    }
    // Keep a real content screen rendered behind the player, so collapsing reveals
    // it instead of a blank. NOW_PLAYING/LYRICS are overlays, not content screens.
    var contentScreen by remember { mutableStateOf(Screen.HOME) }
    LaunchedEffect(screen) {
        if (screen != Screen.NOW_PLAYING && screen != Screen.LYRICS) contentScreen = screen
    }

    LaunchedEffect(Unit) {
        vm.snackbarMessage.collect { message ->
            snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Short)
        }
    }

    BackHandler(screen != Screen.HOME) { vm.goBack() }

    CompositionLocalProvider(LocalBottomOverlayHeight provides bottomOverlayHeight) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val fullWidthPx = constraints.maxWidth
        val fullHeightPx = constraints.maxHeight

        // Main content — always a real screen, even while the player is open, so
        // the morph reveals it underneath rather than a blank.
        MainContent(contentScreen, vm, hazeState)

        // Mini bar + BottomNav. The bar's bounds anchor the growing card, and the
        // bar hands off to the card the instant a drag starts.
        var miniBounds by remember { mutableStateOf<Rect?>(null) }
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .onGloballyPositioned { coordinates ->
                    with(density) {
                        bottomOverlayHeight.value = coordinates.size.height.toDp()
                    }
                }
        ) {
            if (hasTrack) {
                MiniPlayer(
                    vm,
                    modifier = Modifier
                        .onGloballyPositioned { miniBounds = it.boundsInRoot() }
                        // Hand off to the growing card the instant a drag starts;
                        // the card renders the same bar content, so the cut is unseen.
                        .graphicsLayer { alpha = if (expand.value < 0.002f) 1f else 0f }
                        .pointerInput(Unit) {
                            detectTapGestures { vm.navigateTo(Screen.NOW_PLAYING) }
                        }
                        .pointerInput(Unit) {
                            var opened = false
                            // Rolling ~60ms window of (time, y) samples → an honest release
                            // velocity that's robust to 120Hz batching (a single-sample
                            // velocity inflates on near-zero dt and falsely "flings" a slow
                            // drag). We always scrub during the drag and only decide on
                            // release, so a slow drag tracks the finger and a flick opens.
                            val times = ArrayDeque<Long>()
                            val ys = ArrayDeque<Float>()
                            detectVerticalDragGestures(
                                onDragStart = { opened = false; times.clear(); ys.clear() },
                                onDragEnd = {
                                    if (!opened) {
                                        val vel = if (times.size >= 2 && times.last() > times.first()) {
                                            (ys.last() - ys.first()) / (times.last() - times.first())
                                        } else {
                                            0f
                                        }
                                        // Flick up (≥ ~700 px/s) or dragged past ~14% opens;
                                        // else spring back.
                                        if (vel < -0.7f || expand.value > 0.14f) {
                                            opened = true
                                            vm.navigateTo(Screen.NOW_PLAYING)
                                        } else {
                                            scope.launch { expand.animateTo(0f, spring(stiffness = 380f)) }
                                        }
                                    }
                                }
                            ) { change, dragAmount ->
                                val t = change.uptimeMillis
                                times.addLast(t)
                                ys.addLast(change.position.y)
                                while (times.size > 1 && t - times.first() > 60L) {
                                    times.removeFirst()
                                    ys.removeFirst()
                                }
                                // Always track the finger; the open decision is on release.
                                val span = (miniBounds?.top ?: fullHeightPx.toFloat()) +
                                    with(density) { MorphCardPadV.toPx() }
                                val next = (expand.value - dragAmount / span).coerceIn(0f, 1f)
                                scope.launch { expand.snapTo(next) }
                            }
                        }
                )
            }
            Box(
                Modifier.graphicsLayer {
                    alpha = (1f - expand.value * 1.5f).coerceIn(0f, 1f)
                    translationY = expand.value * size.height
                }
            ) {
                BottomNav(screen, vm, hazeState)
            }
        }

        // Snackbar
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 140.dp)
        ) { data ->
            Snackbar(
                snackbarData = data,
                containerColor = SpotifyGray,
                contentColor = SpotifyWhite,
                shape = RoundedCornerShape(8.dp)
            )
        }

        // Expanding player: lerps from the mini bar's bounds to fullscreen,
        // crossfading the full NowPlaying content in (replaces the old slide-up).
        if (hasTrack) {
            miniBounds?.let { mb ->
            PlayerMorph(
                vm, expand, mb, IntSize(fullWidthPx, fullHeightPx),
                onCollapseDrag = { delta ->
                    // Same 1:1 mapping as the open drag: the card's top travels from
                    // the bar to the screen top, so divide by that span, not the screen.
                    val span = mb.top + with(density) { MorphCardPadV.toPx() }
                    scope.launch {
                        expand.snapTo((expand.value - delta / span).coerceIn(0f, 1f))
                    }
                },
                onCollapseEnd = { vy ->
                    // Fling down (or dragged past a third closed) collapses; else springs open.
                    val close = vy > 700f || (vy >= -700f && expand.value < 0.6f)
                    if (close) {
                        vm.goBack()
                    } else {
                        scope.launch { expand.animateTo(1f, spring(stiffness = 380f)) }
                    }
                }
            )
            }
        }
        // Lyrics slides up as overlay
        AnimatedVisibility(
            visible = screen == Screen.LYRICS,
            enter = slideInVertically(
                initialOffsetY = { it },
                animationSpec = tween(200)
            ) + fadeIn(tween(150)),
            exit = slideOutVertically(
                targetOffsetY = { it },
                animationSpec = tween(180)
            ) + fadeOut(tween(120))
        ) {
            LyricsScreen(vm)
        }

        if (showDevices) DevicesDialog(vm)

        // Global playlist picker (triggered from TrackRow menu)
        val showPicker by vm.showPlaylistPicker.collectAsState()
        if (showPicker) {
            val library by vm.library.collectAsState()
            val playlists = library.filter { it.type == "playlist" }
            TightAlertDialog(
                onDismissRequest = { vm.showPlaylistPicker.value = false },
                title = { Text(stringResource(R.string.add_to_playlist), color = SpotifyWhite) },
                text = {
                    LazyColumn {
                        items(playlists.size) { i ->
                            val playlist = playlists[i]
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        val trackUris = vm.pendingPlaylistTrackUris.value
                                        if (trackUris.isEmpty()) return@clickable
                                        vm.addTracksToPlaylist(playlist.uri.substringAfterLast(":"), trackUris)
                                        vm.showPlaylistPicker.value = false
                                    }
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                SpotifyImage(
                                    url = playlist.imageUrl,
                                    modifier = Modifier.size(44.dp),
                                    shape = RoundedCornerShape(4.dp)
                                )
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text(playlist.name, color = SpotifyWhite, fontSize = 14.sp, maxLines = 1)
                                    playlist.owner?.let { Text(it, color = SpotifyLightGray, fontSize = 12.sp, maxLines = 1) }
                                }
                            }
                        }
                    }
                },
                containerColor = SpotifyGray,
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { vm.showPlaylistPicker.value = false }) {
                        Text(stringResource(R.string.cancel), color = SpotifyLightGray)
                    }
                }
            )
        }
    }
    } // CompositionLocalProvider
}

@Composable
private fun MainContent(screen: Screen, vm: SpotifyViewModel, hazeState: HazeState) {
    Box(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .hazeSource(hazeState)
    ) {
        when (screen) {
            Screen.HOME -> HomeScreen(vm)
            Screen.SEARCH -> SearchScreen(vm)
            Screen.LIBRARY -> { LaunchedEffect(Unit) { vm.loadLibrary() }; LibraryScreen(vm) }
            Screen.ACCOUNT -> AccountScreen(vm)
            Screen.QUEUE -> QueueScreen(vm)
            Screen.PLAYLIST_DETAIL, Screen.ALBUM_DETAIL, Screen.ARTIST_DETAIL -> DetailScreen(vm)
            Screen.NOW_PLAYING, Screen.LYRICS, Screen.LOGIN -> {}
        }
    }
}

/**
 * The expanding player: a card that grows from the mini bar ([miniBounds]) to the
 * full screen, with the full [NowPlayingScreen] fading in inside it as it grows.
 * Reads [expand] internally so only this subtree recomposes per frame.
 */
@Composable
private fun PlayerMorph(
    vm: SpotifyViewModel,
    expand: Animatable<Float, *>,
    miniBounds: Rect,
    fullSize: IntSize,
    onCollapseDrag: (Float) -> Unit,
    onCollapseEnd: (Float) -> Unit,
) {
    val f = expand.value
    if (f <= 0.001f) return
    val density = LocalDensity.current
    val theme by vm.themeColors.collectAsState()
    val cardBg by animateColorAsState(
        miniCardBaseColor(theme.primary),
        tween(800), label = "morphCardBg"
    )
    // The card's fill crossfades from the solid mini-card colour into the live
    // player background (Canvas video or blurred art) as the card grows — so the
    // bar's background "becomes" the player's background in place, anchored to the
    // card (never the screen).
    val bgFade = (f / 0.7f).coerceIn(0f, 1f)

    // Start from the *visible* mini card (miniBounds is the padded outer node, so
    // inset by the card padding) and grow that rect to the full screen.
    val padH = with(density) { MorphCardPadH.toPx() }
    val padV = with(density) { MorphCardPadV.toPx() }
    val fullW = with(density) { fullSize.width.toDp() }
    val fullH = with(density) { fullSize.height.toDp() }
    val startW = (miniBounds.width - padH * 2).coerceAtLeast(1f)
    val startH = (miniBounds.height - padV * 2).coerceAtLeast(1f)
    val x = lerp(miniBounds.left + padH, 0f, f)
    val y = lerp(miniBounds.top + padV, 0f, f)
    val w = lerp(startW, fullSize.width.toFloat(), f)
    val h = lerp(startH, fullSize.height.toFloat(), f)
    // Keep the card fully rounded for the first part of the swipe, then sharpen
    // only over the last stretch — otherwise the corners go square too early.
    val cornerFrac = ((f - 0.6f) / 0.4f).coerceIn(0f, 1f)
    val corner = androidx.compose.ui.unit.lerp(12.dp, 0.dp, cornerFrac)
    val elevation = androidx.compose.ui.unit.lerp(12.dp, 0.dp, f)
    val fullAlpha = ((f - 0.1f) / 0.45f).coerceIn(0f, 1f)
    val miniAlpha = (1f - f * 3f).coerceIn(0f, 1f)

    // The card itself — grows from the bar to fullscreen keeping its background,
    // shadow and rounded corners, so the whole card visibly enlarges.
    Box(
        Modifier
            .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
            .size(with(density) { w.toDp() }, with(density) { h.toDp() })
            .shadow(elevation, RoundedCornerShape(corner), ambientColor = cardBg, spotColor = cardBg)
            .clip(RoundedCornerShape(corner))
            .background(cardBg)
    ) {
        // Background at a STATIC full-screen size, anchored to the card's own
        // top-left (no screen offset) so it travels WITH the card/mini-bar as it
        // grows, and clipped by the card. Because its size never changes, the
        // Canvas video never resizes — it can't stretch and needs no per-frame
        // sizing. It just crossfades in over the solid cardBg as the card grows.
        if (bgFade > 0.001f) {
            Box(
                Modifier
                    .requiredSize(fullW, fullH)
                    .graphicsLayer { alpha = bgFade }
            ) {
                PlayerBackground(vm, Modifier.fillMaxSize())
            }
        }
        // Full player pinned to the real screen rect, clipped by the growing card;
        // fades in as the card grows. It draws no background of its own — the
        // card-anchored PlayerBackground above is the only background.
        Box(
            Modifier
                .requiredSize(fullW, fullH)
                .offset { IntOffset(-x.roundToInt(), -y.roundToInt()) }
                .graphicsLayer { alpha = fullAlpha }
        ) {
            NowPlayingScreen(
                vm,
                drawBackground = false,
                onMorphDrag = onCollapseDrag,
                onMorphDragEnd = onCollapseEnd
            )
        }
        // The bar content at the card's top, fading as the card grows.
        if (miniAlpha > 0.001f) {
            MiniPlayerContent(
                vm,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .width(with(density) { startW.toDp() })
                    .graphicsLayer { alpha = miniAlpha }
            )
        }
    }
}

// --- Loading Screen ---

@Composable
fun LoadingScreen(
    error: String?,
    isRateLimited: Boolean = false,
    cooldownSecondsRemaining: Int = 0,
    onLogin: () -> Unit = {},
    onRetry: () -> Unit = {}
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            when {
                isRateLimited -> {
                    // The cooldown total can vary (20s on first retry, 40s on
                    // second, etc. — see SpotifyViewModel). Track the highest
                    // value we've seen so the progress bar fills correctly
                    // regardless of the starting length.
                    var totalSeconds by remember { mutableIntStateOf(cooldownSecondsRemaining) }
                    if (cooldownSecondsRemaining > totalSeconds) totalSeconds = cooldownSecondsRemaining
                    val safeTotal = totalSeconds.coerceAtLeast(1)
                    val progress = ((safeTotal - cooldownSecondsRemaining).toFloat() / safeTotal)
                        .coerceIn(0f, 1f)

                    Icon(Icons.Rounded.CloudOff, null, tint = SpotifyLightGray, modifier = Modifier.size(48.dp))
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(R.string.rate_limited), color = SpotifyWhite, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.retrying_in, cooldownSecondsRemaining), color = SpotifyLightGray, fontSize = 14.sp)
                    Spacer(Modifier.height(24.dp))
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .width(200.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color = SpotifyWhite,
                        trackColor = SpotifyLightGray.copy(alpha = 0.2f)
                    )
                    Spacer(Modifier.height(24.dp))
                    TextButton(onClick = onLogin) {
                        Text(stringResource(R.string.login_different_account), color = SpotifyLightGray, fontSize = 13.sp)
                    }
                }
                error != null -> {
                    Icon(Icons.Rounded.CloudOff, null, tint = SpotifyLightGray, modifier = Modifier.size(48.dp))
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(R.string.connection_failed), color = SpotifyWhite, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Text(error, color = SpotifyLightGray, fontSize = 13.sp)
                    Spacer(Modifier.height(24.dp))
                    Button(
                        onClick = onRetry,
                        colors = ButtonDefaults.buttonColors(containerColor = SpotifyWhite),
                        shape = RoundedCornerShape(24.dp)
                    ) {
                        Text(stringResource(R.string.retry), fontWeight = FontWeight.Bold, color = Color.Black)
                    }
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = onLogin) {
                        Text(stringResource(R.string.login_different_account), color = SpotifyLightGray, fontSize = 13.sp)
                    }
                }
                else -> {
                    CircularWavyProgressIndicator(color = SpotifyWhite)
                    Spacer(Modifier.height(20.dp))
                    Text(stringResource(R.string.connecting_to_spotify), color = SpotifyLightGray, fontSize = 15.sp)
                }
            }
        }
    }
}
