package ch.snepilatch.app.download

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Scanning every downloaded row per list row cost 12ms a frame on a long playlist. The index keeps
 * the relinked-track fallback of [FindByMetadataTest] while costing a hash lookup instead.
 */
class DownloadedIndexTest {

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

    private val index = Downloads.indexOf(listOf(row))

    @Test
    fun theDownloadedUriIsFound() {
        assertTrue(Downloads.isDownloaded(index, row.trackUri))
    }

    @Test
    fun theOtherReleaseOfTheSameSongIsFound() {
        assertTrue(Downloads.isDownloaded(index, "spotify:track:other", row.title, "Kento Nakajima"))
    }

    @Test
    fun aTitleWithNoArtistStillMatches() {
        assertTrue(Downloads.isDownloaded(index, "spotify:track:other", row.title, ""))
    }

    @Test
    fun anotherSongByTheSameArtistIsNotDownloaded() {
        assertFalse(Downloads.isDownloaded(index, "spotify:track:other", "Different Song", "GEMN"))
    }

    @Test
    fun theSameTitleByAnotherArtistIsNotDownloaded() {
        assertFalse(Downloads.isDownloaded(index, "spotify:track:other", row.title, "Someone Else"))
    }
}
