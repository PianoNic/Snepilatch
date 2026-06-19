package ch.snepilatch.app.viewmodel

import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * A stale cloud snapshot can surface a remote seek target far past the track end (the real bug:
 * `Remote seek -> 1420411ms` on a multi-minute track instantly ended it). A legitimate seek is always
 * within the track, so an out-of-range target must be ignored — mirroring the web player, where the
 * media element clamps currentTime to [0, duration].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RemoteSeekBoundsTest {

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
    fun inBoundsSeek_appliesToExoPlayer() {
        rig.seedStreaming(positionMs = 0L, isPaused = false) // duration = 200_000

        rig.vm.handleRemoteSeek(60_000L)

        verify { rig.service.syncSeek(60_000L) }
        assertEquals(60_000L, rig.vm.playback.value.positionMs)
    }

    @Test
    fun outOfRangeSeek_isIgnored() {
        rig.seedStreaming(positionMs = 30_000L, isPaused = false) // duration = 200_000

        rig.vm.handleRemoteSeek(1_420_411L) // ~23 min, way past the end

        verify(exactly = 0) { rig.service.syncSeek(any()) }
        assertEquals("position must not move on a bogus seek", 30_000L, rig.vm.playback.value.positionMs)
    }

    @Test
    fun negativeSeek_isIgnored() {
        rig.seedStreaming(positionMs = 30_000L, isPaused = false)

        rig.vm.handleRemoteSeek(-5_000L)

        verify(exactly = 0) { rig.service.syncSeek(any()) }
        assertEquals(30_000L, rig.vm.playback.value.positionMs)
    }
}
