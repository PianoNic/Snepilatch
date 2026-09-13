package ch.snepilatch.app.viewmodel

import ch.snepilatch.app.data.TrackInfo
import ch.snepilatch.app.logic.playback.MusicPlaybackService
import ch.snepilatch.app.logic.playback.OfflinePlayer
import io.mockk.every
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Issue #790: offline, every transport call used to start with "get the Connect player, or
 * return", so the buttons did nothing. They go to the offline engine now.
 *
 * The view model hands the calls to the engine on IO, so the checks wait for the state to land
 * rather than reading it the moment the call returns.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OfflineTransportTest {

    private val rig = PlaybackTestRig()
    private lateinit var localFile: (String, String, String) -> String?
    private lateinit var service: () -> MusicPlaybackService?

    private val first =
        TrackInfo(uri = "spotify:track:one", name = "One", artist = "A", albumArt = null, durationMs = 100_000L)
    private val second =
        TrackInfo(uri = "spotify:track:two", name = "Two", artist = "A", albumArt = null, durationMs = 120_000L)

    @Before
    fun setUp() {
        rig.install()
        rig.vm.isOffline.value = true
        localFile = OfflinePlayer.localFile
        service = OfflinePlayer.service
        OfflinePlayer.localFile = { _, _, _ -> "content://tree/Music/file.opus" }
        OfflinePlayer.service = { rig.service }
        every { rig.service.getCurrentPosition() } returns 0L
        runBlocking { OfflinePlayer.play(listOf(first, second), 0) }
        awaitUntil { rig.vm.playback.value.track?.uri == "spotify:track:one" }
    }

    @After
    fun tearDown() {
        OfflinePlayer.clear()
        OfflinePlayer.localFile = localFile
        OfflinePlayer.service = service
        rig.uninstall()
    }

    @Test
    fun togglePausesAndResumesTheLocalPlayer() {
        rig.vm.togglePlayPause()
        verify(timeout = 2_000) { rig.service.syncPause() }
        awaitUntil { rig.vm.playback.value.isPaused }
        assertTrue(rig.vm.playback.value.isPaused)

        rig.vm.togglePlayPause()
        verify(timeout = 2_000) { rig.service.syncPlay(any()) }
        awaitUntil { rig.vm.playback.value.isPlaying }
        assertFalse(rig.vm.playback.value.isPaused)
    }

    @Test
    fun seekMovesTheLocalPlayerAndTheState() {
        rig.vm.seekTo(42_000L)
        verify(timeout = 2_000) { rig.service.syncSeek(42_000L) }
        assertEquals(42_000L, rig.vm.playback.value.positionMs)
    }

    @Test
    fun nextPlaysTheFollowingTrackOfTheList() {
        rig.vm.skipNext()
        awaitUntil { rig.vm.playback.value.track?.uri == "spotify:track:two" }
        assertEquals(120_000L, rig.vm.playback.value.durationMs)
    }

    @Test
    fun nextAtTheEndOfTheList_changesNothing() {
        rig.vm.skipNext()
        awaitUntil { rig.vm.playback.value.track?.uri == "spotify:track:two" }

        rig.vm.skipNext()

        assertFalse(awaitUntil(timeoutMs = 300) { rig.vm.playback.value.track?.uri != "spotify:track:two" })
        assertEquals(2, OfflinePlayer.state.value?.tracks?.size)
        assertEquals(1, OfflinePlayer.state.value?.index)
    }

    @Test
    fun previousEarlyInATrack_goesBackOne() {
        rig.vm.skipNext()
        awaitUntil { rig.vm.playback.value.track?.uri == "spotify:track:two" }
        every { rig.service.getCurrentPosition() } returns 1_000L

        rig.vm.skipPrevious()

        assertTrue(awaitUntil { rig.vm.playback.value.track?.uri == "spotify:track:one" })
    }

    @Test
    fun previousLateInATrack_restartsIt() {
        rig.vm.skipNext()
        awaitUntil { rig.vm.playback.value.track?.uri == "spotify:track:two" }
        every { rig.service.getCurrentPosition() } returns 30_000L

        rig.vm.skipPrevious()

        verify(timeout = 2_000) { rig.service.syncSeek(0L) }
        assertEquals("spotify:track:two", rig.vm.playback.value.track?.uri)
    }

    /** True once [condition] holds, false when [timeoutMs] passed first. */
    private fun awaitUntil(timeoutMs: Long = 2_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(10)
        }
        return condition()
    }
}
