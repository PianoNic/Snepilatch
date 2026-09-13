package ch.snepilatch.app.viewmodel

import ch.snepilatch.app.logic.shared.SessionHolder
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotify.api.playerconnect.NoActiveDeviceException
import kotify.api.playerstatus.DeviceInfo
import kotify.api.playerstatus.DevicesInfo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Issue #685: a device switch closes the sheet only once the cluster confirmed it, an unconfirmed
 * one keeps the sheet open with a message, and picking this phone goes through the cold start.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeviceSwitchTest {

    private val rig = PlaybackTestRig()

    @Before
    fun setUp() {
        rig.install()
        every { rig.player.ourDeviceId() } returns "phone"
        coEvery { rig.player.getDevices() } returns DevicesInfo(
            devices = mapOf("desk" to DeviceInfo(id = "desk", name = "Desk", type = "computer", volume = 0, is_active = true)),
            activeDeviceId = "desk",
        )
        rig.vm.showDevices.value = true
    }

    @After
    fun tearDown() {
        SessionHolder.cdnResolver = null
        rig.uninstall()
    }

    private fun <T> await(block: suspend () -> T): T = runBlocking { withTimeout(2_000) { block() } }

    @Test
    fun confirmedTransfer_closesTheSheet() {
        coEvery { rig.player.transferPlaybackTo("desk", any()) } returns true

        rig.vm.transferPlayback("desk")

        await { rig.vm.showDevices.first { !it } }
        assertNull(rig.vm.transferError.value)
        coVerify(timeout = 1_000, exactly = 1) { rig.player.getDevices() }
        assertEquals("Desk", await { rig.vm.activeDeviceName.first { it != null } })
    }

    @Test
    fun unconfirmedTransfer_keepsTheSheetOpenAndSetsTheError() {
        coEvery { rig.player.transferPlaybackTo("desk", any()) } returns false

        rig.vm.transferPlayback("desk")

        assertNotNull(await { rig.vm.transferError.first { it != null } })
        assertTrue("sheet stays open", rig.vm.showDevices.value)
        coVerify(exactly = 0) { rig.player.getDevices() }
    }

    @Test
    fun transferToAVanishedDevice_keepsTheSheetOpenAndSetsTheError() {
        coEvery { rig.player.transferPlaybackTo("desk", any()) } throws NoActiveDeviceException("desk")

        rig.vm.transferPlayback("desk")

        assertNotNull(await { rig.vm.transferError.first { it != null } })
        assertTrue("sheet stays open", rig.vm.showDevices.value)
    }

    @Test
    fun playHereOnOwnDevice_goesThroughTheColdStart() {
        SessionHolder.cdnResolver = mockk(relaxed = true)

        rig.vm.transferPlayback("hobs_phone")

        await { rig.vm.showDevices.first { !it } }
        coVerify(timeout = 1_000, exactly = 1) { rig.player.transferPlaybackHere(true) }
        coVerify(exactly = 0) { rig.player.transferPlaybackTo(any(), any()) }
    }
}
