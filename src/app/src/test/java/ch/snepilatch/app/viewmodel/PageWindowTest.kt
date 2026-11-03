package ch.snepilatch.app.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Test

/** No row is asked for twice (#1102), with the numbers measured on the web player. */
class PageWindowTest {
    private fun loaded(vararg ranges: IntRange) = { row: Int -> ranges.any { row in it } }

    @Test
    fun nothingNearbyAsksForAFullPageFromTheRow() {
        assertEquals(PageWindow(972, 50), pageWindow(972, 50, loaded(0..24)))
    }

    @Test
    fun scrollingUpEndsRightBeforeWhatIsLoaded() {
        assertEquals(PageWindow(922, 50), pageWindow(960, 50, loaded(0..24, 972..1071)))
        assertEquals(PageWindow(872, 50), pageWindow(915, 50, loaded(0..24, 922..1071)))
    }

    @Test
    fun aNarrowGapAsksOnlyForTheGap() {
        assertEquals(PageWindow(100, 10), pageWindow(105, 50, loaded(0..99, 110..159)))
    }

    @Test
    fun theTopOfTheListIsNotPassed() {
        assertEquals(PageWindow(0, 20), pageWindow(5, 50, loaded(20..69)))
    }
}
