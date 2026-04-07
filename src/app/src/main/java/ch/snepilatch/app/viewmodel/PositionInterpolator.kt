package ch.snepilatch.app.viewmodel

import ch.snepilatch.app.data.PlaybackUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Ticks the UI playback position forward every 500ms, reading the authoritative
 * position from ExoPlayer when streaming locally, and periodically reports the
 * position back to Spotify Connect so other clients stay in sync.
 *
 * Extracted from SpotifyViewModel as a pure refactor — behavior is unchanged.
 */
class PositionInterpolator(
    private val scope: CoroutineScope,
    private val playback: MutableStateFlow<PlaybackUiState>,
    private val isStreaming: StateFlow<Boolean>,
    private val getExoPositionMs: () -> Long?,
    private val reportPosition: suspend (Long) -> Unit
) {
    private var job: Job? = null
    private var tickCount = 0

    fun start() {
        job?.cancel()
        tickCount = 0
        job = scope.launch {
            while (true) {
                delay(TICK_MS)
                val current = playback.value
                if (current.isPlaying && !current.isPaused && current.durationMs > 0) {
                    val newPos = if (isStreaming.value) {
                        getExoPositionMs() ?: (current.positionMs + TICK_MS)
                    } else {
                        current.positionMs + TICK_MS
                    }
                    playback.value = current.copy(positionMs = newPos.coerceAtMost(current.durationMs))

                    tickCount++
                    if (isStreaming.value && tickCount % REPORT_EVERY_N_TICKS == 0) {
                        launch(Dispatchers.IO) {
                            try { reportPosition(newPos) } catch (_: Exception) {}
                        }
                    }
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    companion object {
        private const val TICK_MS = 500L
        // Report every 30s (60 * 500ms)
        private const val REPORT_EVERY_N_TICKS = 60
    }
}
