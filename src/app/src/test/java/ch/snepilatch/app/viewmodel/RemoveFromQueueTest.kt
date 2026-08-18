package ch.snepilatch.app.viewmodel

import ch.snepilatch.app.data.TrackInfo
import io.mockk.coVerify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Swiping a queue row away (issue #586).
 *
 * The row goes immediately so the gesture feels like it did something, and the entry is addressed by
 * qid rather than uri because a queue legitimately holds the same track twice.
 */
class RemoveFromQueueTest {

    private val rig = PlaybackTestRig()

    @Before
    fun setUp() = rig.install()

    @After
    fun tearDown() = rig.uninstall()

    private fun track(qid: String?, name: String) =
        TrackInfo(uri = "spotify:track:$name", name = name, artist = "artist", albumArt = null, qid = qid)

    @Test
    fun dropsTheRowAndAddressesItByQid() {
        rig.vm._queue.value = listOf(track("a:::0", "one"), track("b:::0", "two"))

        rig.vm.removeFromQueue(rig.vm.queue.value.first())

        assertEquals(listOf("two"), rig.vm.queue.value.map { it.name })
        coVerify { rig.player.removeFromQueue("a:::0") }
    }

    /** Same uid on the next repeat pass, so a uri or uid match would take the wrong row. */
    @Test
    fun removesOnlyTheSwipedIterationOfADuplicate() {
        rig.vm._queue.value = listOf(track("a:::0", "dup"), track("a:::1", "dup"))

        rig.vm.removeFromQueue(rig.vm.queue.value.first())

        assertEquals(listOf("a:::1"), rig.vm.queue.value.map { it.qid })
    }

    /** The header split is driven by this count, so removing a queued row has to move it too. */
    @Test
    fun removingAQueuedRowShrinksTheQueuedSection() {
        rig.vm._queue.value = listOf(track("q:::0", "queued"), track("c:::0", "context"))
        rig.vm._queuedCount.value = 1

        rig.vm.removeFromQueue(rig.vm.queue.value.first())

        assertEquals(0, rig.vm.queuedCount.value)
    }

    @Test
    fun removingAContextRowLeavesTheQueuedCountAlone() {
        rig.vm._queue.value = listOf(track("q:::0", "queued"), track("c:::0", "context"))
        rig.vm._queuedCount.value = 1

        rig.vm.removeFromQueue(rig.vm.queue.value[1])

        assertEquals(1, rig.vm.queuedCount.value)
    }

    /** Without a qid there is nothing to address, so the row stays rather than vanishing locally. */
    @Test
    fun anEntryWithoutAQidIsLeftAlone() {
        rig.vm._queue.value = listOf(track(null, "orphan"))

        rig.vm.removeFromQueue(rig.vm.queue.value.first())

        assertEquals(1, rig.vm.queue.value.size)
        coVerify(exactly = 0) { rig.player.removeFromQueue(any()) }
    }
}
