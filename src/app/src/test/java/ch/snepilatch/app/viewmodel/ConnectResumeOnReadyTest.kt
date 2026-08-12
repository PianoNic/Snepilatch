package ch.snepilatch.app.viewmodel

import ch.snepilatch.app.data.TrackInfo
import ch.snepilatch.app.playback.SessionHolder
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
 * A downloaded copy is ready within ~250ms of a tap, long before Connect has switched tracks. The
 * ready callback used to resume Connect unconditionally, which restarted the previously playing
 * track on the cluster; it then played on and advanced, dragging this device off the tapped song.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectResumeOnReadyTest {

    private val rig = PlaybackTestRig()

    @Before
    fun setUp() {
        rig.install()
    }

    @After
    fun tearDown() {
        SessionHolder.player = null
        AppSettings.preferredAudioSource.value = null
        rig.uninstall()
    }

    @Test
    fun aStreamWeLoadedOurselvesStillResumesConnect() {
        AppSettings.preferredAudioSource.value = null
        assertTrue(rig.vm.shouldResumeConnectOnReady())
    }

    @Test
    fun aTapStillInFlightDoesNotResumeConnect() {
        SessionHolder.player = mockk<PlayerConnect>(relaxed = true)
        AppSettings.preferredAudioSource.value = null

        runBlocking {
            rig.vm.startUserPlayback(
                TrackInfo(uri = "spotify:track:dl", name = "Fatal", artist = "GEMN", albumArt = null),
                contextUri = null,
            )
        }

        assertFalse(
            "playTrack starts Connect itself; resuming here restarts the old track",
            rig.vm.shouldResumeConnectOnReady()
        )
    }
}
