package ch.snepilatch.app.logic.download

import ch.snepilatch.app.logic.download.TrackMatcher.Candidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A wrong match plays the wrong song from the file, so the rule errs on leaving it unmatched (#930). */
class TrackMatcherTest {

    private val crush =
        Candidate("2cGxRwrMyEAp8dEbuZaVv6", "Instant Crush (feat. Julian Casablancas)", listOf("Daft Punk", "Julian Casablancas"), 337_560)
    private val drumless =
        Candidate("2jmKcUO1mjqU4uypiDvpny", "Instant Crush (Drumless Edition)", listOf("Daft Punk"), 330_000)

    @Test
    fun matchesTitleArtistAndLengthIgnoringFeatures() {
        assertEquals(crush.id, TrackMatcher.match("Instant Crush", "Daft Punk", 338_100, listOf(crush, drumless)))
    }

    @Test
    fun aDifferentLengthIsNotTheSameRecording() {
        assertNull(TrackMatcher.match("Instant Crush", "Daft Punk", 345_000, listOf(crush)))
    }

    @Test
    fun aFewSecondsOffStillMatchesAndTheClosestWins() {
        val first = Candidate("7gJD9BarjoFwL2BNQ0rpWT", "Fatal", listOf("GEMN"), 219_200)
        val closer = Candidate("1Is6RUjCNWxIWK0SCBguuy", "Fatal", listOf("GEMN"), 220_520)
        assertEquals(closer.id, TrackMatcher.match("Fatal", "GEMN, Kento Nakajima", 223_286, listOf(first, closer)))
    }

    @Test
    fun aDifferentArtistIsNotAMatch() {
        assertNull(TrackMatcher.match("Instant Crush", "Someone Else", 337_560, listOf(crush)))
    }

    @Test
    fun anyOfSeveralArtistsCounts() {
        assertEquals(crush.id, TrackMatcher.match("Instant Crush", "Julian Casablancas, Daft Punk", 337_560, listOf(crush)))
    }

    @Test
    fun nonLatinTitlesMatch() {
        val ufo = Candidate("77LMizNt1rTxojfMt9sL7t", "未確認飛行体", listOf("Takashi Fujii"), 302_000)
        assertEquals(ufo.id, TrackMatcher.match("未確認飛行体", "Takashi Fujii", 302_500, listOf(ufo)))
    }

    @Test
    fun anUnknownLengthStillMatchesOnTitleAndArtist() {
        assertEquals(crush.id, TrackMatcher.match("Instant Crush", "Daft Punk", 0, listOf(crush)))
    }
}
