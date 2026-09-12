package ch.snepilatch.app.viewmodel

import io.mockk.coEvery
import io.mockk.every
import kotify.api.playerstatus.DevicesInfo
import kotify.api.playerstatus.PlayerStateData
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** Issue #694: a remote device's position is advanced against the server clock, not the phone's. */
@OptIn(ExperimentalCoroutinesApi::class)
class ServerClockPositionTest {

    private val rig = PlaybackTestRig()

    @Before
    fun setUp() {
        rig.install()
        rig.vm.isInitialized.value = true
        coEvery { rig.player.getDevices() } returns DevicesInfo(emptyMap(), null)
    }

    @After
    fun tearDown() = rig.uninstall()

    @Test
    fun remotePosition_isTheSnapshotAdvancedByServerTime() {
        val snapshotAt = 1_700_000_000_000L
        coEvery { rig.player.getState() } returns PlayerStateData(
            is_playing = true, is_paused = false, track = null, position_as_of_timestamp = 10_000L,
            timestamp = snapshotAt, duration = 200_000L, play_origin = null,
            is_active_device = false, has_active_device = true, active_device_id = "desk",
        )
        // The phone clock is far from that timestamp; only the server clock may be used.
        every { rig.player.serverNowMs() } returns snapshotAt + 5_000L

        rig.vm.resyncOnForeground()

        val pos = runBlocking { withTimeout(2_000) { rig.vm.playback.first { it.positionMs == 15_000L }.positionMs } }
        assertEquals(15_000L, pos)
    }
}
