package ch.snepilatch.app.logic.playback

import ch.snepilatch.app.data.TrackInfo

/**
 * What a drag in the queue sheet amounts to, worked out before anything is touched.
 *
 * Two translations happen here. The displayed list hides the delimiter and anything flagged, so
 * the server's index comes from whichever entry currently occupies the target row rather than from
 * the row number. And the move is clamped to the entry's own section, mirroring what the server
 * does, so the local list cannot show an order the server would refuse to store.
 */
sealed class QueueMovePlan {
    /** Move [from] to [target] in the displayed list and tell the server [rawTarget]. */
    data class Move(val qid: String, val from: Int, val target: Int, val rawTarget: Int) : QueueMovePlan()

    /** Nothing to do, and why. Every exit is worth a log line: a silent no-op looks like a broken drag. */
    data class Skip(val reason: String) : QueueMovePlan()
}

fun planQueueMove(list: List<TrackInfo>, queuedCount: Int, track: TrackInfo, toDisplayedIndex: Int): QueueMovePlan {
    val qid = track.qid ?: return QueueMovePlan.Skip("${track.name} has no qid, nothing to address")
    val from = list.indexOfFirst { it.qid == qid }
    if (from < 0) return QueueMovePlan.Skip("${track.name} is no longer in the list")
    val section = if (from < queuedCount) 0..(queuedCount - 1) else queuedCount..list.lastIndex
    if (section.isEmpty()) return QueueMovePlan.Skip("${track.name} sits in an empty section, from=$from queued=$queuedCount")
    val target = toDisplayedIndex.coerceIn(section)
    if (target == from) {
        return QueueMovePlan.Skip(
            "${track.name} asked for $toDisplayedIndex, clamped to $target inside $section, so it is already there. " +
                "queued=$queuedCount size=${list.size}"
        )
    }
    val rawTarget = list[target].queueIndex ?: return QueueMovePlan.Skip("no server index on the row at $target")
    return QueueMovePlan.Move(qid, from, target, rawTarget)
}
