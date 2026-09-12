package ch.snepilatch.app.viewmodel

import ch.snepilatch.app.data.TrackInfo
import io.mockk.coEvery
import io.mockk.coVerify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Issue #724: a tapped queue row becomes the playing track at the tap, the way a skip does, so the
 * sheet can move the row up into the now playing slot instead of waiting for the echo.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class QueueTapShowsTrackTest {

    private val rig = PlaybackTestRig()

    @Before
    fun setUp() {
        rig.install()
        coEvery { rig.player.skipToTrack(any()) } returns true
    }

    @After
    fun tearDown() = rig.uninstall()

    private fun <T> await(block: suspend () -> T): T = runBlocking { withTimeout(2_000) { block() } }

    @Test
    fun tappedRow_isShownAsThePlayingTrackAtOnce() {
        val tapped = TrackInfo(
            uri = "spotify:track:b", name = "B", artist = "X", albumArt = null, durationMs = 180_000,
            uid = "ub", qid = "ub:::0", queueIndex = 1,
        )
        rig.vm._queue.value = listOf(
            TrackInfo(uri = "spotify:track:a", name = "A", artist = "X", albumArt = null, uid = "ua", qid = "ua:::0", queueIndex = 0),
            tapped,
        )

        rig.vm.skipToQueueIndex(1)

        assertEquals("spotify:track:b", await { rig.vm.playback.first { it.track?.uri == "spotify:track:b" }.track?.uri })
        assertEquals(0L, rig.vm.playback.value.positionMs)
        coVerify(timeout = 1_000) { rig.player.skipToTrack("ub:::0") }
    }
}
