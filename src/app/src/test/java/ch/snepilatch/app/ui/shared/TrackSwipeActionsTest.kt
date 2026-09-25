package ch.snepilatch.app.ui.shared

import androidx.compose.foundation.gestures.DraggableAnchors
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
    fun activationDependsOnDistanceAndAvailableAnchors() {
        val anchors = DraggableAnchors {
            SwipeAnchor.EndToStart at -320f
            SwipeAnchor.Settled at 0f
            SwipeAnchor.StartToEnd at 320f
        }

        assertEquals(SwipeAnchor.Settled, swipeTarget(95f, 96f, anchors))
        assertEquals(SwipeAnchor.StartToEnd, swipeTarget(96f, 96f, anchors))
        assertEquals(SwipeAnchor.Settled, swipeTarget(-95f, 96f, anchors))
        assertEquals(SwipeAnchor.EndToStart, swipeTarget(-96f, 96f, anchors))

        val startOnly = DraggableAnchors {
            SwipeAnchor.Settled at 0f
            SwipeAnchor.StartToEnd at 320f
        }
        assertEquals(SwipeAnchor.Settled, swipeTarget(-200f, 96f, startOnly))
    }

    @Test
    fun semanticRevealEdgeTracksLayoutDirection() {
        assertTrue(revealFromLeft(fromStart = true, isLtr = true))
        assertFalse(revealFromLeft(fromStart = false, isLtr = true))
        assertFalse(revealFromLeft(fromStart = true, isLtr = false))
        assertTrue(revealFromLeft(fromStart = false, isLtr = false))
    }
}
