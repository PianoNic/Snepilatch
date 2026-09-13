package ch.snepilatch.app.viewmodel

import ch.snepilatch.app.data.TrackInfo
import ch.snepilatch.app.logic.playback.MusicPlaybackService
import ch.snepilatch.app.logic.playback.OfflinePlayer
import io.mockk.every
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Issue #791: offline, a track played to the end and playback stopped, since there was no queue.
 * The list the user tapped from is the queue now: the sheet shows it, track end advances, and at
 * the end playback stops on the last track rather than wandering into unrelated downloads.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OfflineQueueTest {

    private val rig = PlaybackTestRig()
    private lateinit var localFile: (String, String, String) -> String?
    private lateinit var service: () -> MusicPlaybackService?

    private val tracks = listOf("one", "two", "three").map {
        TrackInfo(uri = "spotify:track:$it", name = it, artist = "A", albumArt = null, durationMs = 100_000L)
    }

    @Before
    fun setUp() {
        rig.install()
        rig.vm.isOffline.value = true
        localFile = OfflinePlayer.localFile
        service = OfflinePlayer.service
        OfflinePlayer.localFile = { _, _, _ -> "content://tree/Music/file.opus" }
        OfflinePlayer.service = { rig.service }
        every { rig.service.getCurrentPosition() } returns 0L
        runBlocking { OfflinePlayer.play(tracks, 0) }
        awaitUntil { current() == "spotify:track:one" }
    }

    @After
    fun tearDown() {
        OfflinePlayer.clear()
        OfflinePlayer.localFile = localFile
        OfflinePlayer.service = service
        rig.uninstall()
    }

    private fun current() = rig.vm.playback.value.track?.uri
    private fun queueUris() = rig.vm.queue.value.map { it.uri.removePrefix("spotify:track:") }

    @Test
    fun theSheetShowsWhatIsStillToCome() {
        assertEquals(listOf("two", "three"), queueUris())
        assertEquals("spotify:track:two", rig.vm.nextTrackPreview.value?.uri)
        assertEquals(0, rig.vm.queuedCount.value)
    }

    @Test
    fun trackEnd_advancesAndStopsAtTheEnd() {
        assertTrue(runBlocking { OfflinePlayer.ended() })
        assertTrue(awaitUntil { current() == "spotify:track:two" })
        assertTrue(runBlocking { OfflinePlayer.ended() })
        assertTrue(awaitUntil { current() == "spotify:track:three" })
        assertEquals(emptyList<String>(), queueUris())

        assertFalse(runBlocking { OfflinePlayer.ended() })

        assertTrue(awaitUntil { rig.vm.playback.value.isPaused })
        assertEquals("spotify:track:three", current())
    }

    @Test
    fun theStopAtTheEnd_isAnnounced() {
        runBlocking { OfflinePlayer.play(tracks, 2, contextUri = "spotify:playlist:p") }
        awaitUntil { current() == "spotify:track:three" }
        val messages = mutableListOf<Int>()
        val collector = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined).launch {
            rig.vm.errorMessage.collect { messages += it.id }
        }

        runBlocking { rig.vm.offline.trackEnded() }

        assertTrue(awaitUntil { messages.isNotEmpty() })
        assertEquals(listOf(ch.snepilatch.app.R.string.offline_playlist_ended), messages)
        collector.cancel()
    }

    @Test
    fun theDownloadsListEnding_saysSoToo() {
        runBlocking { OfflinePlayer.play(tracks, 2) }
        awaitUntil { current() == "spotify:track:three" }
        val messages = mutableListOf<Int>()
        val collector = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined).launch {
            rig.vm.errorMessage.collect { messages += it.id }
        }

        runBlocking { rig.vm.offline.trackEnded() }

        assertTrue(awaitUntil { messages.isNotEmpty() })
        assertEquals(listOf(ch.snepilatch.app.R.string.offline_downloads_ended), messages)
        collector.cancel()
    }

    @Test
    fun repeatContext_startsTheListOver() {
        rig.vm.cycleRepeat()
        assertTrue(awaitUntil { rig.vm.playback.value.repeatMode == "context" })
        runBlocking { OfflinePlayer.jumpTo(1) }
        awaitUntil { current() == "spotify:track:three" }

        runBlocking { OfflinePlayer.ended() }

        assertTrue(awaitUntil { current() == "spotify:track:one" })
        assertTrue(rig.vm.playback.value.isPlaying)
    }

    @Test
    fun repeatTrack_playsItAgain() {
        rig.vm.cycleRepeat()
        rig.vm.cycleRepeat()
        assertTrue(awaitUntil { rig.vm.playback.value.repeatMode == "track" })

        runBlocking { OfflinePlayer.ended() }

        assertEquals("spotify:track:one", current())
        assertTrue(rig.vm.playback.value.isPlaying)
    }

    @Test
    fun shuffle_keepsTheCurrentTrackAndRestoresTheOrderWhenOff() {
        rig.vm.toggleShuffle()
        assertTrue(awaitUntil { rig.vm.playback.value.isShuffling })
        assertEquals("spotify:track:one", current())
        assertEquals(setOf("two", "three"), queueUris().toSet())

        rig.vm.toggleShuffle()

        assertTrue(awaitUntil { !rig.vm.playback.value.isShuffling })
        assertEquals(listOf("two", "three"), queueUris())
    }

    @Test
    fun aTappedQueueRow_plays() {
        rig.vm.skipToQueueIndex(1)
        assertTrue(awaitUntil { current() == "spotify:track:three" })
        assertEquals(emptyList<String>(), queueUris())
    }

    @Test
    fun removeAndMove_editWhatIsStillToCome() {
        rig.vm.removeFromQueue(tracks[1])
        assertTrue(awaitUntil { queueUris() == listOf("three") })

        runBlocking { OfflinePlayer.play(tracks, 0) }
        awaitUntil { queueUris() == listOf("two", "three") }
        rig.vm.moveQueueEntry(tracks[2], 0)

        assertTrue(awaitUntil { queueUris() == listOf("three", "two") })
        assertFalse(rig.vm.playback.value.isPaused)
    }

    private fun awaitUntil(timeoutMs: Long = 2_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(10)
        }
        return condition()
    }
}
