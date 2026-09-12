package ch.snepilatch.app.viewmodel

import io.mockk.coEvery
import io.mockk.coVerify
import kotify.api.playerstatus.DevicesInfo
import kotify.api.playerstatus.PlayerStateData
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Issue #692: the device list reloads when the active device changes, including a move from one
 * other device to another, and not on a push that names the same device again.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ActiveDeviceRefreshTest {

    private val rig = PlaybackTestRig()

    @Before
    fun setUp() {
        rig.install()
        rig.vm.isInitialized.value = true
        coEvery { rig.player.getDevices() } returns DevicesInfo(emptyMap(), null)
    }

    @After
    fun tearDown() = rig.uninstall()

    private fun playingOn(deviceId: String) = PlayerStateData(
        is_playing = true, is_paused = false, track = null, position_as_of_timestamp = 0L, play_origin = null,
        is_active_device = false, has_active_device = true, active_device_id = deviceId,
    )

    // Every foreground resync reloads the list once itself; the state edge adds one more per switch.

    @Test
    fun moveBetweenTwoOtherDevices_reloadsTheList() {
        coEvery { rig.player.getState() } returns playingOn("desk")
        rig.vm.resyncOnForeground()
        coVerify(timeout = 1_000, exactly = 2) { rig.player.getDevices() }

        coEvery { rig.player.getState() } returns playingOn("kitchen")
        rig.vm.resyncOnForeground()

        coVerify(timeout = 1_000, exactly = 4) { rig.player.getDevices() }
    }

    @Test
    fun sameDeviceAgain_doesNotReloadTheList() {
        coEvery { rig.player.getState() } returns playingOn("desk")
        rig.vm.resyncOnForeground()
        coVerify(timeout = 1_000, exactly = 2) { rig.player.getDevices() }

        rig.vm.resyncOnForeground()

        coVerify(timeout = 1_000, exactly = 3) { rig.player.getDevices() }
    }
}
