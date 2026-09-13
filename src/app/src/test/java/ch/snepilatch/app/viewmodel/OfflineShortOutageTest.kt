package ch.snepilatch.app.viewmodel

import ch.snepilatch.app.data.PlaybackUiState
import ch.snepilatch.app.data.TrackInfo
import ch.snepilatch.app.logic.download.Downloads
import ch.snepilatch.app.logic.playback.MusicPlaybackService
import ch.snepilatch.app.logic.playback.OfflineController
import ch.snepilatch.app.logic.playback.OfflinePlayer
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Issue #801: a short outage on the same track must hand nothing back with a play command.
 * Connect still has this phone on that track, so the dealer's return simply ends offline mode.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OfflineShortOutageTest {

    private class FakeHooks : OfflineController.Hooks {
        override val playback = MutableStateFlow(PlaybackUiState())
        override val queue = MutableStateFlow<List<TrackInfo>>(emptyList())
        override val queuedCount = MutableStateFlow(0)
        override val nextPreview = MutableStateFlow<TrackInfo?>(null)
        override val prevPreview = MutableStateFlow<TrackInfo?>(null)
        override val isOffline = MutableStateFlow(false)
        val playCommands = mutableListOf<String>()
        val reports = mutableListOf<Pair<Long, Boolean>>()
        override fun hasSession() = true
        override fun playingContextUri(): String? = "spotify:playlist:p"
        override fun trackChanged(track: TrackInfo) = Unit
        override fun setTickerRunning(running: Boolean) = Unit
        override fun startSession() = Unit
        override suspend fun awaitPlayer(timeoutMs: Long) = true
        override suspend fun currentPositionMs() = 42_000L
        override suspend fun handBackToConnect(track: TrackInfo, contextUri: String?, positionMs: Long, paused: Boolean) {
            playCommands += track.uri
        }
        override suspend fun reportToConnect(positionMs: Long, paused: Boolean) {
            reports += positionMs to paused
        }
    }

    private val hooks = FakeHooks()
    private val controller = OfflineController(CoroutineScope(Dispatchers.Unconfined), hooks)
    private val playing =
        TrackInfo(uri = "spotify:track:playing", name = "P", artist = "A", albumArt = null, durationMs = 100_000L)
    private val next =
        TrackInfo(uri = "spotify:track:next", name = "N", artist = "A", albumArt = null, durationMs = 100_000L)
    private lateinit var localFile: (String, String, String) -> String?
    private lateinit var service: () -> MusicPlaybackService?

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        mockkObject(Downloads)
        every { Downloads.index } returns MutableStateFlow(setOf("spotify:track:next"))
        every { Downloads.isDownloaded(any(), any(), any(), any()) } answers { secondArg<String>() == "spotify:track:next" }
        every { Downloads.rows } returns MutableStateFlow(emptyList())
        hooks.playback.value =
            PlaybackUiState(track = playing, isPlaying = true, isPaused = false, positionMs = 30_000L, durationMs = 100_000L)
        hooks.queue.value = listOf(next)
        localFile = OfflinePlayer.localFile
        service = OfflinePlayer.service
        OfflinePlayer.localFile = { _, _, _ -> "content://tree/Music/file.opus" }
        OfflinePlayer.service = { null }
    }

    @After
    fun tearDown() {
        OfflinePlayer.clear()
        OfflinePlayer.localFile = localFile
        OfflinePlayer.service = service
        unmockkObject(Downloads)
        Dispatchers.resetMain()
    }

    /** The report goes out on IO; wait for it rather than reading the list the moment the call returns. */
    private fun awaitReports(): List<Pair<Long, Boolean>> {
        val deadline = System.currentTimeMillis() + 2_000
        while (hooks.reports.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        return hooks.reports.toList()
    }

    @Test
    fun dealerBackOnTheSameTrack_endsOfflineModeWithoutAPlayCommand() {
        controller.takeOver()
        assertTrue(hooks.isOffline.value)

        controller.onDealerReconnected()

        assertFalse(hooks.isOffline.value)
        assertNull(OfflinePlayer.state.value)
        assertEquals(emptyList<String>(), hooks.playCommands)
        assertEquals(listOf(42_000L to false), awaitReports())
    }

    @Test
    fun pausedDuringTheOutage_isReportedPaused() {
        controller.takeOver()
        runBlocking { OfflinePlayer.togglePlayPause() }

        controller.onDealerReconnected()

        assertEquals(listOf(42_000L to true), awaitReports())
    }

    @Test
    fun movedOnDuringTheOutage_staysOfflineForTheFullWayBack() {
        controller.takeOver()
        runBlocking { OfflinePlayer.next() }
        assertEquals("spotify:track:next", OfflinePlayer.state.value?.current?.uri)

        controller.onDealerReconnected()

        assertTrue(hooks.isOffline.value)
        assertEquals(emptyList<String>(), hooks.playCommands)
        Thread.sleep(200)
        assertEquals(emptyList<Pair<Long, Boolean>>(), hooks.reports)
    }
}
