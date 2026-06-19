package ch.snepilatch.app.viewmodel

import ch.snepilatch.app.playback.SessionHolder
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotify.api.playerconnect.PlayerConnect
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A Wi-Fi/cell handover times out the end-of-track advance. Like the web player — which keeps its
 * session and re-registers on reconnect rather than stopping — we must NOT tear the stream down on a
 * recoverable transport error, and we must re-fire the interrupted advance once the dealer reconnects.
 * This is the regression guard for the "stuck on one song after walking into the office" bug.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RemoteAdvanceRecoveryTest {

    private val rig = PlaybackTestRig()

    @Before
    fun setUp() {
        rig.install()
    }

    @After
    fun tearDown() {
        SessionHolder.player = null
        rig.uninstall()
    }

    @Test
    fun transportDropDuringAdvance_keepsStreamAndRetriesOnReconnect() {
        val pc = mockk<PlayerConnect>(relaxed = true)
        coEvery { pc.forceAdvance() } returns true
        SessionHolder.player = pc
        rig.seedStreaming(positionMs = 200_000L, isPaused = false)

        // The advance throws because the connect-state request timed out mid-handover.
        rig.vm.handleAdvanceFailure(RuntimeException("context deadline exceeded"))

        // Session is kept (not stopped), shown paused — exactly like the web player while reconnecting.
        assertTrue("stream must stay alive", rig.vm.isStreaming.value)
        assertTrue("playback shown paused", rig.vm.playback.value.isPaused)
        assertFalse(rig.vm.playback.value.isPlaying)

        // When the dealer reconnects, the interrupted advance is re-fired.
        runBlocking { rig.vm.retryPendingAdvance() }
        coVerify(exactly = 1) { pc.forceAdvance() }
    }

    @Test
    fun retryPendingAdvance_isNoOpWhenNothingPending() {
        val pc = mockk<PlayerConnect>(relaxed = true)
        SessionHolder.player = pc

        runBlocking { rig.vm.retryPendingAdvance() }

        coVerify(exactly = 0) { pc.forceAdvance() }
    }

    @Test
    fun advanceFailureWithNoPlayer_stopsStream() {
        SessionHolder.player = null
        rig.seedStreaming()

        rig.vm.handleAdvanceFailure(RuntimeException("boom"))

        assertFalse("with no live session there is nothing to recover — stop", rig.vm.isStreaming.value)
    }
}
