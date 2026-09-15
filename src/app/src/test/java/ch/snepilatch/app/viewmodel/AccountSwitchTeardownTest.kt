package ch.snepilatch.app.viewmodel

import ch.snepilatch.app.logic.shared.AccountStore
import ch.snepilatch.app.logic.shared.SavedAccount
import ch.snepilatch.app.logic.shared.SessionHolder
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Switching accounts (#847) must leave nothing of the old one behind: the shown track, the account
 * and the session all go, so the next initialize builds from the new cookies instead of adopting
 * what the holder still had.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AccountSwitchTeardownTest {

    private val rig = PlaybackTestRig()

    @Before fun setUp() = rig.install()

    @After fun tearDown() = rig.uninstall()

    @Test fun shutDown_clearsTheTrackAccountAndSession() = runBlocking {
        rig.seedStreaming(positionMs = 30_000)
        rig.vm.isInitialized.value = true

        rig.vm.shutDownSession()

        assertNull("the outgoing track is gone", rig.vm.playback.value.track)
        assertEquals(0, rig.vm.playback.value.positionMs)
        assertEquals("", rig.vm.account.value.username)
        assertFalse(rig.vm.isInitialized.value)
        assertNull("the holder is empty, so nothing gets adopted", SessionHolder.player)
        assertFalse(SessionHolder.isReady)
    }

    @Test fun shutDown_stopsTheAudio() = runBlocking {
        rig.seedStreaming()
        rig.vm.shutDownSession()
        io.mockk.verify { rig.service.stop() }
    }

    @Test fun loggingOut_withAnotherAccountSaved_keepsTheAppSignedIn() = runBlocking {
        val gone = SavedAccount("gone", "Gone", null, mapOf("sp_dc" to "a"))
        val other = SavedAccount("other", "Other", null, mapOf("sp_dc" to "b"))
        AccountStore.accounts.value = listOf(gone, other)
        SessionHolder.username = "gone"

        rig.vm.logout(mockk(relaxed = true))?.join()

        assertEquals(listOf("other"), AccountStore.accounts.value.map { it.username })
        assertFalse("the remaining account is signed in instead of the login screen", rig.vm.needsLogin.value)
    }

    @Test fun loggingOutTheLastAccount_showsTheLogin() = runBlocking {
        AccountStore.accounts.value = listOf(SavedAccount("only", "Only", null, mapOf("sp_dc" to "a")))
        SessionHolder.username = "only"

        rig.vm.logout(mockk(relaxed = true))?.join()

        assertEquals(emptyList<String>(), AccountStore.accounts.value.map { it.username })
        assertTrue(rig.vm.needsLogin.value)
    }
}
