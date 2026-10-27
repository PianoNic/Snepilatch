package ch.snepilatch.app.viewmodel

import io.mockk.coVerify
import io.mockk.excludeRecords
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Regression tests for issue #1024: a relay jam follows the host with explicit pause and resume. They
 * act only when the state differs and never flip the other way, which a toggle did whenever the state
 * changed between the decision and the call. Runs on the remote-device path, where each command is a
 * single verifiable call.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExplicitPlayPauseTest {

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

    private fun playing(isPlaying: Boolean) {
        rig.vm._playback.value = rig.vm._playback.value.copy(isPlaying = isPlaying, isPaused = !isPlaying)
    }

    @Test
    fun pauseWhilePlaying_pauses() {
        playing(true)
        rig.vm.pausePlayback()
        runBlocking { rig.vm.awaitCommandForTest() }
        coVerify(exactly = 1) { rig.player.pause() }
    }

    @Test
    fun pauseWhilePaused_sendsNothing() {
        playing(false)
        rig.vm.pausePlayback()
        runBlocking { rig.vm.awaitCommandForTest() }
        coVerify(exactly = 0) { rig.player.pause() }
        coVerify(exactly = 0) { rig.player.resume() }
    }

    /** The race itself: a toggle here would have paused a guest that was meant to play. */
    @Test
    fun resumeWhilePlaying_neverPauses() {
        playing(true)
        rig.vm.resumePlayback()
        runBlocking { rig.vm.awaitCommandForTest() }
        coVerify(exactly = 0) { rig.player.pause() }
        coVerify(exactly = 0) { rig.player.resume() }
    }

    @Test
    fun resumeWhilePaused_resumes() {
        playing(false)
        rig.vm.resumePlayback()
        runBlocking { rig.vm.awaitCommandForTest() }
        coVerify(exactly = 1) { rig.player.resume() }
    }
}
