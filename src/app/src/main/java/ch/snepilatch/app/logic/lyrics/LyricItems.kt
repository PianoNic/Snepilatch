package ch.snepilatch.app.logic.lyrics

import kotify.api.lyrics.Syllable
import kotify.api.lyrics.SyncedLine
import kotlin.math.max

/** One row of the synced lyrics view: a line of words, or the dots that fill a long gap. */
sealed interface LyricItem {
    val startMs: Long
    val endMs: Long
}

/** The timed words of one voice: which are lit letter by letter (the long ones), and which runs form one word. */
class Words(val syllables: List<Syllable>) {

    val emphasised: List<Boolean> =
        syllables.map { it.text.isNotEmpty() && it.endTimeMs - it.startTimeMs >= LyricsStyle.EMPHASIS_MIN_MS }

    /** Runs of syllable indices that form one word and must not wrap apart. */
    val runs: List<IntRange> = wordRuns(syllables)
}

/** A lyric line; [endMs] is the line's own end, or the next line's start when the provider gave none. */
data class LineItem(val line: SyncedLine, override val startMs: Long, override val endMs: Long) : LyricItem {

    val lead: Words = Words(line.syllables)

    /** The backing vocals drawn as the smaller line under the lead, or null when there are none (#814). */
    val background: Words? = line.background.takeIf { it.isNotEmpty() }?.let { Words(it) }
}

/** The three interlude dots and the windows each fills in. */
data class DotsItem(override val startMs: Long, override val endMs: Long, val dots: List<LongRange>) : LyricItem

/**
 * Builds the rows of the synced view from the provider's lines: blank and note-only lines are
 * dropped, a gap of [LyricsStyle.DOTS_MIN_GAP_MS] or more (before the first line too) becomes a
 * [DotsItem] whose three dots share the gap, each ending 183 ms early so the last one has gone
 * 550 ms before the vocals return.
 */
fun lyricItems(lines: List<SyncedLine>): List<LyricItem> {
    val sung = lines.filter { it.text.isNotBlank() && it.text.trim() != NOTE }
    val items = ArrayList<LyricItem>(sung.size * 2)
    var previousEnd = 0L
    for ((i, line) in sung.withIndex()) {
        val nextStart = sung.getOrNull(i + 1)?.startTimeMs
        if (line.startTimeMs - previousEnd >= LyricsStyle.DOTS_MIN_GAP_MS) items += dotsBetween(previousEnd, line.startTimeMs)
        val end = if (line.endTimeMs > line.startTimeMs) line.endTimeMs else nextStart ?: (line.startTimeMs + 1)
        items += LineItem(line, line.startTimeMs, end)
        previousEnd = end
    }
    return items
}

private const val NOTE = "♪"

private fun dotsBetween(gapStart: Long, nextStart: Long): DotsItem {
    val total = nextStart - gapStart
    val base = total / 3.0
    val padding = LyricsStyle.DOTS_END_PADDING_MS / 3.0
    val first = max(gapStart, (gapStart + base + padding).toLong())
    val second = max(first, (gapStart + base * 2 + padding * 2).toLong())
    val third = max(second, gapStart + total + LyricsStyle.DOTS_END_PADDING_MS)
    return DotsItem(gapStart, nextStart, listOf(gapStart until first, first until second, second until third))
}

private fun wordRuns(syllables: List<Syllable>): List<IntRange> {
    val runs = ArrayList<IntRange>()
    var start = 0
    for (i in 1..syllables.size) {
        if (i == syllables.size || !syllables[i].isPartOfWord) {
            runs += start until i
            start = i
        }
    }
    return runs
}

/** The letters of an emphasised syllable, each lit for an equal share of the window minus its tail. */
fun letterWindows(startMs: Long, endMs: Long, count: Int): List<LongRange> {
    if (count <= 0) return emptyList()
    val end = endMs - LyricsStyle.EMPHASIS_TAIL_MS
    val each = (end - startMs).toDouble() / count
    return List(count) { i -> (startMs + i * each).toLong() until (startMs + (i + 1) * each).toLong() }
}
