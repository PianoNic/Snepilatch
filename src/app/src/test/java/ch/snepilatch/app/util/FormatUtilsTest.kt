package ch.snepilatch.app.util

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatUtilsTest {

    @Test fun zero() {
        assertEquals("0:00", formatTime(0))
    }

    @Test fun lessThanOneMinute() {
        assertEquals("0:42", formatTime(42_000))
    }

    @Test fun exactMinute() {
        assertEquals("1:00", formatTime(60_000))
    }

    @Test fun overOneMinute() {
        assertEquals("3:14", formatTime(194_000))
    }

    @Test fun longTrack() {
        assertEquals("12:34", formatTime(754_000))
    }

    @Test fun roundsDownToWholeSeconds() {
        assertEquals("0:00", formatTime(999))
        assertEquals("0:01", formatTime(1_000))
        assertEquals("0:01", formatTime(1_999))
    }
}
