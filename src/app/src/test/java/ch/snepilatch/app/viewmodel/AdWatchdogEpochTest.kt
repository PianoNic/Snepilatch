package ch.snepilatch.app.viewmodel

import ch.snepilatch.app.playback.SessionHolder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Regression guard for the back-to-back-ad skip: two ads in a row each arm an advance watchdog when
 * their silent clip ends, but the clip never sets currentStreamUri, so it stays on the pre-ad track
 * for the whole break. The watchdog armed for the FIRST ad then found isAd still true (the second ad
 * was playing) and the URI unchanged, forced an advance the engine had already made, and the next
 * real track was skipped ~1s in. Captured live on device at 09:21:12.521 as
 * "Ad advance didn't fire (still on the ad) — forcing local advance".
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AdWatchdogEpochTest {

    private val rig = PlaybackTestRig()

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
    fun watchdogArmedForFirstAd_doesNotFireDuringTheSecondAd() {
        rig.seedStreaming()

        // Ad #1 starts and its silent clip ends -> a watchdog is armed for this ad.
        rig.vm.handleAd(1000L)
        val armedEpoch = rig.vm.currentAdEpoch()
        val armedUri = rig.vm.currentStreamUri

        // The engine advances ad #1 -> ad #2 on its own. isAd stays true and the stream URI is
        // unchanged, so only the epoch can tell these apart.
        rig.vm.handleAd(1000L)

        assertNotEquals("a new ad must supersede the previous generation", armedEpoch, rig.vm.currentAdEpoch())
        assertTrue("still on an ad", rig.vm.playback.value.isAd)
        assertFalse(
            "watchdog for ad #1 must not force an advance while ad #2 is playing",
            rig.vm.adWatchdogShouldFire(armedEpoch, armedUri)
        )
    }

    @Test
    fun watchdogDoesNotFireOnceTheEngineAlreadyLeftTheAd() {
        rig.seedStreaming()

        rig.vm.handleAd(1000L)
        val armedEpoch = rig.vm.currentAdEpoch()
        val armedUri = rig.vm.currentStreamUri

        // The engine advances off the ad and the post-ad track's audio is announced. isAd is still set
        // and the stream URI has not moved yet — the post-ad state lands ~1.5s later — so only this
        // signal can stop the watchdog. Captured live at 11:05:45.855 skipping "Wieso?" 3.2s in.
        rig.vm.handlePlaybackId("postAdFileId", "spotify:track:postad")

        assertTrue("still flagged as an ad", rig.vm.playback.value.isAd)
        assertEquals("stream has not moved yet", armedUri, rig.vm.currentStreamUri)
        assertFalse(
            "the engine already advanced — the watchdog must not force another advance",
            rig.vm.adWatchdogShouldFire(armedEpoch, armedUri)
        )
    }

    @Test
    fun watchdogStillFiresWhenTheSameAdIsGenuinelyStuck() {
        rig.seedStreaming()

        rig.vm.handleAd(1000L)
        val armedEpoch = rig.vm.currentAdEpoch()
        val armedUri = rig.vm.currentStreamUri

        // Nothing advanced: same ad, same stream. The rescue must still work.
        assertTrue(rig.vm.adWatchdogShouldFire(armedEpoch, armedUri))
    }

    @Test
    fun watchdogDoesNotFireOnceTheRealTrackTookOver() {
        rig.seedStreaming()

        rig.vm.handleAd(1000L)
        val armedEpoch = rig.vm.currentAdEpoch()
        val armedUri = rig.vm.currentStreamUri

        // The post-ad track committed its stream; the ad placeholder is gone.
        rig.vm.currentStreamUri = "spotify:track:postad"
        rig.vm._playback.value = rig.vm.playback.value.copy(isAd = false)

        assertFalse(rig.vm.adWatchdogShouldFire(armedEpoch, armedUri))
    }
}
