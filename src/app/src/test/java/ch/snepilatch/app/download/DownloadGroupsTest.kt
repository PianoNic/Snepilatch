package ch.snepilatch.app.download

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * How downloads present in the library. A track saved on its own has no album or playlist to sit
 * under, so it is its own entry — named after the track, not after the record it came from. Falling
 * back to the album name turned two singles off one album into two library rows with the same title,
 * the same cover and the same "Single" subtitle, each holding one track.
 */
class DownloadGroupsTest {

    private fun row(
        uri: String,
        title: String,
        contextUri: String? = null,
        contextName: String? = null,
        contextType: String? = null,
    ) = DownloadedTrack(
        trackUri = uri,
        documentUri = "content://tree/Music/$title.opus",
        source = "ytm",
        provider = "YouTube Music",
        mimeType = "audio/ogg",
        coverUrl = "https://i.example/cover.jpg",
        contextUri = contextUri,
        contextName = contextName,
        contextType = contextType,
        sizeBytes = 1,
        title = title,
        artist = "Daft Punk",
        downloadedAt = 0,
    )

    @Test
    fun aOneOffDownloadIsItsOwnEntryNamedAfterTheTrack() {
        val groups = Downloads.groupsOf(listOf(row("spotify:track:1", "Instant Crush")))

        assertEquals(1, groups.size)
        assertEquals("Instant Crush", groups.first().name)
        assertEquals("single", groups.first().type)
        assertEquals("spotify:track:1", groups.first().uri)
    }

    @Test
    fun twoSinglesOffOneAlbumStayTwoDistinctEntries() {
        val groups = Downloads.groupsOf(
            listOf(row("spotify:track:1", "Instant Crush"), row("spotify:track:2", "Doin' It Right"))
        )

        assertEquals(2, groups.size)
        assertEquals(listOf("Doin' It Right", "Instant Crush"), groups.map { it.name })
        assertEquals(listOf(1, 1), groups.map { it.trackCount })
    }

    @Test
    fun tracksDownloadedFromAnAlbumGroupUnderIt() {
        val album = "spotify:album:4m2880jivSbbyEGAKfITCa"
        val groups = Downloads.groupsOf(
            listOf(
                row("spotify:track:1", "Instant Crush", album, "Random Access Memories", "album"),
                row("spotify:track:2", "Doin' It Right", album, "Random Access Memories", "album"),
            )
        )

        assertEquals(1, groups.size)
        assertEquals("Random Access Memories", groups.first().name)
        assertEquals("album", groups.first().type)
        assertEquals(2, groups.first().trackCount)
        assertEquals(album, groups.first().uri)
    }
}
