package ch.snepilatch.app.data

import kotify.api.playerstatus.QueueTrack
import org.junit.Assert.assertEquals
import org.junit.Test

class KotifyMappersTest {
    @Test
    fun queueTrackKeepsAlbumNameForOptimisticSkips() {
        val track = QueueTrack(
            uri = "spotify:track:next",
            uid = "uid",
            name = "Track",
            artistName = "Artist",
            artistUri = null,
            albumName = "Album",
            albumUri = null,
            durationMs = 1_000,
            imageUrl = null,
            provider = null,
            metadata = emptyMap(),
            removedReasons = emptyList(),
        )

        assertEquals("Album", track.toTrackInfo().albumName)
    }
}
