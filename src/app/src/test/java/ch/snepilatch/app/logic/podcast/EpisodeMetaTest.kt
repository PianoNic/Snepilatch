package ch.snepilatch.app.logic.podcast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

/** The web player's release date, progress and new episode rules (#1044). */
class EpisodeMetaTest {

    // A Wednesday, so the week runs from Sunday the 25th.
    private val today = LocalDate.of(2026, 10, 28)

    @Test
    fun `today, then the weekday within this sunday to saturday week`() {
        assertEquals(ReleaseLabel.Today, releaseLabel(today, today))
        assertEquals(ReleaseLabel.ThisWeek(DayOfWeek.SUNDAY), releaseLabel(LocalDate.of(2026, 10, 25), today))
    }

    @Test
    fun `before this week the date, with the year only when it is another one`() {
        assertEquals(ReleaseLabel.ThisYear(LocalDate.of(2026, 10, 20)), releaseLabel(LocalDate.of(2026, 10, 20), today))
        assertEquals(ReleaseLabel.OtherYear(LocalDate.of(2025, 12, 31)), releaseLabel(LocalDate.of(2025, 12, 31), today))
    }

    @Test
    fun `release day reads the iso string the service sends`() {
        assertEquals(LocalDate.of(2026, 10, 20), releaseDay("2026-10-20T00:00:00Z"))
        assertNull(releaseDay(null))
        assertNull(releaseDay("not a date"))
    }

    @Test
    fun `length before it starts, time left once started, played when done`() {
        assertEquals(EpisodeProgress.NotStarted(972_564), episodeProgress(972_564, 0, isPlayed = false))
        val started = episodeProgress(1_000_000, 250_000, isPlayed = false) as EpisodeProgress.Started
        assertEquals(750_000, started.leftMs)
        assertEquals(0.25f, started.fraction, 0.001f)
        assertEquals(EpisodeProgress.Played, episodeProgress(1_000_000, 250_000, isPlayed = true))
    }

    @Test
    fun `new for a week unless played or more than five minutes were heard`() {
        val lastWeek = today.minusDays(6)
        assertTrue(isNewEpisode(lastWeek, today, positionMs = 0, isPlayed = false))
        assertFalse(isNewEpisode(today.minusDays(7), today, positionMs = 0, isPlayed = false))
        assertFalse(isNewEpisode(lastWeek, today, positionMs = 300_001, isPlayed = false))
        assertFalse(isNewEpisode(lastWeek, today, positionMs = 0, isPlayed = true))
    }

    @Test
    fun `duration splits into hours, minutes and seconds`() {
        assertEquals(Triple(0L, 16L, 12L), durationParts(972_564))
        assertEquals(Triple(1L, 5L, 0L), durationParts(3_900_000))
    }
}
