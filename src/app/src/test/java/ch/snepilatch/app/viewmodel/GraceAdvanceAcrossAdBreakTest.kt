package ch.snepilatch.app.viewmodel

import ch.snepilatch.app.playback.SessionHolder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Regression guard for a track eaten right after an ad break. The end-of-track grace was armed before
 * the break, the break spanned the whole window, and neither of its tests noticed: the silent ad clip
 * never sets currentStreamUri so it still read as the outgoing track, and the clip had ended so
 * nothing was playing. It forced an advance 16ms before the post-ad stream committed, skipping it.
 *
 * Captured live at 15:01:47.561 as "Auto-advance didn't fire (still on spotify:track:53pILL...),
 * forcing local advance", which ate 勇者 and jumped to Retry Now.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GraceAdvanceAcrossAdBreakTest {

    private val rig = PlaybackTestRig()
    private val ended = "spotify:track:theOneThatEnded"

    @Before
    fun setUp() {
        rig.install()
    }

    @After
    fun tearDown() {
        SessionHolder.player = null
        rig.uninstall()
    }

    @Test
    fun doesNotForceAnAdvanceWhenAnAdBreakSpannedTheWindow() {
        rig.seedStreaming()
        rig.vm.currentStreamUri = ended
        val armed = rig.vm.currentAdEpoch()

        // The break: two ads, then the post-ad track's audio is announced. The stream has not
        // committed yet, which is exactly the 16ms gap that lost the track.
        rig.vm.handleAd(1000L)
        rig.vm.handleAd(1000L)
        rig.vm.handlePlaybackId("postAdFileId", "spotify:track:postad")

        assertFalse(
            "the ad break advanced us already, forcing again eats the post-ad track",
            rig.vm.graceAdvanceShouldFire(ended, armed, exoPlaying = false)
        )
    }

    @Test
    fun stillForcesAnAdvanceWhenNothingHappenedAtAll() {
        rig.seedStreaming()
        rig.vm.currentStreamUri = ended
        val armed = rig.vm.currentAdEpoch()

        // No ad, no new stream, nothing playing: this is the stall the grace exists for.
        assertTrue(rig.vm.graceAdvanceShouldFire(ended, armed, exoPlaying = false))
    }

    @Test
    fun doesNotForceAnAdvanceOnceTheNextStreamCommitted() {
        rig.seedStreaming()
        rig.vm.currentStreamUri = ended
        val armed = rig.vm.currentAdEpoch()

        rig.vm.currentStreamUri = "spotify:track:next"

        assertFalse(rig.vm.graceAdvanceShouldFire(ended, armed, exoPlaying = false))
    }

    @Test
    fun doesNotForceAnAdvanceWhileAudioIsPlaying() {
        rig.seedStreaming()
        rig.vm.currentStreamUri = ended
        val armed = rig.vm.currentAdEpoch()

        assertFalse(rig.vm.graceAdvanceShouldFire(ended, armed, exoPlaying = true))
    }
}
