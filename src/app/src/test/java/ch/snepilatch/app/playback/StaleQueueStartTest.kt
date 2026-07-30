package ch.snepilatch.app.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Regression guard for a long gap between tracks. setNextUrl used to trim the queue from a fixed
 * index 1, which is only the first stale slot while the current track is item 0. After a gapless
 * auto-advance ExoPlayer leaves the played item in the playlist and moves the current index past it,
 * so the next pre-resolve deleted the track that was playing: STATE_ENDED fired 18ms after the
 * enqueue, the end-of-track grace then burned its full 5 seconds, and the forced advance landed on
 * an ad. Captured live at 10:08:15.174.
 */
class StaleQueueStartTest {

    @Test
    fun trimsFromTheSlotAfterTheCurrentTrack() {
        assertEquals(1, staleQueueStart(currentIndex = 0, itemCount = 2))
        assertEquals(2, staleQueueStart(currentIndex = 1, itemCount = 3))
    }

    @Test
    fun keepsThePlayingTrackAfterAGaplessAdvance() {
        // The bug: current is item 1, the only queued item, and trimming from 1 removed it.
        assertNull(staleQueueStart(currentIndex = 1, itemCount = 2))
        assertNull(staleQueueStart(currentIndex = 2, itemCount = 3))
    }

    @Test
    fun trimsNothingWhenTheCurrentTrackIsAlone() {
        assertNull(staleQueueStart(currentIndex = 0, itemCount = 1))
        assertNull(staleQueueStart(currentIndex = 0, itemCount = 0))
    }
}
