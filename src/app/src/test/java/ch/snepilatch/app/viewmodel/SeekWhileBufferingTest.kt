package ch.snepilatch.app.viewmodel

import ch.snepilatch.app.data.PlaybackUiState
import io.mockk.coEvery
import io.mockk.every
import kotify.api.playerstatus.DevicesInfo
import kotify.api.playerstatus.PlayerStateData
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Issue #702: a seek makes ExoPlayer re-buffer, and the cluster echo of that seek can land while it
 * still is. Buffering is not a pause, so the push must not flip the UI to paused and stall the ticker.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SeekWhileBufferingTest {

    private val rig = PlaybackTestRig()

    @Before
    fun setUp() {
        rig.install()
        rig.vm.isInitialized.value = true
        coEvery { rig.player.getDevices() } returns DevicesInfo(emptyMap(), null)
        coEvery { rig.player.getState() } returns PlayerStateData(
            is_playing = true, is_paused = false, track = null, position_as_of_timestamp = 100_000L,
            timestamp = 1_700_000_000_000L, duration = PUSHED_DURATION, play_origin = null,
            is_active_device = true, has_active_device = true, active_device_id = "phone",
        )
        every { rig.service.getCurrentPosition() } returns 100_000L
    }

    @After
    fun tearDown() = rig.uninstall()

    @Test
    fun pushWhileRebufferingAfterASeek_keepsPlaying() {
        rig.seedStreaming(positionMs = 100_000)
        every { rig.service.isPlaying() } returns false
        every { rig.service.playWhenReady() } returns true

        rig.vm.resyncOnForeground()

        val state = awaitPush()
        assertTrue(state.isPlaying)
        assertFalse(state.isPaused)
    }

    @Test
    fun pushWhileThePlayerIsActuallyPaused_showsPaused() {
        rig.seedStreaming(positionMs = 100_000)
        every { rig.service.isPlaying() } returns false
        every { rig.service.playWhenReady() } returns false

        rig.vm.resyncOnForeground()

        val state = awaitPush()
        assertFalse(state.isPlaying)
        assertTrue(state.isPaused)
    }

    private fun awaitPush(): PlaybackUiState =
        runBlocking { withTimeout(2_000) { rig.vm.playback.first { it.durationMs == PUSHED_DURATION } } }

    private companion object {
        const val PUSHED_DURATION = 180_000L
    }
}
