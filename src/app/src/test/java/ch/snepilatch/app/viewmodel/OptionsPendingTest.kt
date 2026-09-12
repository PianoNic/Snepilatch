package ch.snepilatch.app.viewmodel

import io.mockk.coEvery
import io.mockk.coVerify
import kotify.api.playerstatus.DevicesInfo
import kotify.api.playerstatus.PlayerStateData
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Issue #716: a shuffle or repeat change is pending until the cluster confirms it. While it is, no
 * second change goes out and no cluster frame paints over the requested value; a change the cluster
 * does not confirm is painted back.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OptionsPendingTest {

    private val rig = PlaybackTestRig()

    @Before
    fun setUp() {
        rig.install()
        rig.vm.isInitialized.value = true
        coEvery { rig.player.getDevices() } returns DevicesInfo(emptyMap(), null)
    }

    @After
    fun tearDown() = rig.uninstall()

    private fun <T> await(block: suspend () -> T): T = runBlocking { withTimeout(2_000) { block() } }

    private fun clusterSays(shuffling: Boolean) = PlayerStateData(
        is_playing = true, is_paused = false, track = null, position_as_of_timestamp = 0L, play_origin = null,
        is_active_device = true, has_active_device = true, active_device_id = "us", is_shuffling = shuffling,
    )

    @Test
    fun shuffleToggle_thatTheClusterDoesNotConfirm_paintsBack() {
        coEvery { rig.player.setShuffle("on") } returns false

        rig.vm.toggleShuffle()

        assertFalse(await { rig.vm.playback.first { !it.isShuffling }.isShuffling })
        assertFalse(await { rig.vm.optionsPending.first { !it } })
    }

    /** Issue #732: a tap while a change is in flight is a new request, and the newest one counts. */
    @Test
    fun secondTap_whileTheFirstIsPending_isANewRequestThatWins() {
        val firstReturns = CompletableDeferred<Boolean>()
        coEvery { rig.player.setShuffle("on") } coAnswers { firstReturns.await() }
        coEvery { rig.player.setShuffle("off") } returns true

        rig.vm.toggleShuffle()
        assertTrue(await { rig.vm.optionsPending.first { it } })
        rig.vm.toggleShuffle()

        coVerify(timeout = 1_000, exactly = 1) { rig.player.setShuffle("off") }
        assertFalse(await { rig.vm.playback.first { !it.isShuffling }.isShuffling })
        // The first request comes back a failure after the second landed: it no longer paints back.
        firstReturns.complete(false)
        Thread.sleep(300)
        assertFalse(rig.vm.playback.value.isShuffling)
        assertFalse(rig.vm.optionsPending.value)
    }

    @Test
    fun clusterFrame_whileTheChangeIsPending_doesNotPaintOverIt() {
        val commandReturns = CompletableDeferred<Boolean>()
        coEvery { rig.player.setShuffle("on") } coAnswers { commandReturns.await() }
        coEvery { rig.player.getState() } returns clusterSays(shuffling = false)

        rig.vm.toggleShuffle()
        assertTrue(await { rig.vm.optionsPending.first { it } })
        rig.vm.resyncOnForeground()

        assertTrue(rig.vm.playback.value.isShuffling)
        commandReturns.complete(true)
        assertFalse(await { rig.vm.optionsPending.first { !it } })

        // Once settled, the cluster is the truth again.
        rig.vm.resyncOnForeground()
        assertFalse(await { rig.vm.playback.first { !it.isShuffling }.isShuffling })
    }

    /** A cluster frame carrying the requested value confirms the change even if the ack never comes. */
    @Test
    fun clusterFrame_withTheRequestedValue_endsThePendingPhaseWithoutTheAck() {
        val commandReturns = CompletableDeferred<Boolean>()
        coEvery { rig.player.setShuffle("on") } coAnswers { commandReturns.await() }
        coEvery { rig.player.getState() } returns clusterSays(shuffling = true).copy(shuffle_mode = "on")

        rig.vm.toggleShuffle()
        assertTrue(await { rig.vm.optionsPending.first { it } })
        rig.vm.resyncOnForeground()

        assertFalse(await { rig.vm.optionsPending.first { !it } })
        // The late verdict is a miss, but the state already showed the change: nothing paints back.
        commandReturns.complete(false)
        Thread.sleep(300)
        assertTrue(rig.vm.playback.value.isShuffling)
    }

    @Test
    fun repeatCycle_thatTheClusterDoesNotConfirm_paintsBack() {
        coEvery { rig.player.setRepeat("context") } returns false

        rig.vm.cycleRepeat()

        assertEquals("off", await { rig.vm.playback.first { it.repeatMode == "off" }.repeatMode })
    }
}
