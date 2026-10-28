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
        // Only the same length to the second makes another artist name the same song (#1053); a cover is longer or shorter.
        assertNull(TrackMatcher.match("Instant Crush", "Someone Else", 341_000, listOf(crush)))
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

    @Test
    fun anArtistUnderAnotherNameMatchesOnTitleAndLengthToTheSecond() {
        val song = Candidate("3", "1999", listOf("Mark Lee"), 205_400)
        assertEquals(song.id, TrackMatcher.match("1999", "MARK", 205_027, listOf(song)))
        assertNull(TrackMatcher.match("1999", "MARK", 209_000, listOf(song)))
    }

    @Test
    fun aMissSaysWhatIsWrong() {
        val meeting = Candidate("4", "Meeting", listOf("ATLUS Sound Team", "ATLUS GAME MUSIC"), 45_386)
        // The file is a 207 s loop of a 45 s track: the song is there, the file is another recording of it.
        assertEquals(
            Miss.WrongLength("4", fileMs = 207_720, catalogueMs = 45_386),
            TrackMatcher.miss("Meeting", "アトラスサウンドチーム, ATLUS GAME MUSIC", 207_720, listOf(meeting)),
        )
        assertEquals(Miss.OtherArtist("Luciid"), TrackMatcher.miss("pray", "Such", 203_453, listOf(Candidate("5", "pray", listOf("Luciid"), 180_000))))
        assertEquals(Miss.NotFound, TrackMatcher.miss("pa!", "SEE", 197_093, emptyList()))
        // Of a song's cuts, the one of exactly that title is what the file was meant to be, not the remix closest in length.
        val plain = Candidate("6", "Children", listOf("Robert Miles"), 453_000)
        val remix = Candidate("7", "Children - Undercatt Remix", listOf("Robert Miles"), 405_000)
        assertEquals("6", (TrackMatcher.miss("Children", "Robert Miles", 411_307, listOf(remix, plain)) as Miss.WrongLength).id)
        assertEquals("6", TrackMatcher.match("Children", "Robert Miles", 0, listOf(remix, plain)))
    }
}
