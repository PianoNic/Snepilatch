package ch.snepilatch.app.viewmodel

import io.mockk.coVerify
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Regression tests for issue #571: Snepilatch would not let another app play.
 *
 * ExoPlayer is built with `handleAudioFocus = true`, so it owns focus entirely, and Android's guidance
 * is that an app with automatic handling should contain no code responding to focus changes. Snepilatch
 * had some anyway: on focus return it called `togglePlayPause()`, which restarted the local player,
 * re-requested focus and took it straight back off whatever app had asked for it.
 *
 * All that is left is a report to Spfy, because we are also a Connect device whose cloud clock keeps
 * advancing while our audio is muted. The local player is moved by the *echo* of that report through
 * [PlaybackViewModel.handleRemotePause] / [PlaybackViewModel.handleRemotePlay], not from here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AudioFocusReportOnlyTest {

    private val rig = PlaybackTestRig()

    @Before
    fun setUp() = rig.install()

    @After
    fun tearDown() = rig.uninstall()

    @Test
    fun focusLoss_reportsPauseAndLeavesTheLocalPlayerAlone() {
        rig.seedStreaming(positionMs = 30_000)

        rig.vm.handleAudioFocusPaused()

        coVerify(exactly = 1) { rig.player.localPause(30_000) }
        verify(exactly = 0) { rig.service.syncPause() }
        verify(exactly = 0) { rig.service.syncPlay(any()) }
    }

    @Test
    fun focusRegain_reportsResumeAndLeavesTheLocalPlayerAlone() {
        rig.seedStreaming(positionMs = 30_000)
        rig.vm.handleAudioFocusPaused()
        rig.vm.handleRemotePause(30_000) // the echo of that report

        rig.vm.handleAudioFocusResumed()

        coVerify(timeout = 1_000, exactly = 1) { rig.player.localResume(30_000) }
        verify(exactly = 0) { rig.service.syncPlay(any()) }
    }

    /** Issue #700: a pause the user chose stays paused through a call. */
    @Test
    fun focusRegain_afterAManualPause_doesNotResume() {
        rig.seedStreaming(positionMs = 30_000, isPaused = true)

        rig.vm.handleAudioFocusPaused()
        rig.vm.handleAudioFocusResumed()

        coVerify(exactly = 0) { rig.player.localResume(any()) }
    }

    /**
     * Issue #700 as reported: an earlier loss that never regained (another app taking over, headphones
     * pulled) had paused us and left its mark. The user then played and paused by hand, and the call
     * after that resumed on hang-up.
     */
    @Test
    fun focusRegain_afterAManualPauseThatFollowedAnUnregainedLoss_doesNotResume() {
        rig.seedStreaming(positionMs = 30_000)
        rig.vm.handleAudioFocusPaused()
        coVerify(timeout = 1_000, exactly = 1) { rig.player.localPause(30_000) }

        rig.vm.handleRemotePlay(30_000) // the user presses play
        rig.vm.handleRemotePause(30_000) // and later pause
        rig.vm.handleAudioFocusPaused() // the call
        rig.vm.handleAudioFocusResumed() // hang-up

        coVerify(exactly = 0) { rig.player.localResume(any()) }
    }

    /**
     * The bug that survived the first fix: focus regain must not pre-clear `isPaused`.
     *
     * [PlaybackViewModel.handleRemotePlay] only restarts ExoPlayer when it still sees `isPaused`, so
     * clearing the flag here made the echo a no-op — Spfy showed playing while the phone stayed silent.
     */
    @Test
    fun focusRegain_leavesIsPausedSetSoTheEchoStillRestartsTheAudio() {
        rig.seedStreaming(positionMs = 30_000, isPaused = true)

        rig.vm.handleAudioFocusResumed()

        assertTrue(
            "isPaused must survive the report, or handleRemotePlay's guard skips syncPlay",
            rig.vm._playback.value.isPaused
        )

        // The echo lands and *this* is what restarts the audio.
        rig.vm.handleRemotePlay(30_000)
        verify(exactly = 1) { rig.service.syncPlay(30_000) }
    }

    /** Un-suppression fires whether or not we were suppressed; idle means nothing to report. */
    @Test
    fun focusCallbacksAreInertWhenNotStreaming() {
        rig.vm.handleAudioFocusResumed()
        rig.vm.handleAudioFocusPaused()

        coVerify(exactly = 0) { rig.player.localResume(any()) }
        coVerify(exactly = 0) { rig.player.localPause(any()) }
    }
}
