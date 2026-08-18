package ch.snepilatch.app.viewmodel

import ch.snepilatch.app.playback.SessionHolder
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A recreated Activity gets a fresh ViewModel but the session is process-scoped (issue #612).
 *
 * initialize() used to clear the holder and re-run the whole network cascade regardless, so every
 * teardown cost a full re-init and a slow one could never finish before the next teardown.
 */
class InitAdoptsRunningSessionTest {

    private val rig = PlaybackTestRig()

    @Before
    fun setUp() {
        rig.install()
        SessionHolder.session = mockk(relaxed = true)
        SessionHolder.cdnResolver = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        SessionHolder.session = null
        SessionHolder.cdnResolver = null
        rig.uninstall()
    }

    @Test
    fun initializeKeepsALiveSessionInsteadOfRebuildingIt() {
        val live = SessionHolder.session
        val livePlayer = SessionHolder.player

        rig.vm.initialize(mapOf("sp_dc" to "cookie"))

        assertSame("a ready session was torn down and re-created", live, SessionHolder.session)
        assertSame("a ready player was torn down and re-created", livePlayer, SessionHolder.player)
        assertTrue("adopting left the UI waiting on the connecting screen", rig.vm.isInitialized.value)
    }

    @Test
    fun aHalfBuiltHolderIsNotAdopted() {
        // isReady needs all three. With the resolver missing this must take the real init path
        // rather than adopt a session that cannot resolve a stream.
        SessionHolder.cdnResolver = null

        rig.vm.initialize(mapOf("sp_dc" to "cookie"))

        assertTrue("a half-built holder was adopted as if it were ready", !rig.vm.isInitialized.value)
    }
}
