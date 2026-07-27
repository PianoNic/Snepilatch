package ch.snepilatch.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpfyImageUrlTest {

    @Test fun rewritesSpfyImageUriToCdnUrl() {
        assertEquals(
            "https://i.scdn.co/image/ab67616d0000b273abc",
            normalizeSpfyImageUrl("spotify:image:ab67616d0000b273abc")
        )
    }

    @Test fun passesThroughHttpsUrl() {
        val url = "https://i.scdn.co/image/already-an-url"
        assertEquals(url, normalizeSpfyImageUrl(url))
    }

    @Test fun passesThroughHttpUrl() {
        val url = "http://example.com/cover.jpg"
        assertEquals(url, normalizeSpfyImageUrl(url))
    }

    @Test fun passesNullThrough() {
        assertNull(normalizeSpfyImageUrl(null))
    }

    @Test fun passesBlankThrough() {
        assertEquals("", normalizeSpfyImageUrl(""))
        assertEquals("   ", normalizeSpfyImageUrl("   "))
    }
}
