package ch.snepilatch.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpotifyImageUrlTest {

    @Test fun rewritesSpotifyImageUriToCdnUrl() {
        assertEquals(
            "https://i.scdn.co/image/ab67616d0000b273abc",
            normalizeSpotifyImageUrl("spotify:image:ab67616d0000b273abc")
        )
    }

    @Test fun passesThroughHttpsUrl() {
        val url = "https://i.scdn.co/image/already-an-url"
        assertEquals(url, normalizeSpotifyImageUrl(url))
    }

    @Test fun passesThroughHttpUrl() {
        val url = "http://example.com/cover.jpg"
        assertEquals(url, normalizeSpotifyImageUrl(url))
    }

    @Test fun passesNullThrough() {
        assertNull(normalizeSpotifyImageUrl(null))
    }

    @Test fun passesBlankThrough() {
        assertEquals("", normalizeSpotifyImageUrl(""))
        assertEquals("   ", normalizeSpotifyImageUrl("   "))
    }
}
