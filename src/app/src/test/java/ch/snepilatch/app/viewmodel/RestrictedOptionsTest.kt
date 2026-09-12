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
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

/**
 * Issue #726: the server's toggling restrictions grey the buttons and shape the repeat cycle, as on
 * the web player. A tap on a disallowed control, from a screen or the notification, does nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RestrictedOptionsTest {

    private val rig = PlaybackTestRig()

    @Before
    fun setUp() {
        rig.install()
        coEvery { rig.player.setShuffle(any()) } returns true
        coEvery { rig.player.setRepeat(any()) } returns true
    }

    @After
    fun tearDown() = rig.uninstall()

    private fun <T> await(block: suspend () -> T): T = runBlocking { withTimeout(2_000) { block() } }

    @Test
    fun shuffleTap_whileDisallowed_sendsNothing() {
        rig.vm._playback.value = PlaybackUiState(canToggleShuffle = false)

        rig.vm.toggleShuffle()

        coVerify(exactly = 0) { rig.player.setShuffle(any()) }
        assertFalse(rig.vm.playback.value.isShuffling)
        assertFalse(await { rig.vm.canToggleShuffleFlow.first { !it } })
    }

    @Test
    fun repeatCycle_skipsContextWhenTheServerDisallowsIt() {
        rig.vm._playback.value = PlaybackUiState(repeatMode = "off", canToggleRepeatContext = false)

        rig.vm.cycleRepeat()

        assertEquals("track", await { rig.vm.playback.first { it.repeatMode == "track" }.repeatMode })
        coVerify(timeout = 1_000) { rig.player.setRepeat("track") }
    }

    @Test
    fun repeatCycle_fallsBackToOffWhenTrackIsDisallowed() {
        rig.vm._playback.value = PlaybackUiState(repeatMode = "context", canToggleRepeatTrack = false)

        rig.vm.cycleRepeat()

        assertEquals("off", await { rig.vm.playback.first { it.repeatMode == "off" }.repeatMode })
        coVerify(timeout = 1_000) { rig.player.setRepeat("off") }
    }

    @Test
    fun repeatCycle_withBothDisallowed_sendsNothing() {
        rig.vm._playback.value = PlaybackUiState(repeatMode = "off", canToggleRepeatContext = false, canToggleRepeatTrack = false)

        rig.vm.cycleRepeat()

        coVerify(exactly = 0) { rig.player.setRepeat(any()) }
        assertFalse(await { rig.vm.canToggleRepeatFlow.first { !it } })
    }
}
