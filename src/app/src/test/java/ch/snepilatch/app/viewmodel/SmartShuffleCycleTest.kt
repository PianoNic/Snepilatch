package ch.snepilatch.app.viewmodel

import ch.snepilatch.app.data.PlaybackUiState
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
 * Issue #730: the shuffle button cycles off, on, smart, off over the modes the server allows, the
 * way the web player's cycleShuffleMode does. Smart is in the cycle only while the state allows the
 * mode and the context is eligible, or while it is already on.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SmartShuffleCycleTest {

    private val rig = PlaybackTestRig()

    @Before
    fun setUp() {
        rig.install()
        coEvery { rig.player.setShuffle(any()) } returns true
    }

    @After
    fun tearDown() = rig.uninstall()

    private fun <T> await(block: suspend () -> T): T = runBlocking { withTimeout(2_000) { block() } }

    private fun modeAfterTap(from: String, canSmart: Boolean, canToggle: Boolean = true): String {
        rig.vm._playback.value = PlaybackUiState(
            shuffleMode = from, isShuffling = from != "off", canToggleShuffle = canToggle, canSmartShuffle = canSmart,
        )
        rig.vm.toggleShuffle()
        return await { rig.vm.optionsPending.first { !it } }.let { rig.vm.playback.value.shuffleMode }
    }

    @Test
    fun withoutSmart_itTogglesOffAndOn() {
        assertEquals("on", modeAfterTap("off", canSmart = false))
        assertEquals("off", modeAfterTap("on", canSmart = false))
        coVerify(exactly = 1) { rig.player.setShuffle("on") }
        coVerify(exactly = 1) { rig.player.setShuffle("off") }
    }

    @Test
    fun withSmart_itCyclesThroughAllThree() {
        assertEquals("on", modeAfterTap("off", canSmart = true))
        assertEquals("smart", modeAfterTap("on", canSmart = true))
        assertEquals("off", modeAfterTap("smart", canSmart = true))
        coVerify(exactly = 1) { rig.player.setShuffle("smart") }
    }

    @Test
    fun smartOnWhereItIsNoLongerAllowed_stillLeavesToOff() {
        assertEquals("off", modeAfterTap("smart", canSmart = false))
    }

    @Test
    fun aDisallowedShuffle_sendsNothing() {
        rig.vm._playback.value = PlaybackUiState(shuffleMode = "off", canToggleShuffle = false)

        rig.vm.toggleShuffle()

        coVerify(exactly = 0) { rig.player.setShuffle(any()) }
        assertEquals("off", rig.vm.playback.value.shuffleMode)
    }
}
