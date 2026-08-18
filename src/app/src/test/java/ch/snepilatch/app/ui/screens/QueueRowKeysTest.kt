package ch.snepilatch.app.ui.screens

import ch.snepilatch.app.data.TrackInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A repeated key throws inside a LazyColumn, which is how the Home feed crashed once already.
 * The queue can genuinely hold duplicates: a live autoplay queue was observed with two entries
 * sharing a uid and an iteration, so qid alone is not an identity.
 */
class QueueRowKeysTest {

    private fun track(qid: String?, uri: String = "spotify:track:x") =
        TrackInfo(uri = uri, name = "n", artist = "a", albumArt = null, qid = qid)

    @Test
    fun `distinct entries keep their qid`() {
        val keys = queueRowKeys(listOf(track("a:::0"), track("b:::0")))

        assertEquals(listOf("a:::0", "b:::0"), keys)
    }

    @Test
    fun `duplicate qids do not collide`() {
        val keys = queueRowKeys(listOf(track("a:::0"), track("a:::0"), track("a:::0")))

        assertEquals(keys.size, keys.toSet().size)
        assertEquals("a:::0", keys.first())
    }

    /** Entries with no qid still need a key, and blank or repeated uris were the Home crash. */
    @Test
    fun `entries without a qid fall back to uri and still stay unique`() {
        val keys = queueRowKeys(listOf(track(null, "spotify:track:same"), track(null, "spotify:track:same")))

        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun `a long queue of identical entries stays unique`() {
        val keys = queueRowKeys(List(50) { track("dup:::0") })

        assertEquals(50, keys.toSet().size)
    }

    /** A key must not move when rows above it are untouched, or the list rebuilds on every edit. */
    @Test
    fun `removing a row leaves the remaining keys unchanged`() {
        val queue = listOf(track("a:::0"), track("b:::0"), track("c:::0"))

        val before = queueRowKeys(queue)
        val after = queueRowKeys(queue.filterNot { it.qid == "a:::0" })

        assertTrue(after.all { it in before })
        assertEquals(listOf("b:::0", "c:::0"), after)
    }
}
