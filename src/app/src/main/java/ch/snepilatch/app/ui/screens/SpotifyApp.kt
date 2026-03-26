package ch.snepilatch.app.ui.screens

import ch.snepilatch.app.ui.theme.SpotifyWhite
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.material.icons.filled.CloudOff
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.snepilatch.app.data.Screen
import ch.snepilatch.app.ui.components.BottomNav
import ch.snepilatch.app.ui.components.DevicesDialog
import ch.snepilatch.app.ui.components.MiniPlayer
import ch.snepilatch.app.ui.components.SpotifyImage
import ch.snepilatch.app.ui.theme.SpotifyGray
import ch.snepilatch.app.ui.theme.SpotifyLightGray
import ch.snepilatch.app.viewmodel.SpotifyViewModel
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf

/** Dp height of the bottom overlay (MiniPlayer + BottomNav). Screens use this for bottom padding. */
val LocalBottomOverlayHeight = compositionLocalOf { mutableStateOf(0.dp) }

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

    LaunchedEffect(Unit) {
        vm.snackbarMessage.collect { message ->
            snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Short)
        }
    }

    BackHandler(screen != Screen.HOME) { vm.goBack() }

    CompositionLocalProvider(LocalBottomOverlayHeight provides bottomOverlayHeight) {
    Box(Modifier.fillMaxSize()) {
        // Main content — fills entire screen, hazeSource for blur
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

        // Bottom overlay: MiniPlayer + BottomNav float over content
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .onGloballyPositioned { coordinates ->
                    with(density) {
                        bottomOverlayHeight.value = coordinates.size.height.toDp()
                    }
                }
        ) {
            AnimatedVisibility(
                visible = playback.track != null && screen != Screen.NOW_PLAYING,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
            ) {
                MiniPlayer(vm)
            }
            AnimatedVisibility(
                visible = screen != Screen.NOW_PLAYING && screen != Screen.LYRICS,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
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

        // NowPlaying slides up as overlay
        AnimatedVisibility(
            visible = screen == Screen.NOW_PLAYING,
            enter = slideInVertically(
                initialOffsetY = { it },
                animationSpec = tween(200)
            ) + fadeIn(tween(150)),
            exit = slideOutVertically(
                targetOffsetY = { it },
                animationSpec = tween(180)
            ) + fadeOut(tween(120))
        ) {
            NowPlayingScreen(vm)
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
            AlertDialog(
                onDismissRequest = { vm.showPlaylistPicker.value = false },
                title = { Text("Add to Playlist", color = SpotifyWhite) },
                text = {
                    LazyColumn {
                        items(playlists.size) { i ->
                            val playlist = playlists[i]
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        val trackUri = vm.pendingPlaylistTrackUri.value ?: return@clickable
                                        vm.addTrackToPlaylist(playlist.uri.substringAfterLast(":"), trackUri)
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
                        Text("Cancel", color = SpotifyLightGray)
                    }
                }
            )
        }
    }
    } // CompositionLocalProvider
}

// --- Loading Screen ---

@Composable
fun LoadingScreen(error: String?, onLogin: () -> Unit = {}, onRetry: () -> Unit = {}) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (error != null) {
                var countdown by remember(error) { mutableIntStateOf(20) }
                LaunchedEffect(error) {
                    countdown = 20
                    while (countdown > 0) {
                        kotlinx.coroutines.delay(1000)
                        countdown--
                    }
                    onRetry()
                }

                Icon(Icons.Default.CloudOff, null, tint = SpotifyLightGray, modifier = Modifier.size(48.dp))
                Spacer(Modifier.height(16.dp))
                Text("Rate limited", color = SpotifyWhite, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Text("Retrying in ${countdown}s", color = SpotifyLightGray, fontSize = 14.sp)
                Spacer(Modifier.height(24.dp))
                LinearProgressIndicator(
                    progress = { (20 - countdown) / 20f },
                    modifier = Modifier
                        .width(200.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = SpotifyWhite,
                    trackColor = SpotifyLightGray.copy(alpha = 0.2f)
                )
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = onRetry,
                    colors = ButtonDefaults.buttonColors(containerColor = SpotifyWhite),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Text("Retry now", fontWeight = FontWeight.Bold, color = Color.Black)
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onLogin) {
                    Text("Login with different account", color = SpotifyLightGray, fontSize = 13.sp)
                }
            } else {
                CircularProgressIndicator(color = SpotifyWhite, strokeWidth = 3.dp)
                Spacer(Modifier.height(20.dp))
                Text("Connecting to Spotify...", color = SpotifyLightGray, fontSize = 15.sp)
            }
        }
    }
}
