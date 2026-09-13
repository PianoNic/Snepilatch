package ch.snepilatch.app.viewmodel

import ch.snepilatch.app.data.PlaybackUiState
import ch.snepilatch.app.data.TrackInfo
import ch.snepilatch.app.logic.download.DownloadedTrack
import ch.snepilatch.app.logic.download.Downloads
import ch.snepilatch.app.logic.playback.OfflinePlayer
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Issue #792: the signal goes while listening online. The engine takes over in place: the playing
 * track stays, what of the queue and its context is on the phone follows, the rest is dropped.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OfflineTakeoverTest {

    private val rig = PlaybackTestRig()

    private fun track(id: String) = TrackInfo(uri = "spotify:track:$id", name = id, artist = "A", albumArt = null, durationMs = 100_000L)

    private fun row(id: String, context: String?) = DownloadedTrack(
        trackUri = "spotify:track:$id", documentUri = "content://$id", source = "ytm", provider = null, mimeType = null,
        coverUrl = null, contextUri = context, contextName = null, contextType = null, sizeBytes = 1L,
        title = id, artist = "A", downloadedAt = 0L, durationMs = 100_000L,
    )

    @Before
    fun setUp() {
        rig.install()
        mockkObject(Downloads)
        val downloaded = setOf("spotify:track:playing", "spotify:track:b", "spotify:track:d", "spotify:track:e")
        every { Downloads.index } returns MutableStateFlow(downloaded)
        every { Downloads.isDownloaded(any(), any(), any(), any()) } answers { secondArg<String>() in downloaded }
        every { Downloads.rows } returns MutableStateFlow(
            listOf(row("e", "spotify:playlist:p"), row("d", "spotify:playlist:p"), row("b", "spotify:playlist:p"), row("z", "spotify:album:other"))
        )
        rig.vm._playback.value = PlaybackUiState(track = track("playing"), isPlaying = true, isPaused = false, positionMs = 42_000L, durationMs = 100_000L)
        rig.vm._queue.value = listOf(track("a"), track("b"), track("c"), track("d"))
        rig.vm.playingContext.value = PlaybackViewModel.PlayingContext(type = "playlist", name = "P", uri = "spotify:playlist:p")
    }

    @After
    fun tearDown() {
        OfflinePlayer.clear()
        unmockkObject(Downloads)
        rig.uninstall()
    }

    @Test
    fun takeover_keepsThePlayingTrackAndWhatIsOnThePhone() {
        rig.vm.offline.takeOver()

        assertTrue(rig.vm.isOffline.value)
        val state = OfflinePlayer.state.value!!
        assertEquals("spotify:track:playing", state.current?.uri)
        // The queue's downloaded entries in queue order, then the context's other downloads.
        assertEquals(listOf("b", "d", "e"), state.upcoming.map { it.uri.removePrefix("spotify:track:") })
        assertTrue(state.isPlaying)
    }

    @Test
    fun takeover_leavesTheAudioAndPositionAlone() {
        rig.vm.offline.takeOver()

        val p = rig.vm.playback.value
        assertEquals("spotify:track:playing", p.track?.uri)
        assertEquals(42_000L, p.positionMs)
        assertTrue(p.isPlaying)
        assertFalse(p.isPaused)
        assertEquals(listOf("b", "d", "e"), rig.vm.queue.value.map { it.uri.removePrefix("spotify:track:") })
    }

    @Test
    fun takeover_withNothingOfTheContextOnThePhone_stopsAfterTheTrack() {
        every { Downloads.isDownloaded(any(), any(), any(), any()) } returns false
        every { Downloads.rows } returns MutableStateFlow(emptyList())

        rig.vm.offline.takeOver()

        assertEquals(emptyList<TrackInfo>(), OfflinePlayer.state.value?.upcoming)
        assertEquals("spotify:track:playing", OfflinePlayer.state.value?.current?.uri)
    }

    @Test
    fun takeover_whilePaused_staysPaused() {
        rig.vm._playback.value = rig.vm._playback.value.copy(isPlaying = false, isPaused = true)

        rig.vm.offline.takeOver()

        assertFalse(OfflinePlayer.state.value!!.isPlaying)
        assertTrue(rig.vm.playback.value.isPaused)
    }
}
