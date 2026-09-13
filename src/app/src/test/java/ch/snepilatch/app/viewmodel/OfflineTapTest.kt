package ch.snepilatch.app.viewmodel

import ch.snepilatch.app.data.TrackInfo
import ch.snepilatch.app.logic.playback.MusicPlaybackService
import ch.snepilatch.app.logic.playback.OfflinePlayer
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
 * Issue #789: offline, a tapped download started the audio and nothing else, so the player UI that
 * keys off the current track never appeared. The offline engine's state has to reach the one
 * playback state the screens read.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OfflineTapTest {

    private val rig = PlaybackTestRig()
    private lateinit var localFile: (String, String, String) -> String?
    private lateinit var service: () -> MusicPlaybackService?

    @Before
    fun setUp() {
        rig.install()
        rig.vm.isOffline.value = true
        localFile = OfflinePlayer.localFile
        service = OfflinePlayer.service
        OfflinePlayer.service = { rig.service }
    }

    @After
    fun tearDown() {
        OfflinePlayer.clear()
        OfflinePlayer.localFile = localFile
        OfflinePlayer.service = service
        rig.uninstall()
    }

    private val track =
        TrackInfo(uri = "spotify:track:dl", name = "Fatal", artist = "GEMN", albumArt = null, durationMs = 183_000L)

    @Test
    fun tappingADownload_publishesItAsThePlayingTrack() {
        OfflinePlayer.localFile = { _, _, _ -> "content://tree/Music/fatal.opus" }

        runBlocking { rig.vm.startUserPlayback(track, contextUri = null) }

        val state = rig.vm.playback.value
        assertEquals("spotify:track:dl", state.track?.uri)
        assertTrue(state.isPlaying)
        assertFalse(state.isPaused)
        assertEquals(183_000L, state.durationMs)
        assertEquals("Local", rig.vm.streamProvider.value)
    }

    @Test
    fun theFilesRealLength_replacesTheIndexedOne() {
        OfflinePlayer.localFile = { _, _, _ -> "content://tree/Music/fatal.opus" }
        runBlocking { rig.vm.startUserPlayback(track.copy(durationMs = 0L), contextUri = null) }

        OfflinePlayer.durationKnown(184_250L)

        assertEquals(184_250L, rig.vm.playback.value.durationMs)
    }

    @Test
    fun aTrackThatRanOut_showsAsPaused() {
        OfflinePlayer.localFile = { _, _, _ -> "content://tree/Music/fatal.opus" }
        runBlocking { rig.vm.startUserPlayback(track, contextUri = null) }

        OfflinePlayer.ended()

        assertFalse(rig.vm.playback.value.isPlaying)
        assertTrue(rig.vm.playback.value.isPaused)
        assertEquals("spotify:track:dl", rig.vm.playback.value.track?.uri)
    }

    @Test
    fun aTrackWithoutALocalCopy_isNotClaimed() {
        OfflinePlayer.localFile = { _, _, _ -> null }

        runBlocking { rig.vm.startUserPlayback(track, contextUri = null) }

        assertNull(rig.vm.playback.value.track)
        assertNull(rig.vm.streamProvider.value)
    }
}
