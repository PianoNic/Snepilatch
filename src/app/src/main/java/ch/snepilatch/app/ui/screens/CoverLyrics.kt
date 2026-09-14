@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package ch.snepilatch.app.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import ch.snepilatch.app.R
import ch.snepilatch.app.logic.shared.AppSettings
import ch.snepilatch.app.logic.shared.ThemeController
import ch.snepilatch.app.ui.shared.rememberSmoothPosition
import ch.snepilatch.app.ui.theme.SnepilatchLightGray
import ch.snepilatch.app.ui.theme.SnepilatchWhite
import ch.snepilatch.app.viewmodel.LyricsViewModel
import ch.snepilatch.app.viewmodel.PlaybackViewModel
import kotlinx.coroutines.launch

/**
 * The back of the flipped cover (#817): the playing track's lyrics in the card over a scrim,
 * with a button in the top right corner to the full lyrics screen. Lines are not tappable
 * here; a tap on the card turns it back over.
 */
@Composable
fun CoverLyrics(vm: PlaybackViewModel, modifier: Modifier = Modifier) {
    val lyricsVm: LyricsViewModel = viewModel()
    val track by vm.currentTrack.collectAsState()
    val lyrics by lyricsVm.lyrics.collectAsState()
    val isLoading by lyricsVm.isLoading.collectAsState()
    val isPlayingRaw by vm.isPlayingFlow.collectAsState()
    val isPaused by vm.isPausedFlow.collectAsState()
    val theme by ThemeController.themeColors.collectAsState()
    val lyricsAnimDirection by AppSettings.lyricsAnimDirection.collectAsState()
    LaunchedEffect(track?.uri) {
        track?.let { lyricsVm.fetch(it) }
    }
    val synced = lyrics?.syncType == "LINE_SYNCED" || lyrics?.syncType == "SYLLABLE_SYNCED"
    val smoothPosition = rememberSmoothPosition(vm, active = isPlayingRaw && !isPaused && synced)

    // A sideways drag moves the lyrics with the finger like the cover strip (#829): let go early and
    // they spring back, drag far enough and they slide out of the card while the track skips. The
    // list inside claims vertical drags, so only horizontal ones reach here.
    val swipeThresholdPx = with(LocalDensity.current) { SWIPE_THRESHOLD_DP.dp.toPx() }
    val slide = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    // The player already paints the blurred art behind the card, so the back only needs a scrim
    // over it; a blurred image of its own cost a decode and a blur pass in the middle of the turn.
    Box(
        modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = BACKDROP_SCRIM))
            .pointerInput(swipeThresholdPx) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        val width = size.width.toFloat()
                        val travelled = slide.value
                        scope.launch {
                            when {
                                travelled > swipeThresholdPx -> {
                                    slide.animateTo(width, tween(SLIDE_OUT_MS))
                                    vm.skipPrevious(forceTrackChange = true)
                                    slide.snapTo(0f)
                                }
                                travelled < -swipeThresholdPx -> {
                                    slide.animateTo(-width, tween(SLIDE_OUT_MS))
                                    vm.skipNext()
                                    slide.snapTo(0f)
                                }
                                else -> slide.animateTo(0f, spring(Spring.DampingRatioLowBouncy, Spring.StiffnessMediumLow))
                            }
                        }
                    },
                    onDragCancel = { scope.launch { slide.animateTo(0f) } },
                    onHorizontalDrag = { change, amount ->
                        scope.launch { slide.snapTo(slide.value + amount) }
                        change.consume()
                    },
                )
            }
    ) {
        val current = lyrics
        Box(Modifier.fillMaxSize().graphicsLayer { translationX = slide.value }) {
            when {
                isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    LoadingIndicator(color = theme.primary)
                }
                current == null || current.lines.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.MusicNote, stringResource(R.string.lyrics_not_available), tint = SnepilatchLightGray, modifier = Modifier.size(40.dp))
                }
                !synced -> UnsyncedLyricsView(current, isLandscape = true)
                else -> SyncedLyricsView(current, smoothPosition, isLandscape = true, lyricsAnimDirection, onSeek = null, compact = true)
            }
        }
        IconButton(onClick = vm::openLyrics, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)) {
            Icon(Icons.Rounded.Fullscreen, stringResource(R.string.lyrics), tint = SnepilatchWhite)
        }
    }
}

private const val BACKDROP_SCRIM = 0.55f

/** How far a sideways drag has to travel to count as a swipe to the previous or next track. */
private const val SWIPE_THRESHOLD_DP = 72

/** How long the lyrics take to slide the rest of the way out of the card once a swipe commits. */
private const val SLIDE_OUT_MS = 200
