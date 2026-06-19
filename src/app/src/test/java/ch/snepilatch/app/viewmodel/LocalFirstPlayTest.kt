package ch.snepilatch.app.viewmodel

import ch.snepilatch.app.data.TrackInfo
import ch.snepilatch.app.playback.SessionHolder
import io.mockk.coVerify
import io.mockk.mockk
import kotify.api.playerconnect.PlayerConnect
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Playing a tapped track must mirror the web player: the Connect "play" command carries the track's
 * uid so a context with the same track twice starts on the exact tapped occurrence (skip_to track_uid).
 * With no CDN resolver wired the local-first audio path short-circuits, but the command must still fire
 * with the uid forwarded.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LocalFirstPlayTest {

    private val rig = PlaybackTestRig()

    @Before
    fun setUp() {
        rig.install()
    }

    @After
    fun tearDown() {
        SessionHolder.player = null
        SessionHolder.cdnResolver = null
        rig.uninstall()
    }

    @Test
    fun playTrack_forwardsUidToConnectCommand() {
        val pc = mockk<PlayerConnect>(relaxed = true)
        SessionHolder.player = pc
        val track = TrackInfo(
            uri = "spotify:track:abc", name = "Song", artist = "Artist", albumArt = null, uid = "uid-42",
        )

        runBlocking { rig.vm.startUserPlayback(track, "spotify:playlist:p1", trackIndex = 7) }

        coVerify(exactly = 1) { pc.playTrack("spotify:track:abc", "spotify:playlist:p1", "uid-42", 7) }
    }

    @Test
    fun playTrack_uriOnlyOverload_sendsNullUid() {
        val pc = mockk<PlayerConnect>(relaxed = true)
        SessionHolder.player = pc

        runBlocking {
            rig.vm.startUserPlayback(TrackInfo(uri = "spotify:track:xyz", name = "", artist = "", albumArt = null), null)
        }

        coVerify(exactly = 1) { pc.playTrack("spotify:track:xyz", null, null, null) }
    }
}
