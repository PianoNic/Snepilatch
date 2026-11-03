package ch.snepilatch.app.logic.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class RecentPlaysTest {
    @Test
    fun aReplayMovesToTheFrontWithoutADuplicate() {
        assertEquals(listOf("b", "a", "c"), moveToFront(listOf("a", "b", "c"), "b", 50))
    }

    @Test
    fun theOldestFallsOff() {
        assertEquals(listOf("d", "a", "b"), moveToFront(listOf("a", "b", "c"), "d", 3))
    }
}
