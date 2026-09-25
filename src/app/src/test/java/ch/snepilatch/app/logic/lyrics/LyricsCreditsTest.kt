package ch.snepilatch.app.logic.lyrics

import kotify.api.lyrics.LyricsAttribution
import kotify.api.lyrics.LyricsData
import org.junit.Assert.assertEquals
import org.junit.Test

class LyricsCreditsTest {

    private fun lyrics(providerName: String, attribution: LyricsAttribution? = null) =
        LyricsData("LINE_SYNCED", emptyList(), providerName = providerName, attribution = attribution)

    @Test fun aSpicyLyricsAnswer_isCreditedToTheCatalogueThatAnswered() {
        assertEquals("Apple Music", providerLabel(lyrics("SpicyLyrics (apple_music)", LyricsAttribution("Apple Music"))))
        assertEquals("Spfy", providerLabel(lyrics("SpicyLyrics (spotify)", LyricsAttribution("Spotify"))))
        assertEquals("unknown", providerLabel(lyrics("SpicyLyrics (unknown)", LyricsAttribution("unknown"))))
    }

    @Test fun anyOtherProvider_keepsItsName() {
        assertEquals("AMLL TTML DB", providerLabel(lyrics("AMLL TTML DB")))
        assertEquals("Spfy (Musixmatch)", providerLabel(lyrics("Spotify (Musixmatch)")))
        assertEquals("", providerLabel(lyrics("")))
    }
}
