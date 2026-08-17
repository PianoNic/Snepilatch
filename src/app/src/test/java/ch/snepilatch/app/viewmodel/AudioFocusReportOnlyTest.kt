package ch.snepilatch.app.viewmodel

import io.mockk.coVerify
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Regression tests for issue #571: Snepilatch would not let another app play.
 *
 * ExoPlayer is built with `handleAudioFocus = true`, so it owns focus entirely — it suppresses itself
 * on a transient loss and un-suppresses when focus returns. Android's guidance is that an app with
 * automatic handling should contain no code responding to focus changes. Snepilatch had some anyway:
 * on focus return it called `togglePlayPause()`, which resumed the local player, which re-requested
 * focus and took it straight back off whatever app had asked for it.
 *
 * What remains is a report to Spfy, because we are also a Connect device whose cloud clock keeps
 * advancing while our audio is muted. These tests pin "report, never command".
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AudioFocusReportOnlyTest {

    private val rig = PlaybackTestRig()

    @Before
    fun setUp() = rig.install()

    @After
    fun tearDown() = rig.uninstall()

    @Test
    fun focusLoss_reportsPauseToSpfyWithoutTouchingTheLocalPlayer() {
        rig.seedStreaming(positionMs = 30_000)

        rig.vm.handleAudioFocusPaused()

        coVerify(exactly = 1) { rig.player.localPause(30_000) }
        verify(exactly = 0) { rig.service.syncPause() }
        verify(exactly = 0) { rig.service.syncPlay(any()) }
        assertTrue(rig.vm._playback.value.isPaused)
    }

    /** The actual #571 bug: regaining focus must not start the local player back up. */
    @Test
    fun focusRegain_reportsPlayToSpfyWithoutRestartingTheLocalPlayer() {
        rig.seedStreaming(positionMs = 30_000, isPaused = true)

        rig.vm.handleAudioFocusResumed()

        coVerify(exactly = 1) { rig.player.localResume(30_000) }
        verify(exactly = 0) { rig.service.syncPlay(any()) }
        assertFalse(rig.vm._playback.value.isPaused)
    }

    /**
     * Un-suppression fires whether or not we were ever suppressed, and while idle there is nothing to
     * report — an unsolicited resume would claim the device.
     */
    @Test
    fun focusCallbacksAreInertWhenNotStreaming() {
        rig.vm.handleAudioFocusResumed()
        rig.vm.handleAudioFocusPaused()

        coVerify(exactly = 0) { rig.player.localResume(any()) }
        coVerify(exactly = 0) { rig.player.localPause(any()) }
    }
}
