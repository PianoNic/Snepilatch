package ch.snepilatch.app.viewmodel

import ch.snepilatch.app.logic.shared.JamHolder
import io.mockk.coVerify
import io.mockk.excludeRecords
import kotify.api.jam.JamSession
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

/**
 * Regression test for issue #1014: in a jam as a controller (CONTROL, the only role a Free account may
 * take) play must stay a command to the jam even with no device active. Claiming this phone instead
 * left the server paused under audio that played, and taking the jam over dropped the account from it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class JamControllerTransportTest {

    private val rig = PlaybackTestRig()

    private fun jam(listening: Boolean) = JamSession(
        sessionId = "s", joinSessionToken = "t", joinSessionUri = "u", sessionOwnerId = "host",
        members = emptyList(), isSessionOwner = false, isListening = listening, isControlling = true,
        active = true, queueOnlyMode = false, isPaused = false, hostActiveDeviceId = null,
        initialSessionType = null, hostDevice = null,
    )

    @Before
    fun setUp() {
        rig.install()
        rig.vm.setForeignDeviceActiveForTest(false)
        excludeRecords { rig.player.ourDeviceId() }
    }

    @After
    fun tearDown() {
        JamHolder.clear()
        rig.uninstall()
    }

    @Test
    fun resumeAsJamController_sendsCommandAndDoesNotClaimThePhone() {
        JamHolder.session.value = jam(listening = false)
        rig.vm._playback.value = rig.vm._playback.value.copy(isPlaying = false, isPaused = true)

        rig.vm.togglePlayPause()
        runBlocking { rig.vm.awaitCommandForTest() }

        coVerify(exactly = 1) { rig.player.resume() }
        coVerify(exactly = 0) { rig.player.transferPlaybackHere(any()) }
        coVerify(exactly = 0) { rig.player.localResume(any()) }
        assertFalse("a controller must not enter the cold-start path", rig.vm.isStreamLoading.value)
    }
}
