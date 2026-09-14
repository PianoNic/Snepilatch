@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package ch.snepilatch.app.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import ch.snepilatch.app.R
import ch.snepilatch.app.ui.shared.SpfyImage
import ch.snepilatch.app.ui.shared.rememberSmoothPosition
import ch.snepilatch.app.ui.theme.*
import ch.snepilatch.app.logic.shared.ThemeController
import ch.snepilatch.app.viewmodel.LyricsViewModel
import ch.snepilatch.app.logic.shared.AppSettings
import ch.snepilatch.app.viewmodel.PlaybackViewModel
import kotify.api.lyrics.LyricsData
import ch.snepilatch.app.ui.shared.LikeToggleButton

/** Spinner while loading, a note and a line when there are no lyrics, the plain or the synced view otherwise. */
@Composable
private fun LyricsBody(
    isLoading: Boolean,
    lyrics: LyricsData?,
    smoothPosition: MutableState<Long>,
    accent: Color,
    isLandscape: Boolean,
    lyricsAnimDirection: String,
    onSeek: (Long) -> Unit,
) {
    when {
        isLoading -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                LoadingIndicator(color = accent)
            }
        }
        lyrics == null || lyrics.lines.isEmpty() -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.MusicNote, null, tint = SnepilatchLightGray.copy(alpha = 0.5f), modifier = Modifier.size(48.dp))
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.lyrics_not_available), color = SnepilatchLightGray, fontSize = 16.sp)
                }
            }
        }
        lyrics.syncType == "UNSYNCED" -> UnsyncedLyricsView(lyrics, isLandscape)
        else -> SyncedLyricsView(lyrics, smoothPosition, isLandscape, lyricsAnimDirection, onSeek)
    }
}

@Composable
fun LyricsScreen(vm: PlaybackViewModel) {
    val lyricsVm: LyricsViewModel = viewModel()
    // Narrow projections — the lyrics scaffold must not recompose on the 2Hz position tick; position
    // is consumed only through the smoothPosition state below (mutated off the flow, not read here).
    val track by vm.currentTrack.collectAsState()
    val isPlayingRaw by vm.isPlayingFlow.collectAsState()
    val isPaused by vm.isPausedFlow.collectAsState()
    val repeatMode by vm.repeatModeFlow.collectAsState()
    val canToggleRepeat by vm.canToggleRepeatFlow.collectAsState()
    val theme by ThemeController.themeColors.collectAsState()
    val lyrics by lyricsVm.lyrics.collectAsState()
    val isLoading by lyricsVm.isLoading.collectAsState()
    val animatedPrimary by animateColorAsState(theme.primary, tween(800), label = "lyricsPrimary")
    val lyricsAnimDirection by AppSettings.lyricsAnimDirection.collectAsState()

    LaunchedEffect(track?.uri) {
        track?.let { lyricsVm.fetch(it) }
    }

    // Only synced lyrics need the per-frame clock; unsynced, loading and missing ones follow the coarse position.
    val syncedReveal = lyrics?.syncType == "LINE_SYNCED" || lyrics?.syncType == "SYLLABLE_SYNCED"
    val smoothPosition = rememberSmoothPosition(vm, active = isPlayingRaw && !isPaused && syncedReveal)

    Box(Modifier.fillMaxSize()) {
        track?.albumArt?.let { artUrl ->
            AsyncImage(
                model = coil.request.ImageRequest.Builder(LocalContext.current)
                    .data(artUrl).crossfade(800).build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().blur(100.dp)
            )
        }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)))

        BoxWithConstraints(Modifier.fillMaxSize()) {
            val isLandscape = maxWidth > maxHeight

            if (isLandscape) {
                Row(
                    Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .navigationBarsPadding()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        Modifier
                            .weight(0.4f)
                            .fillMaxHeight(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        SpfyImage(
                            url = track?.albumArt,
                            modifier = Modifier
                                .fillMaxHeight(0.55f)
                                .aspectRatio(1f),
                            shape = RoundedCornerShape(12.dp)
                        )
                        Spacer(Modifier.height(10.dp))

                        Text(
                            track?.name ?: "",
                            color = SnepilatchWhite,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1
                        )
                        Text(
                            track?.artist ?: "",
                            color = SnepilatchLightGray,
                            fontSize = 11.sp,
                            maxLines = 1
                        )
                        Spacer(Modifier.height(10.dp))

                        val streamLoading by vm.isStreamLoading.collectAsState()
                        val isLiked by vm.currentTrackLiked.collectAsState()
                        val buttonBg = Color.White.copy(alpha = 0.12f)
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                Modifier.size(36.dp).background(buttonBg, CircleShape).clip(CircleShape)
                                    .clickable(enabled = canToggleRepeat) { vm.cycleRepeat() },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    when (repeatMode) { "track" -> Icons.Rounded.RepeatOne; else -> Icons.Rounded.Repeat },
                                    stringResource(R.string.repeat),
                                    tint = if (repeatMode != "off") animatedPrimary else SnepilatchWhite.copy(alpha = 0.7f),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Box(
                                Modifier.size(36.dp).background(buttonBg, CircleShape).clip(CircleShape).clickable { vm.skipPrevious() },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Rounded.SkipPrevious, stringResource(R.string.previous), tint = SnepilatchWhite, modifier = Modifier.size(20.dp))
                            }
                            Box(
                                Modifier
                                    .size(44.dp)
                                    .background(if (streamLoading) animatedPrimary.copy(alpha = 0.5f) else animatedPrimary, CircleShape)
                                    .clip(CircleShape)
                                    .clickable { if (!streamLoading) vm.togglePlayPause() },
                                contentAlignment = Alignment.Center
                            ) {
                                if (streamLoading) {
                                    LoadingIndicator(color = SnepilatchWhite, modifier = Modifier.size(20.dp))
                                } else {
                                    Icon(
                                        if (isPaused || !isPlayingRaw) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                                        stringResource(R.string.play_pause), tint = SnepilatchWhite, modifier = Modifier.size(26.dp)
                                    )
                                }
                            }
                            Box(
                                Modifier.size(36.dp).background(buttonBg, CircleShape).clip(CircleShape).clickable { vm.skipNext() },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Rounded.SkipNext, stringResource(R.string.next), tint = SnepilatchWhite, modifier = Modifier.size(20.dp))
                            }
                            LikeToggleButton(isLiked, track?.uri, vm, buttonBg, animatedPrimary, size = 36.dp, iconSize = 18.dp)
                        }
                    }

                    Spacer(Modifier.width(12.dp))

                    Column(
                        Modifier
                            .weight(0.6f)
                            .fillMaxHeight()
                    ) {
                        LyricsBody(
                            isLoading, lyrics, smoothPosition, animatedPrimary,
                            isLandscape = true, lyricsAnimDirection = lyricsAnimDirection, onSeek = vm::seekTo,
                        )
                    }
                }
            } else {
                Column(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectVerticalDragGestures { _, dragAmount ->
                                if (dragAmount > 5) vm.goBack()
                            }
                        }
                        .statusBarsPadding()
                        .navigationBarsPadding()
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier.size(40.dp)
                                .background(Color.White.copy(alpha = 0.12f), CircleShape)
                                .clip(CircleShape)
                                .clickable { vm.goBack() },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Rounded.KeyboardArrowDown, stringResource(R.string.close), tint = SnepilatchWhite, modifier = Modifier.size(24.dp))
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(track?.name ?: "", color = SnepilatchWhite, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            Text(track?.artist ?: "", color = SnepilatchLightGray, fontSize = 12.sp, maxLines = 1)
                        }
                        Spacer(Modifier.size(40.dp))
                    }

                    LyricsBody(
                        isLoading, lyrics, smoothPosition, animatedPrimary,
                        isLandscape = false, lyricsAnimDirection = lyricsAnimDirection, onSeek = vm::seekTo,
                    )
                }
            }
        }
    }
}
