@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package ch.snepilatch.app.ui.components

import ch.snepilatch.app.ui.theme.SpotifyWhite
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.snepilatch.app.R
import ch.snepilatch.app.ui.theme.SpotifyElevated
import ch.snepilatch.app.ui.theme.SpotifyLightGray
import ch.snepilatch.app.viewmodel.ThemeController
import ch.snepilatch.app.viewmodel.PlaybackViewModel

/**
 * Base fill colour of the mini-player card. Shared with the expanding-player morph
 * (PlayerMorph in SpotifyApp) so the hand-off from bar to growing card has no
 * colour seam — both must derive the fill from the same tint of the album colour.
 */
fun miniCardBaseColor(primary: Color): Color = lerp(SpotifyElevated, primary, 0.18f)

/**
 * The compact now-playing bar — a rounded card around [MiniPlayerContent].
 * Gesture-free: the parent supplies tap/drag via [modifier] so the same bar acts
 * as the collapsed anchor of the expanding player morph (see SpotifyApp).
 */
@Composable
fun MiniPlayer(
    vm: PlaybackViewModel,
    modifier: Modifier = Modifier
) {
    val theme by ThemeController.themeColors.collectAsState()
    val animatedCardBg by animateColorAsState(
        miniCardBaseColor(theme.primary),
        tween(800), label = "miniCardBg"
    )

    MiniPlayerContent(
        vm,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .shadow(
                elevation = 12.dp,
                shape = RoundedCornerShape(12.dp),
                ambientColor = animatedCardBg,
                spotColor = animatedCardBg
            )
            .clip(RoundedCornerShape(12.dp))
            .background(animatedCardBg)
    )
}

/**
 * The mini-player's inner content (artwork + title + transport + progress),
 * without the card chrome — so the expanding-player morph can render it inside
 * the growing card while it stretches to fullscreen.
 */
@Composable
fun MiniPlayerContent(
    vm: PlaybackViewModel,
    modifier: Modifier = Modifier
) {
    // Collect only the fields the mini bar draws, each distinctUntilChanged, instead of the whole
    // PlaybackUiState — otherwise the 2Hz positionMs rewrite recomposes this whole bar on every screen
    // twice a second. positionMs is read only inside MiniProgressBar below.
    val track by vm.currentTrack.collectAsState()
    val trackInfo = track ?: return
    val isPlaying by vm.isPlayingFlow.collectAsState()
    val isPaused by vm.isPausedFlow.collectAsState()
    val isAd by vm.isAdFlow.collectAsState()
    val durationMs by vm.durationFlow.collectAsState()
    // Ads are handled invisibly: keep the current song shown and let the play button spin (below) for
    // the skip, so it reads as loading the next track rather than "Skipping ad".
    val displayTitle = trackInfo.name
    val displayArtist = trackInfo.artist
    val displayArtUrl: String? = trackInfo.albumArt
    val theme by ThemeController.themeColors.collectAsState()
    val animatedPrimary by animateColorAsState(theme.primary, tween(800), label = "miniPrimary")
    val streamLoading by vm.isStreamLoading.collectAsState()
    val spinnerActive = streamLoading || isAd

    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 10.dp, end = 4.dp, top = 10.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SpotifyImage(
                url = displayArtUrl,
                modifier = Modifier.size(44.dp),
                shape = RoundedCornerShape(8.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(displayTitle, color = SpotifyWhite, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(displayArtist, color = SpotifyLightGray, fontSize = 12.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = { if (!spinnerActive) vm.togglePlayPause() }, modifier = Modifier.size(40.dp)) {
                if (spinnerActive) {
                    LoadingIndicator(
                        color = SpotifyWhite,
                        modifier = Modifier.size(20.dp)
                    )
                } else {
                    Icon(
                        if (isPaused || !isPlaying) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                        stringResource(R.string.play_pause), tint = SpotifyWhite, modifier = Modifier.size(28.dp)
                    )
                }
            }
            IconButton(onClick = { vm.skipNext() }, modifier = Modifier.size(40.dp)) {
                Icon(
                    Icons.Rounded.SkipNext,
                    stringResource(R.string.next),
                    tint = SpotifyWhite,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
        if (durationMs > 0) {
            MiniProgressBar(vm, durationMs, animatedPrimary)
        }
        Spacer(Modifier.height(6.dp))
    }
}

/**
 * The 2dp progress bar as its own leaf. It — and only it — collects the 2Hz positionFlow, so the
 * interpolator's position ticks recompose this tiny bar instead of the whole MiniPlayerContent body.
 */
@Composable
private fun MiniProgressBar(vm: PlaybackViewModel, durationMs: Long, color: Color) {
    val positionMs by vm.positionFlow.collectAsState()
    val isPlaying by vm.isPlayingFlow.collectAsState()
    val smoothPos = rememberSmoothPositionMs(positionMs, durationMs, isPlaying)
    LinearProgressIndicator(
        // Read .value inside the progress lambda (draw phase) so per-frame position updates
        // redraw only this 2dp bar, not this composable's body.
        progress = { (smoothPos.value.toFloat() / durationMs).coerceIn(0f, 1f) },
        modifier = Modifier
            .fillMaxWidth()
            .height(2.dp)
            .padding(horizontal = 10.dp),
        color = color,
        trackColor = SpotifyLightGray.copy(alpha = 0.2f),
    )
}
