package ch.snepilatch.app.viewmodel

import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Repeat-one (loop) regression. Spotify signals "loop this track" by pointing the state machine's
 * advance back to the current track, which the engine reports as "exhausted" — so the track-end path
 * used to advance to the next song instead of replaying. With repeat-track on, the just-ended track
 * must loop from 0 (and NOT advance).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RepeatTrackLoopTest {

    private val rig = PlaybackTestRig()

    @Before
    fun setUp() {
        rig.install()
    }

    @After
    fun tearDown() {
        rig.uninstall()
    }

    @Test
    fun repeatTrackOn_replaysFromZero() {
        rig.seedStreaming(positionMs = 200_000L, isPaused = false)
        rig.vm._playback.value = rig.vm._playback.value.copy(repeatMode = "track")

        val handled = rig.vm.maybeLoopRepeatTrack()

        assertTrue("repeat-track must be handled as a loop", handled)
        verify { rig.service.syncPlay(0) }
        assertEquals("position resets to 0 for the loop", 0L, rig.vm.playback.value.positionMs)
        assertTrue("loop keeps playing", rig.vm.playback.value.isPlaying)
    }

    @Test
    fun repeatOff_doesNotLoop() {
        rig.seedStreaming(positionMs = 200_000L, isPaused = false) // default repeatMode = "off"

        val handled = rig.vm.maybeLoopRepeatTrack()

        assertFalse("with repeat off, end-of-track is not a loop", handled)
        verify(exactly = 0) { rig.service.syncPlay(any()) }
    }
}
