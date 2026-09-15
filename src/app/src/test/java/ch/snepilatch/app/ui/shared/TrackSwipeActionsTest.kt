package ch.snepilatch.app.ui.shared

import androidx.compose.material3.SwipeToDismissBoxValue
import ch.snepilatch.app.data.PlayerShortcut
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which shortcut a swipe runs, and which shortcuts a row may be given at all. */
class TrackSwipeActionsTest {

    @Test
    fun eachDirectionRunsItsOwnShortcutAndSettledRunsNothing() {
        val end = PlayerShortcut.ADD_TO_PLAYLIST
        val start = PlayerShortcut.ADD_TO_QUEUE
        assertEquals(end, swipeActionFor(SwipeToDismissBoxValue.EndToStart, end, start))
        assertEquals(start, swipeActionFor(SwipeToDismissBoxValue.StartToEnd, end, start))
        assertNull(swipeActionFor(SwipeToDismissBoxValue.Settled, end, start))
    }

    @Test
    fun rowsOnlyOfferWhatActsOnTheirOwnTrack() {
        val perTrack = PlayerShortcut.perTrack
        assertTrue(perTrack.containsAll(listOf(PlayerShortcut.ADD_TO_QUEUE, PlayerShortcut.ADD_TO_PLAYLIST, PlayerShortcut.SHARE)))
        for (excluded in listOf(PlayerShortcut.LIKE, PlayerShortcut.LYRICS, PlayerShortcut.QUEUE, PlayerShortcut.JAM)) {
            assertFalse(excluded in perTrack)
        }
    }

    @Test
    fun aStoredShortcutThatRowsNoLongerOfferFallsBack() {
        assertEquals(PlayerShortcut.ADD_TO_QUEUE, PlayerShortcut.perTrackFromId("like", PlayerShortcut.ADD_TO_QUEUE))
        assertEquals(PlayerShortcut.SHARE, PlayerShortcut.perTrackFromId("share", PlayerShortcut.ADD_TO_QUEUE))
    }
}
