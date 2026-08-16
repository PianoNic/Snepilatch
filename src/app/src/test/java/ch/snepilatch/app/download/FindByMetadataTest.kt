package ch.snepilatch.app.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The same song sits in the catalogue under more than one id: separate releases, and the per-market
 * instances Spotify relinks between. A uri-keyed index misses those, so a downloaded track streamed
 * whenever it turned up under the other id.
 */
class FindByMetadataTest {

    private val row = DownloadedTrack(
        trackUri = "spotify:track:7gJD9BarjoFwL2BNQ0rpWT",
        documentUri = "content://tree/Music/fatal.opus",
        source = "ytm",
        provider = "YouTube Music",
        mimeType = "audio/ogg",
        coverUrl = null,
        contextUri = null,
        contextName = null,
        contextType = null,
        sizeBytes = 1,
        title = "ファタール - Fatal",
        artist = "GEMN, Kento Nakajima, Tatsuya Kitani",
        downloadedAt = 0,
    )

    private fun match(title: String, artist: String) =
        Downloads.matchByMetadata(listOf(row), title, artist)

    @Test
    fun theOtherReleaseOfTheSameSongMatches() {
        assertEquals(row, match("ファタール - Fatal", "GEMN, Kento Nakajima, Tatsuya Kitani"))
    }

    @Test
    fun aDifferentCreditOrderStillMatches() {
        assertEquals(row, match("ファタール - Fatal", "Tatsuya Kitani, GEMN"))
    }

    @Test
    fun aPartialCreditListStillMatches() {
        assertEquals(row, match("ファタール - Fatal", "GEMN"))
    }

    @Test
    fun caseAndWidthDoNotMatter() {
        assertEquals(row, match("ﾌｧﾀｰﾙ - FATAL", "gemn"))
    }

    @Test
    fun aDifferentSongByTheSameArtistDoesNotMatch() {
        assertNull(match("Another Song", "GEMN"))
    }

    @Test
    fun aCoverBySomeoneElseDoesNotMatch() {
        assertNull(match("ファタール - Fatal", "Piano Covers Inc"))
    }

    @Test
    fun aBlankTitleNeverMatches() {
        assertNull(match("", "GEMN"))
    }

    // --- isDownloaded: the checkmark's uri-then-metadata lookup, same fallback playback uses ---

    @Test
    fun isDownloadedMatchesByExactUriRegardlessOfMetadata() {
        assertTrue(Downloads.isDownloaded(listOf(row), row.trackUri, title = "Some Other Title", artist = "Someone Else"))
    }

    @Test
    fun isDownloadedFallsBackToMetadataWhenTheUriDiffers() {
        assertTrue(
            Downloads.isDownloaded(
                listOf(row),
                trackUri = "spotify:track:relinkedIdNotInTheIndex",
                title = "ファタール - Fatal",
                artist = "GEMN, Kento Nakajima, Tatsuya Kitani",
            )
        )
    }

    @Test
    fun isDownloadedIsFalseWhenNeitherUriNorMetadataMatch() {
        assertFalse(Downloads.isDownloaded(listOf(row), "spotify:track:notDownloaded", "Another Song", "Nobody"))
    }

    @Test
    fun isDownloadedIsFalseForAMissingUriAndNoMetadataToFallBackOn() {
        assertFalse(Downloads.isDownloaded(listOf(row), "spotify:track:notDownloaded"))
    }
}
