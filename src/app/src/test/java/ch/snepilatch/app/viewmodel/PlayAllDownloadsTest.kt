package ch.snepilatch.app.viewmodel

import ch.snepilatch.app.data.TrackInfo
import ch.snepilatch.app.logic.playback.MusicPlaybackService
import ch.snepilatch.app.logic.playback.OfflinePlayer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Play all stays on the files while online (#895): the engine owns playback, Connect hears nothing of it. */
class PlayAllDownloadsTest {
    private val rig = PlaybackTestRig()
    private lateinit var localFile: (String, String, String) -> String?
    private lateinit var service: () -> MusicPlaybackService?
    private val tracks = listOf("one", "two", "three").map {
        TrackInfo(uri = "spotify:track:$it", name = it, artist = "A", albumArt = null, durationMs = 100_000L)
    }

    @Before
    fun setUp() {
        rig.install()
        localFile = OfflinePlayer.localFile
        service = OfflinePlayer.service
        OfflinePlayer.localFile = { _, _, _ -> "content://tree/Music/file.opus" }
        OfflinePlayer.service = { rig.service }
    }

    @After
    fun tearDown() {
        OfflinePlayer.clear()
        OfflinePlayer.localFile = localFile
        OfflinePlayer.service = service
        rig.uninstall()
    }

    @Test
    fun onlineTheEngineOwnsPlaybackInOrder() = runBlocking {
        rig.vm.startDownloadsPlayback(tracks, shuffle = false)

        assertTrue(rig.vm.localPlayback.value)
        assertEquals(tracks, OfflinePlayer.state.value?.tracks)
        assertEquals(0, OfflinePlayer.state.value?.index)
    }

    @Test
    fun shuffleKeepsEveryTrack() = runBlocking {
        rig.vm.startDownloadsPlayback(tracks, shuffle = true)

        assertEquals(true, OfflinePlayer.state.value?.shuffle)
        assertEquals(tracks.toSet(), OfflinePlayer.state.value?.tracks?.toSet())
    }

    /** A Free account's server restrictions are about Connect, not the files. */
    @Test
    fun theEngineLiftsTheServersRestrictions() = runBlocking {
        rig.vm._playback.value = rig.vm._playback.value.copy(canToggleShuffle = false, canToggleRepeatContext = false)

        rig.vm.startDownloadsPlayback(tracks, shuffle = false)

        var waited = 0
        while (!rig.vm.playback.value.canToggleShuffle && waited++ < 200) kotlinx.coroutines.delay(10)
        assertTrue(rig.vm.playback.value.canToggleShuffle)
        assertTrue(rig.vm.playback.value.canToggleRepeatContext)
    }

    @Test
    fun aFileThatIsGoneLeavesConnectInCharge() = runBlocking {
        OfflinePlayer.localFile = { _, _, _ -> null }

        rig.vm.startDownloadsPlayback(tracks, shuffle = false)

        assertFalse(rig.vm.localPlayback.value)
    }

    @Test
    fun offlineTheFlagStaysOffSoTheSignalCanHandBack() = runBlocking {
        rig.vm.isOffline.value = true

        rig.vm.startDownloadsPlayback(tracks, shuffle = false)

        assertFalse(rig.vm.localPlayback.value)
        assertEquals(tracks, OfflinePlayer.state.value?.tracks)
    }
}
