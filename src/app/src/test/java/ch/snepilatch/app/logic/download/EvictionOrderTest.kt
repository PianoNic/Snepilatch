package ch.snepilatch.app.logic.download

import org.junit.Assert.assertEquals
import org.junit.Test

/** The storage cap makes room from auto-saves only, oldest first; a download the user asked for stays (#581). */
class EvictionOrderTest {

    private fun row(uri: String, at: Long, auto: Boolean) = DownloadedTrack(
        trackUri = uri,
        documentUri = "content://tree/Music/$uri.opus",
        source = "ytm",
        provider = null,
        mimeType = "audio/ogg",
        coverUrl = null,
        contextUri = null,
        contextName = null,
        contextType = null,
        sizeBytes = 1,
        title = uri,
        artist = "Daft Punk",
        downloadedAt = at,
        auto = auto,
    )

    @Test
    fun onlyAutoSavesAreEvictedOldestFirst() {
        val rows = listOf(
            row("manual-oldest", at = 1, auto = false),
            row("auto-newer", at = 3, auto = true),
            row("auto-older", at = 2, auto = true),
        )

        assertEquals(listOf("auto-older", "auto-newer"), TrackDownloader.evictionOrder(rows).map { it.trackUri })
    }

    @Test
    fun nothingIsEvictedWhenEveryDownloadWasAskedFor() {
        assertEquals(emptyList<DownloadedTrack>(), TrackDownloader.evictionOrder(listOf(row("manual", at = 1, auto = false))))
    }
}
