package ch.snepilatch.app.logic.lyrics

import org.junit.Assert.assertEquals
import org.junit.Test

class LyricsCreditsTest {

    @Test fun aRelayedSpicyLyricsSource_isCreditedByItsOwnName() {
        assertEquals("Apple Music", providerLabel("SpicyLyrics (aml)"))
        assertEquals("Spfy", providerLabel("SpicyLyrics (spt)"))
        assertEquals("Spicy Lyrics", providerLabel("SpicyLyrics (xyz)"))
    }

    @Test fun anyOtherProvider_keepsItsName() {
        assertEquals("AMLL TTML DB", providerLabel("AMLL TTML DB"))
        assertEquals("Spfy (Musixmatch)", providerLabel("Spotify (Musixmatch)"))
        assertEquals("", providerLabel(""))
    }
}
