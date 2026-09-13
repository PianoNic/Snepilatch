package ch.snepilatch.app.viewmodel

import ch.snepilatch.app.data.TrackInfo
import io.mockk.coVerify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Issue #793: the network is back while the offline engine owns playback. Connect adopts the
 * playing track through a play command in its context, then the real position and pause state
 * go out over the local transport; nothing reloads the audio.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OfflineHandBackTest {

    private val rig = PlaybackTestRig()
    private val track =
        TrackInfo(uri = "spotify:track:x", name = "X", artist = "A", albumArt = null, durationMs = 100_000L)

    @Before
    fun setUp() {
        rig.install()
    }

    @After
    fun tearDown() {
        rig.uninstall()
    }

    @Test
    fun aPlayingTrack_isPlayedInItsContextAndSeekedToWhereItIs() {
        runBlocking { rig.vm.handBackToConnect(track, "spotify:playlist:p", 42_000L, paused = false) }

        coVerify { rig.player.playTrack("spotify:track:x", "spotify:playlist:p") }
        coVerify { rig.player.localSeek(42_000L) }
        coVerify(exactly = 0) { rig.player.localPause(any()) }
    }

    @Test
    fun aPausedTrack_isReportedPausedAfterTheSeek() {
        runBlocking { rig.vm.handBackToConnect(track, null, 7_000L, paused = true) }

        coVerify { rig.player.playTrack("spotify:track:x", null) }
        coVerify { rig.player.localSeek(7_000L) }
        coVerify { rig.player.localPause(7_000L) }
    }
}
