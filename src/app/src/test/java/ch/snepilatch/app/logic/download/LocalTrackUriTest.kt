package ch.snepilatch.app.logic.download

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalTrackUriTest {

    @Test
    fun encodesLikeTheClient() {
        assertEquals(
            "spotify:local:Daft+Punk:Discovery:One+More+Time%2A:320",
            LocalFiles.localTrackUri("Daft Punk", "Discovery", "One More Time*", 320_500),
        )
    }

    @Test
    fun aColonInATagCannotSplitTheUri() {
        assertEquals("spotify:local:A%3AB:::1", LocalFiles.localTrackUri("A:B", "", "", 1_000))
    }
}
