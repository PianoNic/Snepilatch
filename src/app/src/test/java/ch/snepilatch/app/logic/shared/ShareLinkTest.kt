package ch.snepilatch.app.logic.shared

import org.junit.Assert.assertEquals
import org.junit.Test

/** Issue #746: every share goes through one link builder. */
class ShareLinkTest {

    @Test
    fun aTrackUri_becomesItsPublicLink() {
        assertEquals("https://open.spotify.com/track/7ovUcF5uHTBRzUpB6ZOmvt", spfyLink("spotify:track:7ovUcF5uHTBRzUpB6ZOmvt"))
    }

    @Test
    fun otherEntities_keepTheirTypeSegment() {
        assertEquals("https://open.spotify.com/playlist/37i9dQZF1F5p3rmiWPIYgZ", spfyLink("spotify:playlist:37i9dQZF1F5p3rmiWPIYgZ"))
        assertEquals("https://open.spotify.com/artist/abc", spfyLink("spotify:artist:abc"))
    }
}
