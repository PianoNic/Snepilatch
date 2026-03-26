package ch.snepilatch.app.ui.components

import ch.snepilatch.app.ui.theme.SpotifyWhite
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.snepilatch.app.data.Screen
import ch.snepilatch.app.ui.theme.SpotifyElevated
import ch.snepilatch.app.ui.theme.SpotifyLightGray
import ch.snepilatch.app.viewmodel.SpotifyViewModel

@Composable
fun MiniPlayer(vm: SpotifyViewModel) {
    val playback by vm.playback.collectAsState()
    val track = playback.track ?: return
    val theme by vm.themeColors.collectAsState()
    val animatedPrimary by animateColorAsState(theme.primary, tween(800), label = "miniPrimary")
    val animatedCardBg by animateColorAsState(
        lerp(SpotifyElevated, theme.primary, 0.18f),
        tween(800), label = "miniCardBg"
    )
    val streamLoading by vm.isStreamLoading.collectAsState()

    Column(
        modifier = Modifier
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
            .pointerInput(Unit) {
                detectVerticalDragGestures { _, dragAmount ->
                    if (dragAmount < -30) vm.navigateTo(Screen.NOW_PLAYING)
                }
            }
            .clickable { vm.navigateTo(Screen.NOW_PLAYING) }
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 10.dp, end = 4.dp, top = 10.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SpotifyImage(
                url = track.albumArt,
                modifier = Modifier.size(44.dp),
                shape = RoundedCornerShape(8.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(track.name, color = SpotifyWhite, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(track.artist, color = SpotifyLightGray, fontSize = 12.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = { if (!streamLoading) vm.togglePlayPause() }, modifier = Modifier.size(40.dp)) {
                if (streamLoading) {
                    CircularProgressIndicator(
                        color = SpotifyWhite,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(20.dp)
                    )
                } else {
                    Icon(
                        if (playback.isPaused || !playback.isPlaying) Icons.Default.PlayArrow else Icons.Default.Pause,
                        "Play/Pause", tint = SpotifyWhite, modifier = Modifier.size(28.dp)
                    )
                }
            }
            IconButton(onClick = { vm.skipNext() }, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.SkipNext, "Next", tint = SpotifyWhite, modifier = Modifier.size(24.dp))
            }
        }
        if (playback.durationMs > 0) {
            LinearProgressIndicator(
                progress = { (playback.positionMs.toFloat() / playback.durationMs).coerceIn(0f, 1f) },
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
