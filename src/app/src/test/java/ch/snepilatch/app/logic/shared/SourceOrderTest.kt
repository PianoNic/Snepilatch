package ch.snepilatch.app.logic.shared

import ch.snepilatch.app.logic.playback.AudioSourceResolver
import org.junit.Assert.assertEquals
import org.junit.Test

class SourceOrderTest {
    @Test
    fun aSingleSavedSourceBecomesAnOrderOfOne() {
        assertEquals(listOf("spotify"), sourceOrderFrom(null, null))
        assertEquals(listOf("lossless"), sourceOrderFrom(null, "lossless"))
        assertEquals(listOf("spotify"), sourceOrderFrom(null, "spotify"))
    }

    @Test
    fun theSavedOrderWinsAndDropsWhatItDoesNotKnow() {
        assertEquals(listOf("lossless", "spotify", "ytm"), sourceOrderFrom("lossless,spotify,ytm", "ytm"))
        assertEquals(listOf("ytm"), sourceOrderFrom("tidal,ytm,ytm", null))
        assertEquals(listOf("spotify"), sourceOrderFrom("tidal", null))
    }

    @Test
    fun fileSourcesBeforeSpfyAreTheOnesTriedAhead() {
        assertEquals(listOf("lossless", "ytm"), AudioSourceResolver.fileSourcesFirst(listOf("lossless", "ytm", "spotify")))
        assertEquals(listOf("lossless"), AudioSourceResolver.fileSourcesFirst(listOf("lossless", "spotify", "ytm")))
        assertEquals(emptyList<String>(), AudioSourceResolver.fileSourcesFirst(listOf("spotify", "lossless")))
    }
}
