package ch.snepilatch.app.ui.shared

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.delay
import ch.snepilatch.app.viewmodel.PlaybackViewModel

/**
 * A playback position (ms) that advances at the display's native refresh rate instead of stepping
 * when the upstream [positionMs] StateFlow ticks.
 *
 * Each authoritative [positionMs] (and every play/pause flip) becomes an anchor; while playing, the
 * returned value advances from that anchor roughly every [PROGRESS_TICK_MS], read off the real frame
 * timestamp, so the progress bar glides with no visible steps. It stays honest because every upstream
 * tick re-anchors it to the true position (for local streaming that is ExoPlayer's own clock),
 * correcting any drift twice a second. Costs nothing when paused or off-screen — no frames are
 * requested.
 */
@Composable
fun rememberSmoothPositionMs(positionMs: Long, durationMs: Long, isPlaying: Boolean): State<Long> {
    // Returns the State rather than its value so the per-frame write only recomposes/redraws the
    // leaf that reads `.value` (ideally inside a draw-phase lambda), not the caller's whole body.
    val displayed = remember { mutableLongStateOf(positionMs) }
    LaunchedEffect(positionMs, isPlaying, durationMs) {
        if (!isPlaying) {
            displayed.longValue = positionMs
            return@LaunchedEffect
        }
        val cap = if (durationMs > 0) durationMs else Long.MAX_VALUE
        var anchorFrame = -1L
        while (true) {
            withFrameNanos { frame ->
                if (anchorFrame < 0) anchorFrame = frame
                displayed.longValue = (positionMs + (frame - anchorFrame) / 1_000_000L).coerceIn(0L, cap)
            }
            // Yield the display back between updates. withFrameNanos registers a Choreographer
            // callback, which schedules a frame — so looping it bare held the whole app at the panel's
            // full rate (~120fps, ~1 CPU core plus up to half of surfaceflinger) for as long as audio
            // played, on every screen. A progress bar advances about one pixel every 200ms, so 30
            // updates a second is already more than it can show. Same cadence P0-1 settled on for the
            // fluid background.
            delay(PROGRESS_TICK_MS)
        }
    }
    return displayed
}

/**
 * How often the smooth position advances while playing, in ms (~30fps). Not per-frame: see the loop
 * in [rememberSmoothPositionMs]. The value stays derived from the real frame timestamp, so throttling
 * the cadence costs accuracy nothing — only the number of frames requested.
 */
private const val PROGRESS_TICK_MS = 32L

// --- Track Row ---

/**
 * The lyric clock: the player's last reported position, advanced by the wall clock every frame
 * while [active]. Unlike [rememberSmoothPositionMs] it takes the position off the flow inside its
 * effect, so the composable that holds it never recomposes on the player's ticks, and it runs at
 * the full frame rate, which a karaoke fill needs and a progress bar does not.
 */
@Composable
fun rememberSmoothPosition(vm: PlaybackViewModel, active: Boolean): MutableState<Long> {
    val smoothPosition = remember { mutableLongStateOf(vm.positionFlow.value) }
    val lastKnownPos = remember { mutableLongStateOf(vm.positionFlow.value) }
    val lastKnownWallClock = remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        vm.positionFlow.collect { pos ->
            lastKnownPos.longValue = pos
            lastKnownWallClock.longValue = System.currentTimeMillis()
            smoothPosition.longValue = pos
        }
    }
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        while (true) {
            withFrameNanos { }
            val elapsed = System.currentTimeMillis() - lastKnownWallClock.longValue
            smoothPosition.longValue = (lastKnownPos.longValue + elapsed).coerceAtMost(vm.durationFlow.value.coerceAtLeast(1))
        }
    }
    return smoothPosition
}
