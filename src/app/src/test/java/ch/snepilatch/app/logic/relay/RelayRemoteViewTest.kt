package ch.snepilatch.app.logic.relay

import ch.snepilatch.app.viewmodel.PlaybackTestRig
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** A remote-only guest's header names the host's context, not this account's last one (#1033). */
class RelayRemoteViewTest {
    private val rig = PlaybackTestRig()

    @Before
    fun setUp() = rig.install()

    @After
    fun tearDown() = rig.uninstall()

    private fun playback(contextUri: String?) = RelayPlayback(
        trackUri = null, contextUri = contextUri, positionMs = 0, durationMs = 0,
        paused = false, shuffle = false, repeat = "off", queue = emptyList(),
    )

    /** Rows without a qid, so nothing can address them in this account's own queue (#1086). */
    @Test
    fun showsTheHostsQueue() = runTest {
        val view = RelayRemoteView(rig.vm) { 0L }
        rig.vm._queue.value = listOf(ch.snepilatch.app.data.TrackInfo("spotify:track:mine", "mine", "me", null, qid = "m:::0"))
        val queue = listOf(RelayQueueEntry("spotify:track:one"), RelayQueueEntry("spotify:track:two"))

        view.show(playback(null).copy(queue = queue))

        assertEquals(listOf("spotify:track:one", "spotify:track:two"), rig.vm.queue.value.map { it.uri })
        assertEquals(listOf(null, null), rig.vm.queue.value.map { it.qid })
        assertEquals("spotify:track:one", rig.vm.nextTrackPreview.value?.uri)
    }

    @Test
    fun followsTheHostsContext() = runTest {
        val view = RelayRemoteView(rig.vm) { 0L }
        rig.vm.playingContext.value = ch.snepilatch.app.viewmodel.PlaybackViewModel.PlayingContext("Album", "Mine", "spotify:album:mine")

        view.show(playback("spotify:collection:tracks"))
        assertEquals("spotify:collection:tracks", rig.vm.playingContext.value?.uri)

        view.show(playback(null))
        assertNull(rig.vm.playingContext.value)
    }
}
