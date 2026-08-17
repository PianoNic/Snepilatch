package ch.snepilatch.app.viewmodel

import io.mockk.coVerify
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

/**
 * Regression tests for issue #589: resume picked its path from `isStreaming` alone.
 *
 * That flag says a stream was loaded at some point, not that ExoPlayer still holds it — the service
 * can be reclaimed or the player released while paused, and nothing clears it when that happens.
 * Resuming on the flag alone flipped the UI to playing and reported the position to Spfy while
 * `syncPlay` hit its `mediaItemCount > 0` guard and returned, so no audio was ever requested and
 * tapping play again just repeated it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ResumeWithoutLoadedMediaTest {

    private val rig = PlaybackTestRig()

    @Before
    fun setUp() = rig.install()

    @After
    fun tearDown() = rig.uninstall()

    /** The bug: streaming flag set, media gone. Resume must not pretend the hot path can work. */
    @Test
    fun resumeWithStreamingFlagButNoLoadedMedia_doesNotTakeTheHotPath() {
        rig.seedStreaming(positionMs = 42_000, isPaused = true, hasLoadedMedia = false)

        rig.vm.togglePlayPause()
        runBlocking { rig.vm.awaitCommandForTest() }

        verify(exactly = 0) { rig.service.syncPlay(any()) }
        coVerify(exactly = 0) { rig.player.localResume(any()) }
        assertFalse(
            "the stale streaming claim must be dropped so the cold start reloads",
            rig.vm.isStreaming.value
        )
    }

    /** The other half: with media actually loaded, resume still resumes rather than cold starting. */
    @Test
    fun resumeWithLoadedMedia_stillTakesTheHotPath() {
        rig.seedStreaming(positionMs = 42_000, isPaused = true, hasLoadedMedia = true)

        rig.vm.togglePlayPause()
        runBlocking { rig.vm.awaitCommandForTest() }

        verify(exactly = 1) { rig.service.syncPlay(42_000) }
        coVerify(exactly = 1) { rig.player.localResume(42_000) }
    }
}
