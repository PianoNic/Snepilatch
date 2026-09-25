package ch.snepilatch.app.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The popular tracks' play count, shown the way the web player shows it (#878). */
class PlaycountLabelTest {

    @Test fun aCountIsFormatted() {
        assertEquals(String.format("%,d", 1566L), playcountLabel("1566"))
    }

    @Test fun zeroMeaningUnderAThousandShowsNothing() {
        assertNull(playcountLabel("0"))
        assertNull(playcountLabel(""))
        assertNull(playcountLabel(null))
    }
}
