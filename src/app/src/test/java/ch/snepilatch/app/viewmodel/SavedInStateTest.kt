package ch.snepilatch.app.viewmodel

import kotify.api.playlist.CurationContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What Done writes in the "Saved in" sheet: only the ticks that differ from the server (#710). */
class SavedInStateTest {
    private fun row(uri: String, curated: Boolean) = CurationContext(uri, uri, "playlist", null, false, curated)

    @Test
    fun aTickFlipsAndIsAChange() {
        val empty = row("a", curated = false)
        val state = SavedInViewModel.State().toggled(empty)
        assertTrue(state.ticked(empty))
        assertEquals(mapOf("a" to true), state.changes)
    }

    @Test
    fun takingAWayASavedOneIsARemoval() {
        val saved = row("a", curated = true)
        val state = SavedInViewModel.State().toggled(saved)
        assertFalse(state.ticked(saved))
        assertEquals(mapOf("a" to false), state.changes)
    }

    @Test
    fun tickingBackIsNoChange() {
        val empty = row("a", curated = false)
        assertEquals(emptyMap<String, Boolean>(), SavedInViewModel.State().toggled(empty).toggled(empty).changes)
    }
}
