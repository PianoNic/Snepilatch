package ch.snepilatch.app.playback

import ch.snepilatch.app.data.PlaybackUiState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class PositionInterpolatorTest {

    /**
     * A redundant start() (as fired on every state push) must not cancel/relaunch the loop and reset
     * the tick counter. The only externally-visible effect of that counter is the 30s Connect position
     * report at tick 60, so drive 61 ticks with a redundant start() at tick 50 and assert it still fires.
     */
    @Test
    fun `redundant start preserves the 30s report counter`() = runTest {
        val reported = CountDownLatch(1)
        val playback = MutableStateFlow(PlaybackUiState(isPlaying = true, durationMs = 600_000L))
        val isStreaming = MutableStateFlow(true)
        val interp = PositionInterpolator(
            scope = backgroundScope,
            playback = playback,
            isStreaming = isStreaming,
            getExoPositionMs = { null },
            reportPosition = { reported.countDown() }
        )

        interp.start()
        advanceTimeBy(50 * 500L) // 50 ticks
        interp.start() // redundant — must be a no-op, NOT a counter reset
        advanceTimeBy(11 * 500L) // 11 more ticks -> 61 total
        advanceUntilIdle()

        // Report fires at tick 60. It is only reached by tick 61 if the redundant start() at tick 50
        // left the counter alone; a reset would have restarted it from 0.
        assertTrue("expected a Connect position report by tick 60", reported.await(2, TimeUnit.SECONDS))
    }
}
