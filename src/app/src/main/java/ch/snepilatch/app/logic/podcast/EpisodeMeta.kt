package ch.snepilatch.app.logic.podcast

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.temporal.TemporalAdjusters

/**
 * How an episode row names its release date, after the web player's rule: today, a weekday within
 * the current Sunday to Saturday week, otherwise the date, with the year only when it is not this one.
 */
sealed interface ReleaseLabel {
    data object Today : ReleaseLabel
    data class ThisWeek(val day: DayOfWeek) : ReleaseLabel
    data class ThisYear(val date: LocalDate) : ReleaseLabel
    data class OtherYear(val date: LocalDate) : ReleaseLabel
}

fun releaseLabel(date: LocalDate, today: LocalDate): ReleaseLabel {
    val weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY))
    return when {
        date == today -> ReleaseLabel.Today
        !date.isBefore(weekStart) && date.isBefore(today) -> ReleaseLabel.ThisWeek(date.dayOfWeek)
        date.year == today.year -> ReleaseLabel.ThisYear(date)
        else -> ReleaseLabel.OtherYear(date)
    }
}

/** The day of an ISO-8601 release date (`2026-10-20T00:00:00Z`), or null when there is none. */
fun releaseDay(isoString: String?): LocalDate? =
    isoString?.let { runCatching { OffsetDateTime.parse(it).toLocalDate() }.getOrNull() }

/** What the duration slot of an episode row says: the length, the time left, or that it was played. */
sealed interface EpisodeProgress {
    data class NotStarted(val durationMs: Long) : EpisodeProgress
    data class Started(val leftMs: Long, val fraction: Float) : EpisodeProgress
    data object Played : EpisodeProgress
}

fun episodeProgress(durationMs: Long, positionMs: Long, isPlayed: Boolean): EpisodeProgress = when {
    isPlayed -> EpisodeProgress.Played
    positionMs <= 0 || durationMs <= 0 -> EpisodeProgress.NotStarted(durationMs)
    else -> EpisodeProgress.Started(
        leftMs = (durationMs - positionMs).coerceAtLeast(0),
        fraction = (positionMs.toFloat() / durationMs).coerceIn(0f, 1f),
    )
}

/**
 * The web player marks an episode new for a week after release, unless it was played or more than
 * five minutes of it were heard.
 */
fun isNewEpisode(release: LocalDate?, today: LocalDate, positionMs: Long, isPlayed: Boolean): Boolean =
    release != null && !release.isBefore(today.minusDays(NEW_FOR_DAYS)) && !isPlayed && positionMs <= NEW_UNTIL_HEARD_MS

/** Hours, minutes and seconds of a duration as the row prints them: seconds only under a minute. */
fun durationParts(ms: Long): Triple<Long, Long, Long> {
    val totalSeconds = ms / 1000
    return Triple(totalSeconds / 3600, totalSeconds % 3600 / 60, totalSeconds % 60)
}

private const val NEW_FOR_DAYS = 6L
private const val NEW_UNTIL_HEARD_MS = 300_000L
