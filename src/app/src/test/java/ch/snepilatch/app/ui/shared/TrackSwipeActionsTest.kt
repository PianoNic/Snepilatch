package ch.snepilatch.app.ui.shared

import androidx.compose.material3.SwipeToDismissBoxValue
import ch.snepilatch.app.data.PlayerShortcut
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which shortcut a swipe runs, and which shortcuts a row may be given at all. */
class TrackSwipeActionsTest {

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

    @Test
    fun animationProgressClampsToItsDistance() {
        assertEquals(0f, swipeProgress(0f, 96f), 0f)
        assertEquals(0.5f, swipeProgress(48f, 96f), 0f)
        assertEquals(1f, swipeProgress(144f, 96f), 0f)
    }

    @Test
    fun revealOffsetStopsAtMaximumRevealDistanceInEitherDirection() {
        assertEquals(192f, boundedRevealOffset(240f, 192f), 0f)
        assertEquals(-192f, boundedRevealOffset(-240f, 192f), 0f)
    }

    @Test
    fun actionGateClaimsOnlyOnceUntilReset() {
        val gate = SwipeActionGate()

        assertFalse(gate.claim(SwipeToDismissBoxValue.Settled))
        assertTrue(gate.claim(SwipeToDismissBoxValue.StartToEnd))
        assertFalse(gate.claim(SwipeToDismissBoxValue.StartToEnd))
        assertFalse(gate.claim(SwipeToDismissBoxValue.EndToStart))

        gate.reset()

        assertTrue(gate.claim(SwipeToDismissBoxValue.EndToStart))
    }
}
