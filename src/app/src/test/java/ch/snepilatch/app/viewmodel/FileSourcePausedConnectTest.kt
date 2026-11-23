package ch.snepilatch.app.viewmodel

import ch.snepilatch.app.logic.shared.AppSettings
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import kotify.api.playerstatus.DevicesInfo
import kotify.api.playerstatus.PlayerStateData
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * #1140: a file source keeps Connect paused on purpose, so the app drives the track. Every state push
 * then read as a pause, the play button flipped while the audio ran on, and the echo of a seek paused it.
 */
class FileSourcePausedConnectTest {

    private val rig = PlaybackTestRig()

    @Before
    fun setUp() {
        rig.install()
        rig.vm.isInitialized.value = true
        coEvery { rig.player.getDevices() } returns DevicesInfo(emptyMap(), null)
        rig.seedStreaming(positionMs = 10_000L)
        rig.vm.setForeignDeviceActiveForTest(false)
        every { rig.service.playWhenReady() } returns true
        every { rig.service.getCurrentPosition() } returns 33_000L
    }

    @After
    fun tearDown() {
        AppSettings.preferredAudioSource.value = null
        rig.uninstall()
    }

    private fun pushPausedState() {
        coEvery { rig.player.getState() } returns PlayerStateData(
            is_playing = true, is_paused = true, track = null, position_as_of_timestamp = 10_000, play_origin = null,
            is_active_device = true, has_active_device = true, active_device_id = "us", duration = 250_000,
        )
        rig.vm.resyncOnForeground()
        // While streaming the position is read from ExoPlayer: once it shows, the push was applied.
        runBlocking { withTimeout(2_000) { rig.vm.playback.first { it.positionMs == 33_000L } } }
    }

    @Test
    fun aPausedConnect_doesNotPauseAFileSourceThatPlays() {
        AppSettings.preferredAudioSource.value = "lossless"

        pushPausedState()

        assertTrue(rig.vm.playback.value.isPlaying)
        assertFalse(rig.vm.playback.value.isPaused)
    }

    @Test
    fun aPausedConnect_stillPausesSpfysOwnStream() {
        AppSettings.preferredAudioSource.value = null

        pushPausedState()

        assertTrue(rig.vm.playback.value.isPaused)
    }

    @Test
    fun aSeekOnAFileSource_isNotReportedToThePausedConnect() = runBlocking {
        AppSettings.preferredAudioSource.value = "lossless"

        rig.vm.seekTo(42_000L)
        delay(300)

        coVerify(exactly = 0) { rig.player.localSeek(any()) }
    }

    @Test
    fun aSeekOnSpfy_isStillReported() {
        AppSettings.preferredAudioSource.value = null

        rig.vm.seekTo(42_000L)

        coVerify(timeout = 1_000, exactly = 1) { rig.player.localSeek(42_000L) }
    }
}
