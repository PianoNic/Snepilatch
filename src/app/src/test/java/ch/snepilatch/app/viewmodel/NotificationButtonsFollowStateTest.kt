package ch.snepilatch.app.viewmodel

import io.mockk.coEvery
import io.mockk.verify
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
 * Issue #714: the notification's shuffle, repeat and like buttons render the playback state, so a tap
 * repaints them at once, before the command has come back, and a failed command paints them back.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NotificationButtonsFollowStateTest {

    private val rig = PlaybackTestRig()

    @Before
    fun setUp() = rig.install()

    @After
    fun tearDown() = rig.uninstall()

    private fun <T> await(block: suspend () -> T): T = runBlocking { withTimeout(2_000) { block() } }

    @Test
    fun shuffleToggle_repaintsBeforeTheCommandReturns() {
        val commandReturns = CompletableDeferred<Boolean>()
        coEvery { rig.player.setShuffle("on") } coAnswers { commandReturns.await() }

        rig.vm.toggleShuffle()

        assertTrue(await { rig.vm.playback.first { it.isShuffling }.isShuffling })
        verify(timeout = 1_000) { rig.service.isShuffling = true }
        verify(timeout = 1_000, atLeast = 1) { rig.service.updateNotification() }
        commandReturns.complete(true)
    }

    @Test
    fun shuffleToggle_thatFails_paintsBack() {
        coEvery { rig.player.setShuffle("on") } throws IllegalStateException("no device")

        rig.vm.toggleShuffle()

        assertFalse(await { rig.vm.playback.first { !it.isShuffling }.isShuffling })
        verify(timeout = 1_000) { rig.service.isShuffling = false }
    }

    @Test
    fun repeatCycle_repaintsWithTheNewMode() {
        coEvery { rig.player.setRepeat("context") } returns true

        rig.vm.cycleRepeat()

        assertEquals("context", await { rig.vm.playback.first { it.repeatMode == "context" }.repeatMode })
        verify(timeout = 1_000) { rig.service.repeatMode = "context" }
    }

    @Test
    fun likeChange_repaintsTheHeart() {
        rig.vm.currentTrackLiked.value = true

        verify(timeout = 1_000) { rig.service.isLiked = true }
        verify(timeout = 1_000, atLeast = 1) { rig.service.updateNotification() }
    }
}
