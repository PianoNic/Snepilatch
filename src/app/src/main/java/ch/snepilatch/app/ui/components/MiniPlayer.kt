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
import ch.snepilatch.app.viewmodel.SpotifyViewModel

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
    vm: SpotifyViewModel,
    modifier: Modifier = Modifier
) {
    val theme by vm.themeColors.collectAsState()
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
    vm: SpotifyViewModel,
    modifier: Modifier = Modifier
) {
    val playback by vm.playback.collectAsState()
    val track = playback.track ?: return
    // While skipping an ad, show a "Skipping ad…" placeholder instead of the lingering track.
    val displayTitle = if (playback.isAd) stringResource(R.string.now_playing_skipping_ad) else track.name
    val displayArtist = if (playback.isAd) "" else track.artist
    val displayArtUrl: String? = if (playback.isAd) null else track.albumArt
    val theme by vm.themeColors.collectAsState()
    val animatedPrimary by animateColorAsState(theme.primary, tween(800), label = "miniPrimary")
    val streamLoading by vm.isStreamLoading.collectAsState()

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
            IconButton(onClick = { if (!streamLoading) vm.togglePlayPause() }, modifier = Modifier.size(40.dp)) {
                if (streamLoading) {
                    LoadingIndicator(
                        color = SpotifyWhite,
                        modifier = Modifier.size(20.dp)
                    )
                } else {
                    Icon(
                        if (playback.isPaused || !playback.isPlaying) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
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
        if (playback.durationMs > 0) {
            val smoothPos = rememberSmoothPositionMs(playback.positionMs, playback.durationMs, playback.isPlaying)
            LinearProgressIndicator(
                progress = { (smoothPos.toFloat() / playback.durationMs).coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .padding(horizontal = 10.dp),
                color = animatedPrimary,
                trackColor = SpotifyLightGray.copy(alpha = 0.2f),
            )
        }
        Spacer(Modifier.height(6.dp))
    }
}
