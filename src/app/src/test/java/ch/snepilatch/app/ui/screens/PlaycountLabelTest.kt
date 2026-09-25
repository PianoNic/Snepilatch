package ch.snepilatch.app.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The popular tracks' play count (#878). */
class PlaycountLabelTest {

    @Test fun aCountIsFormatted() {
        assertEquals(String.format("%,d", 1566L), playcountLabel("1566"))
    }

    @Test fun zeroMeansUnderAThousand() {
        assertEquals("< " + String.format("%,d", 1000), playcountLabel("0"))
    }

    @Test fun noCountShowsNothing() {
        assertNull(playcountLabel(""))
        assertNull(playcountLabel(null))
    }
}
