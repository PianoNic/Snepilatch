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

    // Issue #1038: cases from a real folder the matcher missed.

    @Test
    fun anArtistWhoseNameHasACommaIsStillTheSameArtist() {
        val september = Candidate("2grjqo0Frpf2okIBiifQKs", "September", listOf("Earth, Wind & Fire"), 215_093)
        assertEquals(september.id, TrackMatcher.match("September", "Earth, Wind & Fire", 215_006, listOf(september)))
    }

    @Test
    fun anArtistsWordsInAnotherOrderAreTheSameArtist() {
        val stars = Candidate("1", "Seeing Stars", listOf("Mori Calliope", "Lotus Juice"), 207_600)
        assertEquals(stars.id, TrackMatcher.match("Seeing Stars", "Calliope Mori", 207_567, listOf(stars)))
    }

    @Test
    fun aTitleInAnotherLanguageMatchesOnArtistAndLengthToTheSecond() {
        val surrender = Candidate("2", "Surrender", listOf("MRCH"), 207_500)
        assertEquals(surrender.id, TrackMatcher.match("항복", "MRCH", 207_411, listOf(surrender)))
        // Three seconds apart is another recording, not the same one under another name.
        assertNull(TrackMatcher.match("항복", "MRCH", 210_600, listOf(surrender)))
    }
}
