package ch.snepilatch.app.logic.shared

import org.junit.Assert.assertEquals
import org.junit.Test

/** Issue #749: one place turns a spfy uri into its id. */
class SpfyUriTest {

    @Test
    fun theIdIsTheLastSegment() {
        assertEquals("7ovUcF5uHTBRzUpB6ZOmvt", spfyId("spotify:track:7ovUcF5uHTBRzUpB6ZOmvt"))
        assertEquals("abc", spfyId("spotify:user:someone:playlist:abc"))
    }

    @Test
    fun aBareIdStaysAsItIs() {
        assertEquals("abc", spfyId("abc"))
    }
}
