package ch.snepilatch.app.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The saved search response is a real capture for "Daft Punk Get Lucky", whose songs shelf returns
 * the 6:10 album cut next to a 4:09 radio edit and a 4:08 re-upload. Taking the top-ranked hit is
 * right there by luck and wrong the moment the user's track is the radio edit, so the duration
 * matcher is what these tests pin.
 */
class YouTubeMusicSourceTest {

    private fun fixture(name: String): String =
        requireNotNull(javaClass.getResourceAsStream("/fixtures/youtubemusic/$name")) {
            "missing fixture $name"
        }.bufferedReader().use { it.readText() }

    private val candidates by lazy {
        YouTubeMusicSource.parseCandidates(fixture("search_songs.json"))
    }

    @Test
    fun parsesVideoIdTitleArtistAndDurationFromTheSongsShelf() {
        assertEquals(5, candidates.size)
        val first = candidates.first()
        assertEquals("4D7u5KF7SP8", first.videoId)
        assertTrue("got '${first.title}'", first.title.startsWith("Get Lucky"))
        assertEquals("Daft Punk", first.artist)
        assertEquals(370L, first.durationSec)
        assertTrue("every candidate needs a videoId", candidates.all { it.videoId.isNotBlank() })
    }

    @Test
    fun picksTheAlbumCutWhenTheSpotifyTrackIsTheAlbumCut() {
        val match = YouTubeMusicSource.bestMatch(candidates, "Get Lucky", "Daft Punk", durationMs = 369_000)
        assertNotNull(match)
        assertEquals("4D7u5KF7SP8", match!!.videoId)
        assertEquals(370L, match.durationSec)
    }

    @Test
    fun picksTheRadioEditWhenThatIsWhatTheDurationSays() {
        // Same query, same shelf, different Spotify track. Search rank alone would return the 6:10 cut.
        val match = YouTubeMusicSource.bestMatch(candidates, "Get Lucky", "Daft Punk", durationMs = 249_000)
        assertNotNull(match)
        assertEquals(249L, match!!.durationSec)
        assertTrue("got '${match.title}'", match.title.contains("Radio Edit"))
    }

    @Test
    fun refusesToGuessWhenNoCandidateIsCloseEnough() {
        assertNull(YouTubeMusicSource.bestMatch(candidates, "Get Lucky", "Daft Punk", durationMs = 480_000))
    }

    @Test
    fun matchesOnTitleAloneWhenTheDurationIsUnknown() {
        val match = YouTubeMusicSource.bestMatch(candidates, "Get Lucky", "Daft Punk", durationMs = 0)
        assertNotNull(match)
        assertEquals("4D7u5KF7SP8", match!!.videoId)
    }

    @Test
    fun neverPicksADifferentSongOnDurationAlone() {
        // "Lose Yourself to Dance" (5:54) sits in this shelf and is the closest thing by length to a
        // 5:54 request. The title score has to keep it out no matter how well the duration lines up.
        val match = YouTubeMusicSource.bestMatch(candidates, "Get Lucky", "Daft Punk", durationMs = 354_000)
        assertTrue(
            "picked '${match?.title}', which is not Get Lucky",
            match == null || match.title.contains("Get Lucky")
        )
    }

    @Test
    fun matchesDespiteASpellingDifferenceAndAVideoIntro() {
        // Real miss from the device: Spotify has "Tobbss stinkt" at 2:48, YouTube has it spelled
        // "Tobbs stinkt" in a 3:08 music video. Substring matching failed on the extra s and the
        // 20s intro blew a tight duration window, so the track resolved to nothing.
        val yt = listOf(
            YouTubeMusicSource.Candidate("aaa", "Team Melone (Hardstyle Remix)", "Chaosflo44", 152),
            YouTubeMusicSource.Candidate("bbb", "Tobbs stinkt | Chaosflo44", "Chaosflo44", 188),
        )
        val match = YouTubeMusicSource.bestMatch(yt, "Tobbss stinkt", "Chaosflo44", durationMs = 168_000)
        assertNotNull(match)
        assertEquals("bbb", match!!.videoId)
    }

    @Test
    fun rejectsCoversAndReworksTheRequestNeverAskedFor() {
        // Reported from the device: downloads kept landing on piano covers and re-sings. The title
        // carries every wanted word, so word coverage alone scores them perfectly.
        val yt = listOf(
            YouTubeMusicSource.Candidate("cover", "Get Lucky (Piano Cover)", "Some Pianist", 370),
            YouTubeMusicSource.Candidate("karaoke", "Get Lucky - Karaoke Version", "Sing King", 370),
            YouTubeMusicSource.Candidate("real", "Get Lucky", "Daft Punk", 369),
        )
        val match = YouTubeMusicSource.bestMatch(yt, "Get Lucky", "Daft Punk", durationMs = 369_000)
        assertNotNull(match)
        assertEquals("real", match!!.videoId)
    }

    @Test
    fun aRequestedRemixStillMatchesItsRemix() {
        // The marker only disqualifies a candidate when the request did not ask for it.
        val yt = listOf(YouTubeMusicSource.Candidate("rmx", "Sonne (Remix)", "Rammstein", 272))
        val match = YouTubeMusicSource.bestMatch(yt, "Sonne (Remix)", "Rammstein", durationMs = 272_000)
        assertNotNull(match)
        assertEquals("rmx", match!!.videoId)
    }

    @Test
    fun aDifferentUploaderIsNotTheArtist() {
        val yt = listOf(YouTubeMusicSource.Candidate("x", "Creep", "Random Channel", 239))
        assertNull(YouTubeMusicSource.bestMatch(yt, "Creep", "Radiohead", durationMs = 239_000))
    }

    @Test
    fun titleScoreCountsSharedWordsNotSubstrings() {
        val want = setOf("rappe", "nur", "das", "gleiche")
        assertEquals(1.0, YouTubeMusicSource.titleScore("RAPPE NUR DAS GLEICHE feat. LarsOderSo", want), 0.001)
        assertEquals(0.0, YouTubeMusicSource.titleScore("Fortnite 31er Song", want), 0.001)
        // Filler words alone must not carry a match; this one scored 0.5 and was picked at 3:30
        // against a 3:11 request, which is a different song entirely.
        assertTrue(YouTubeMusicSource.titleScore("Es ist immer das Gleiche", want) < YouTubeMusicSource.MIN_TITLE_SCORE)
    }

    @Test
    fun aDifferentGermanTitleSharingFillerWordsIsRejected() {
        val yt = listOf(
            YouTubeMusicSource.Candidate("wrong", "Es ist immer das Gleiche", "Someone", 210),
            YouTubeMusicSource.Candidate("right", "RAPPE NUR DAS GLEICHE feat. LarsOderSo", "Arazhul", 190),
        )
        val match = YouTubeMusicSource.bestMatch(yt, "Rappe nur das Gleiche", "Arazhul", durationMs = 191_000)
        assertNotNull(match)
        assertEquals("right", match!!.videoId)
    }

    @Test
    fun titleMatchToleratesYouTubesLongerSpelling() {
        assertEquals(
            "get lucky",
            YouTubeMusicSource.normalize("Get Lucky (feat. Pharrell Williams and Nile Rodgers)")
        )
        assertEquals("get lucky radio edit", YouTubeMusicSource.normalize("Get Lucky - Radio Edit"))
        // Non-latin titles must survive normalisation or every J-pop and K-pop track stops matching.
        assertEquals("勇者", YouTubeMusicSource.normalize("勇者"))
    }

    @Test
    fun parsesBothTimestampShapesAndIgnoresOtherRuns() {
        assertEquals(239L, YouTubeMusicSource.parseDuration("3:59"))
        assertEquals(3753L, YouTubeMusicSource.parseDuration("1:02:33"))
        assertNull(YouTubeMusicSource.parseDuration("1.8B plays"))
        assertNull(YouTubeMusicSource.parseDuration("Daft Punk"))
        assertNull(YouTubeMusicSource.parseDuration(""))
    }

    @Test
    fun takesTheHighestBitrateAudioOnlyFormat() {
        val stream = YouTubeMusicSource.pickAudio(fixture("player_android_vr.json"))
        assertNotNull(stream)
        assertEquals("audio/webm", stream!!.mimeType)
        assertTrue("got ${stream.url.take(40)}", stream.url.startsWith("https://"))
        // googlevideo matches the request identity against the client the player call claimed.
        assertTrue(stream.headers.getValue("User-Agent").contains("youtube.vr.oculus"))
    }

    @Test
    fun refusesAPlayerResponseThatDemandsALogin() {
        // What the older ANDROID_VR versions return; the pinned one must keep working.
        val denied = """{"playabilityStatus":{"status":"LOGIN_REQUIRED","reason":"Sign in"}}"""
        assertNull(YouTubeMusicSource.pickAudio(denied))
    }

    /**
     * An instrumental cut carries the original's exact title and artist and runs to the same length,
     * so neither the title check nor the duration filter can see it. The release it sits on is the
     * only thing that says what it is, and rank breaks the rest.
     */
    /**
     * The title scorer drops bracketed text so "Song (feat. X)" still matches "Song". That also
     * erased "(Instrumental)", leaving a cut with the same title, artist and length as the real one.
     */
    @Test
    fun anInstrumentalInBracketsIsRejected() {
        val yt = listOf(
            YouTubeMusicSource.Candidate("inst", "ファタール - Fatal (Instrumental)", "GEMN", 219),
            YouTubeMusicSource.Candidate("real", "ファタール - Fatal", "GEMN", 219),
        )
        val match = YouTubeMusicSource.bestMatch(yt, "ファタール - Fatal", "GEMN", durationMs = 219_000)
        assertEquals("real", match?.videoId)
    }

    /** Asking for one keeps working: the marker is then in the request too. */
    @Test
    fun anInstrumentalIsKeptWhenItIsWhatWasAskedFor() {
        val yt = listOf(YouTubeMusicSource.Candidate("inst", "Sonne (Instrumental)", "Rammstein", 272))
        val match = YouTubeMusicSource.bestMatch(yt, "Sonne (Instrumental)", "Rammstein", durationMs = 272_000)
        assertEquals("inst", match?.videoId)
    }

    /** A bracketed "(feat. …)" is not a rework and must still match. */
    @Test
    fun aFeatureCreditIsNotARework() {
        val yt = listOf(YouTubeMusicSource.Candidate("x", "Get Lucky (feat. Pharrell Williams)", "Daft Punk", 369))
        val match = YouTubeMusicSource.bestMatch(yt, "Get Lucky", "Daft Punk", durationMs = 369_000)
        assertEquals("x", match?.videoId)
    }

    @Test
    fun anInstrumentalReleaseIsRejectedByItsAlbum() {
        val yt = listOf(
            YouTubeMusicSource.Candidate(
                "inst", "ファタール - Fatal", "GEMN", 219,
                details = "GEMN ファタール - Fatal (Instrumental) 3:39",
            ),
            YouTubeMusicSource.Candidate(
                "real", "ファタール - Fatal", "GEMN", 220,
                details = "GEMN ファタール - Fatal 3:40",
            ),
        )
        val match = YouTubeMusicSource.bestMatch(yt, "ファタール - Fatal", "GEMN", durationMs = 219_000)
        assertEquals("real", match?.videoId)
    }

    /** A duration a second closer must not outrank YouTube Music's own ordering. */
    @Test
    fun aMarginallyCloserDurationDoesNotOutrankTheTopResult() {
        val yt = listOf(
            YouTubeMusicSource.Candidate("top", "Get Lucky", "Daft Punk", 369),
            YouTubeMusicSource.Candidate("other", "Get Lucky", "Daft Punk", 370),
        )
        val match = YouTubeMusicSource.bestMatch(yt, "Get Lucky", "Daft Punk", durationMs = 370_000)
        assertEquals("top", match?.videoId)
    }

    /** A genuinely better fit still wins: this is a tiebreak, not a rank-only rule. */
    @Test
    fun aClearlyBetterDurationStillWins() {
        val yt = listOf(
            YouTubeMusicSource.Candidate("short", "Get Lucky", "Daft Punk", 350),
            YouTubeMusicSource.Candidate("full", "Get Lucky", "Daft Punk", 369),
        )
        val match = YouTubeMusicSource.bestMatch(yt, "Get Lucky", "Daft Punk", durationMs = 369_000)
        assertEquals("full", match?.videoId)
    }

    /**
     * The rework markers include bare instrument nouns, and a release is allowed to be called after
     * one. Scanning the whole details run meant an EP named "Guitar Songs" read as a guitar cover, so
     * every candidate was filtered and the track could not be resolved on this source at all. Only
     * bracketed text is a release declaring itself.
     */
    @Test
    fun anAlbumNamedAfterAnInstrumentIsNotARework() {
        val yt = listOf(
            YouTubeMusicSource.Candidate(
                "real", "TV", "Billie Eilish", 293,
                details = "Billie Eilish Guitar Songs 4:53",
            )
        )
        val match = YouTubeMusicSource.bestMatch(yt, "TV", "Billie Eilish", durationMs = 293_000)
        assertEquals("real", match?.videoId)
    }

    /** The bracketed form still disqualifies, which is what the album scan was added for. */
    @Test
    fun aBracketedInstrumentalInTheAlbumIsStillRejected() {
        val yt = listOf(
            YouTubeMusicSource.Candidate(
                "inst", "TV", "Billie Eilish", 293,
                details = "Billie Eilish Guitar Songs (Instrumental) 4:53",
            )
        )
        assertNull(YouTubeMusicSource.bestMatch(yt, "TV", "Billie Eilish", durationMs = 293_000))
    }

    @Test
    fun onlyBracketedPartsOfTheReleaseTextAreRead() {
        assertEquals("Instrumental", YouTubeMusicSource.bracketedIn("GEMN Fatal (Instrumental) 3:39"))
        assertEquals("", YouTubeMusicSource.bracketedIn("Billie Eilish Guitar Songs 4:53"))
    }

    /**
     * A Spfy queue push often carries no artist name, and the UI's "Unknown" placeholder used to reach
     * the matcher as if it were a credit — so every real candidate was rejected for not being by an
     * artist called Unknown, and gapless pre-resolve degraded to a cold resolve at every transition.
     */
    @Test
    fun theUnknownArtistPlaceholderIsNotTreatedAsACredit() {
        val yt = listOf(YouTubeMusicSource.Candidate("real", "Get Lucky", "Daft Punk", 369))
        val match = YouTubeMusicSource.bestMatch(yt, "Get Lucky", "Unknown", durationMs = 369_000)
        assertEquals("real", match?.videoId)
    }

    @Test
    fun arealArtistIsStillRequiredToMatchWhenOneWasGiven() {
        val yt = listOf(YouTubeMusicSource.Candidate("x", "Creep", "Random Channel", 239))
        assertNull(YouTubeMusicSource.bestMatch(yt, "Creep", "Radiohead", durationMs = 239_000))
    }

    /**
     * The rows below are live captures for the KAIHEN -tsumi- release, taken after every track of
     * it failed to resolve on a device.
     */
    @Test
    fun matchesAnArtistSpotifyRomanisesAndYouTubeMusicLeavesInJapanese() {
        // Spotify credits TSUMITOBATSU, YouTube Music credits 罪十罰. Word overlap between the two
        // scripts is empty by construction, so the artist filter rejected the band's own upload.
        val yt = listOf(
            YouTubeMusicSource.Candidate("QLhTcG7tZ7M", "RAVEN", "罪十罰", 197, details = "罪十罰 KAIHEN -tsumi- 3:17"),
            YouTubeMusicSource.Candidate("cgfLdqXyZ8w", "Seventh Heaven", "The Raven Age", 331),
            YouTubeMusicSource.Candidate("DIat_1r73iM", "RAVEN", "Phonkha", 147),
        )
        val match = YouTubeMusicSource.bestMatch(yt, "RAVEN", "TSUMITOBATSU", durationMs = 196_000)
        assertEquals("QLhTcG7tZ7M", match?.videoId)
    }

    /** A Latin-script artist that genuinely differs is still rejected; the skip is script-only. */
    @Test
    fun aDifferentArtistInTheSameScriptIsStillRejected() {
        val yt = listOf(YouTubeMusicSource.Candidate("wrong", "RAVEN", "Phonkha", 196))
        assertNull(YouTubeMusicSource.bestMatch(yt, "RAVEN", "TSUMITOBATSU", durationMs = 196_000))
    }

    @Test
    fun matchesWhenYouTubeMusicCreditsTheCollectiveAndSpotifyTheMembers() {
        // Real rows for "DIGGER": YouTube Music files it under GIRLS REVOLUTION PROJECT, Spotify
        // under TSUMITOBATSU, biz, ZERA. Both Latin, so the script rule does not save it, and no
        // candidate carries the wanted name at all. Rejecting on the artist left nothing.
        val yt = listOf(
            YouTubeMusicSource.Candidate("1jWHBQJhEcw", "BETTALATION", "biz", 153),
            YouTubeMusicSource.Candidate("Mdu8yROvtwI", "DIGGER", "GIRLS REVOLUTION PROJECT", 184),
        )
        val match = YouTubeMusicSource.bestMatch(
            yt, "DIGGER", "TSUMITOBATSU, biz, ZERA", durationMs = 183_000, officialShelf = true,
        )
        assertEquals("Mdu8yROvtwI", match?.videoId)
    }

    /** The same shutout on the videos shelf stays a miss: that is where anyone can upload. */
    @Test
    fun anUnknownCreditOnTheVideosShelfIsStillRejected() {
        val yt = listOf(YouTubeMusicSource.Candidate("x", "DIGGER", "Some Random Channel", 184))
        assertNull(
            YouTubeMusicSource.bestMatch(yt, "DIGGER", "TSUMITOBATSU", durationMs = 183_000, officialShelf = false),
        )
    }

    /** When one candidate IS the artist, the others still lose: the fallback only runs on a shutout. */
    @Test
    fun theArtistStillDecidesWhenACandidateCarriesIt() {
        val yt = listOf(
            YouTubeMusicSource.Candidate("resing", "Get Lucky", "Some Guy", 369),
            YouTubeMusicSource.Candidate("real", "Get Lucky", "Daft Punk", 369),
        )
        val match = YouTubeMusicSource.bestMatch(yt, "Get Lucky", "Daft Punk", durationMs = 369_000, officialShelf = true)
        assertEquals("real", match?.videoId)
    }
}
