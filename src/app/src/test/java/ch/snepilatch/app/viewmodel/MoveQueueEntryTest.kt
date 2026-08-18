package ch.snepilatch.app.viewmodel

import ch.snepilatch.app.data.TrackInfo
import io.mockk.coVerify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Dragging a queue row to a new position (issue #587).
 *
 * The trap this pins: the sheet hides the delimiter and any flagged entry, so a row's position on
 * screen is not the index the server wants. A live queue was observed carrying two hidden entries,
 * so passing the displayed index straight through would drop tracks in the wrong slot.
 */
class MoveQueueEntryTest {

    private val rig = PlaybackTestRig()

    @Before
    fun setUp() = rig.install()

    @After
    fun tearDown() = rig.uninstall()

    private fun row(qid: String, rawIndex: Int) = TrackInfo(
        uri = "spotify:track:$qid",
        name = qid,
        artist = "artist",
        albumArt = null,
        qid = qid,
        queueIndex = rawIndex,
    )

    @Test
    fun `sends the server index of the row landed on, not the row number`() {
        // Displayed rows 0,1,2 sit at server indices 0,3,4 because hidden entries fall between them.
        rig.vm._queue.value = listOf(row("a", 0), row("b", 3), row("c", 4))
        rig.vm._queuedCount.value = 3

        rig.vm.moveQueueEntry(rig.vm.queue.value[0], 2)

        coVerify { rig.player.moveInQueue("a", 4) }
    }

    @Test
    fun `reorders the list straight away`() {
        rig.vm._queue.value = listOf(row("a", 0), row("b", 1), row("c", 2))
        rig.vm._queuedCount.value = 3

        rig.vm.moveQueueEntry(rig.vm.queue.value[0], 2)

        assertEquals(listOf("b", "c", "a"), rig.vm.queue.value.map { it.qid })
    }

    /** The server refuses to move a queued entry into the context, so the local list must not either. */
    @Test
    fun `a queued row cannot be dragged past its section`() {
        rig.vm._queue.value = listOf(row("q1", 0), row("q2", 1), row("c1", 2), row("c2", 3))
        rig.vm._queuedCount.value = 2

        rig.vm.moveQueueEntry(rig.vm.queue.value[0], 3)

        assertEquals(listOf("q2", "q1", "c1", "c2"), rig.vm.queue.value.map { it.qid })
    }

    @Test
    fun `a context row cannot be dragged into the queued block`() {
        rig.vm._queue.value = listOf(row("q1", 0), row("c1", 1), row("c2", 2))
        rig.vm._queuedCount.value = 1

        rig.vm.moveQueueEntry(rig.vm.queue.value[2], 0)

        assertEquals(listOf("q1", "c2", "c1"), rig.vm.queue.value.map { it.qid })
    }

    @Test
    fun `dropping a row where it already sits writes nothing`() {
        rig.vm._queue.value = listOf(row("a", 0), row("b", 1))
        rig.vm._queuedCount.value = 2

        rig.vm.moveQueueEntry(rig.vm.queue.value[0], 0)

        assertEquals(listOf("a", "b"), rig.vm.queue.value.map { it.qid })
        coVerify(exactly = 0) { rig.player.moveInQueue(any(), any()) }
    }
}
