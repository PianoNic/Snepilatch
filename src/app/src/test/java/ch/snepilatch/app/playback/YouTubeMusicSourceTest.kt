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
        val match = YouTubeMusicSource.bestMatch(candidates, "Get Lucky", durationMs = 369_000)
        assertNotNull(match)
        assertEquals("4D7u5KF7SP8", match!!.videoId)
        assertEquals(370L, match.durationSec)
    }

    @Test
    fun picksTheRadioEditWhenThatIsWhatTheDurationSays() {
        // Same query, same shelf, different Spotify track. Search rank alone would return the 6:10 cut.
        val match = YouTubeMusicSource.bestMatch(candidates, "Get Lucky", durationMs = 249_000)
        assertNotNull(match)
        assertEquals(249L, match!!.durationSec)
        assertTrue("got '${match.title}'", match.title.contains("Radio Edit"))
    }

    @Test
    fun refusesToGuessWhenNoCandidateIsCloseEnough() {
        assertNull(YouTubeMusicSource.bestMatch(candidates, "Get Lucky", durationMs = 480_000))
    }

    @Test
    fun matchesOnTitleAloneWhenTheDurationIsUnknown() {
        val match = YouTubeMusicSource.bestMatch(candidates, "Get Lucky", durationMs = 0)
        assertNotNull(match)
        assertEquals("4D7u5KF7SP8", match!!.videoId)
    }

    @Test
    fun neverPicksADifferentSongOnDurationAlone() {
        // "Lose Yourself to Dance" (5:54) sits in this shelf and is the closest thing by length to a
        // 5:54 request. The title score has to keep it out no matter how well the duration lines up.
        val match = YouTubeMusicSource.bestMatch(candidates, "Get Lucky", durationMs = 354_000)
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
        val match = YouTubeMusicSource.bestMatch(yt, "Tobbss stinkt", durationMs = 168_000)
        assertNotNull(match)
        assertEquals("bbb", match!!.videoId)
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
        val match = YouTubeMusicSource.bestMatch(yt, "Rappe nur das Gleiche", durationMs = 191_000)
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
}
