package ch.snepilatch.app.viewmodel

import io.mockk.coEvery
import io.mockk.coVerify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotify.api.playerstatus.DevicesInfo
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Issue #686: returning to the foreground pulls the live state and device list once, and does
 * nothing while the app is not initialized.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ForegroundResyncTest {

    private val rig = PlaybackTestRig()

    @Before
    fun setUp() {
        rig.install()
        coEvery { rig.player.getState() } returns null
        coEvery { rig.player.getDevices() } returns DevicesInfo(devices = emptyMap(), activeDeviceId = null)
    }

    @After
    fun tearDown() {
        rig.uninstall()
    }

    @Test
    fun whenInitialized_fetchesStateAndDevicesOnce() {
        rig.vm.isInitialized.value = true

        rig.vm.resyncOnForeground()

        coVerify(timeout = 1_000, exactly = 1) { rig.player.getState() }
        coVerify(timeout = 1_000, exactly = 1) { rig.player.getDevices() }
    }

    @Test
    fun whenNotInitialized_doesNothing() {
        rig.vm.isInitialized.value = false

        rig.vm.resyncOnForeground()

        coVerify(exactly = 0) { rig.player.getState() }
        coVerify(exactly = 0) { rig.player.getDevices() }
    }
}
