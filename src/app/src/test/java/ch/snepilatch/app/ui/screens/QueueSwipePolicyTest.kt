package ch.snepilatch.app.ui.screens

import androidx.compose.material3.SwipeToDismissBoxValue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueSwipePolicyTest {

    @Test
    fun contextRowsOfferAddButExplicitQueueRowsDoNot() {
        assertTrue(queueSwipePolicy(isContextEntry = true, canAddToQueue = true, dragging = false).hasAddAction)
        assertFalse(queueSwipePolicy(isContextEntry = false, canAddToQueue = true, dragging = false).hasAddAction)
    }

    @Test
    fun addResetsButDeleteDoesNot() {
        val policy = queueSwipePolicy(isContextEntry = true, canAddToQueue = true, dragging = false)

        assertTrue(policy.resetsAfter(SwipeToDismissBoxValue.StartToEnd))
        assertFalse(policy.resetsAfter(SwipeToDismissBoxValue.EndToStart))
    }

    @Test
    fun draggingDisablesBothDirections() {
        val policy = queueSwipePolicy(isContextEntry = true, canAddToQueue = true, dragging = true)

        assertFalse(policy.swipeEnabled)
    }
}
