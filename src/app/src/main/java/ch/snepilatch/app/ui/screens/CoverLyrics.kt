@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package ch.snepilatch.app.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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

    // The player already paints the blurred art behind the card, so the back only needs a scrim
    // over it; a blurred image of its own cost a decode and a blur pass in the middle of the turn.
    Box(modifier.fillMaxSize().background(Color.Black.copy(alpha = BACKDROP_SCRIM))) {
        val current = lyrics
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
        IconButton(onClick = vm::openLyrics, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)) {
            Icon(Icons.Rounded.Fullscreen, stringResource(R.string.lyrics), tint = SnepilatchWhite)
        }
    }
}

private const val BACKDROP_SCRIM = 0.55f
