package ch.snepilatch.app.viewmodel

import kotify.api.playerstatus.QueueTrack
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * `next_tracks` is not the queue (issue #614). It is the queued block, a boundary, then the rest of
 * the context, and the sheet used to render all of it, markers and hidden entries included.
 */
class VisibleQueueTest {

    private val rig = PlaybackTestRig()

    @Before
    fun setUp() = rig.install()

    @After
    fun tearDown() = rig.uninstall()

    private fun track(
        uri: String = "spotify:track:a",
        metadata: Map<String, String> = emptyMap(),
        removed: List<String> = emptyList(),
    ) = QueueTrack(
        uri = uri, uid = "u", name = null, artistName = null, artistUri = null,
        albumName = null, albumUri = null, durationMs = 0L, imageUrl = null,
        metadata = metadata, removedReasons = removed,
    )

    @Test
    fun stopsAtTheBoundary() {
        val out = rig.vm.visibleQueue(
            listOf(track("spotify:track:1"), track(QueueTrack.DELIMITER_URI), track("spotify:track:2"))
        )

        assertEquals(listOf("spotify:track:1"), out.map { it.uri })
    }

    @Test
    fun metaEntriesAreABoundaryToo() {
        val out = rig.vm.visibleQueue(listOf(track("spotify:track:1"), track("spotify:meta:page:2"), track("spotify:track:2")))

        assertEquals(1, out.size)
    }

    @Test
    fun dropsWhatTheServerFlaggedAsNotDisplayable() {
        val out = rig.vm.visibleQueue(
            listOf(
                track("spotify:track:keep"),
                track("spotify:track:hidden", metadata = mapOf("hidden" to "true")),
                track("spotify:track:hiddenq", metadata = mapOf("hidden_in_queue" to "true")),
                track("spotify:track:removed", removed = listOf("banned")),
            )
        )

        assertEquals(listOf("spotify:track:keep"), out.map { it.uri })
    }

    /** Nothing to cut is the common case, and it must not lose entries. */
    @Test
    fun aPlainQueueSurvivesIntact() {
        val plain = listOf(track("spotify:track:1"), track("spotify:track:2"), track("spotify:track:3"))

        assertEquals(3, rig.vm.visibleQueue(plain).size)
    }
}
