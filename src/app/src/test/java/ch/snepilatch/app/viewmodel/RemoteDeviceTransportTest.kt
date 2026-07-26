package ch.snepilatch.app.viewmodel

import io.mockk.coVerify
import io.mockk.excludeRecords
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Regression tests for issue #456: with another Connect device holding playback (e.g. the desktop
 * app), the transport buttons must control THAT device.
 *
 * Previously [PlaybackViewModel.togglePlayPause] only asked "is ExoPlayer loaded here?", so pause
 * sent a local state report describing this idle phone (which the remote device ignored) and play
 * fell through to the cold-start path, claiming the device and pulling the audio onto the phone.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RemoteDeviceTransportTest {

    private val rig = PlaybackTestRig()

    @Before
    fun setUp() {
        rig.install()
        rig.vm.setForeignDeviceActiveForTest(true)
        excludeRecords { rig.player.ourDeviceId() }
    }

    @After
    fun tearDown() {
        rig.uninstall()
    }

    /** The "pause does nothing" half of the bug. */
    @Test
    fun pauseWithForeignDeviceActive_sendsRemotePause() {
        rig.vm._playback.value = rig.vm._playback.value.copy(isPlaying = true, isPaused = false)

        rig.vm.togglePlayPause()
        runBlocking { rig.vm.awaitCommandForTest() }

        coVerify(exactly = 1) { rig.player.pause() }
        coVerify(exactly = 0) { rig.player.localPause(any()) }
        assertTrue("UI should flip to paused immediately", rig.vm._playback.value.isPaused)
    }

    /** The "play steals playback" half: resume must NOT claim the device. */
    @Test
    fun resumeWithForeignDeviceActive_sendsRemoteResumeAndDoesNotTransfer() {
        rig.vm._playback.value = rig.vm._playback.value.copy(isPlaying = false, isPaused = true)

        rig.vm.togglePlayPause()
        runBlocking { rig.vm.awaitCommandForTest() }

        coVerify(exactly = 1) { rig.player.resume() }
        coVerify(exactly = 0) { rig.player.transferPlaybackHere(any()) }
        coVerify(exactly = 0) { rig.player.localResume(any()) }
        assertTrue("UI should flip to playing immediately", rig.vm._playback.value.isPlaying)
        assertFalse(rig.vm._playback.value.isPaused)
    }

    /** No cold start means no spinner — the loading indicator was the visible symptom. */
    @Test
    fun resumeWithForeignDeviceActive_doesNotShowStreamLoading() {
        rig.vm._playback.value = rig.vm._playback.value.copy(isPlaying = false, isPaused = true)

        rig.vm.togglePlayPause()
        runBlocking { rig.vm.awaitCommandForTest() }

        assertFalse("remote resume must not enter the cold-start path", rig.vm.isStreamLoading.value)
    }

    /**
     * The phone being the active device is the normal case and must keep using the local state
     * reports — they're what makes transport uncapped.
     */
    @Test
    fun pauseWhenThisDeviceIsActive_stillUsesLocalStateReport() {
        rig.vm.setForeignDeviceActiveForTest(false)
        rig.seedStreaming(positionMs = 30_000L, isPaused = false)

        rig.vm.togglePlayPause()
        runBlocking { rig.vm.awaitCommandForTest() }

        coVerify(exactly = 1) { rig.player.localPause(any()) }
        coVerify(exactly = 0) { rig.player.pause() }
    }
}
