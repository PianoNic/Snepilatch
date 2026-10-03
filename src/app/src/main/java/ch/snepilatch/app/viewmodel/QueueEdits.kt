package ch.snepilatch.app.viewmodel

import ch.snepilatch.app.R
import ch.snepilatch.app.logic.shared.AppMessages
import ch.snepilatch.app.logic.shared.LokiLogger
import ch.snepilatch.app.logic.shared.SessionHolder
import ch.snepilatch.app.logic.shared.normalizeSpfyImageUrl
import ch.snepilatch.app.data.TrackInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotify.api.playerconnect.PlayerConnect
import kotify.api.playerstatus.QueueView
import kotify.api.song.Song
import kotlinx.coroutines.Dispatchers

private const val TAG = "PlaybackVM"

// Optimistic queue edits: serialized writes, skip bookkeeping and the displayed list, kept apart
// from playback transport. Everything that touches the pending sets runs on Main.
private suspend fun sendQueueRemoval(pc: PlayerConnect, qid: String) {
    repeat(3) { attempt ->
        try {
            pc.removeFromQueue(qid)
            return
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (attempt == 2 || kotify.http.RateLimitGate.waitMs <= 0) throw e
            kotify.http.RateLimitGate.awaitClearance()
        }
    }
}

internal suspend fun PlaybackViewModel.drainQueueRemovals(pc: PlayerConnect) {
    val completed = mutableSetOf<String>()
    try {
        while (pendingQueueRemovals.isNotEmpty()) {
            if (SessionHolder.player !== pc || isOffline.value) return
            val next = pendingQueueRemovals.firstOrNull { it !in completed }
            if (next != null) {
                queueEditMutex.withLock {
                    try {
                        withContext(queueIoDispatcher) { sendQueueRemoval(pc, next) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // A timed-out write may have succeeded. Verify before restoring the row.
                        LokiLogger.e(TAG, "removeFromQueue $next", e)
                    }
                    delay(1_000)
                }
                completed += next
                continue
            }
            // Reconcile only this completed batch. New swipes may arrive during the read.
            val generation = queueReadGeneration
            val view = try {
                val state = queueEditMutex.withLock { withContext(queueIoDispatcher) { pc.getState() } }
                    ?: error("Cannot confirm queue removals")
                kotify.api.playerstatus.queueViewOf(state)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LokiLogger.e(TAG, "confirm queue removals", e)
                null
            }
            if (SessionHolder.player !== pc || isOffline.value) return
            if (view == null || view.entries.any { it.track.qid in completed }) {
                AppMessages.show(R.string.error_remove_queue)
            }
            pendingQueueRemovals.removeAll(completed)
            completed.clear()
            if (generation == queueReadGeneration) applyQueue(view ?: latestQueueView, null)
            queueReadGeneration++
        }
    } finally {
        pendingQueueRemovals.clear()
    }
}

/** Hide the visible next row so two quick presses walk successive entries before the echo. */
internal fun PlaybackViewModel.popQueueEntryForSkip(): TrackInfo? {
    val next = _queue.value.firstOrNull() ?: return null
    val qid = next.qid?.takeIf { it.substringBefore(":::").isNotBlank() } ?: return null
    _queue.value = _queue.value.drop(1)
    _queuedCount.value = (_queuedCount.value - 1).coerceAtLeast(0)
    queueReadGeneration++
    pendingQueueSkips += qid
    queueSkipConfirmed = false
    return next
}

/** Keep skipped rows hidden once the latest skip lands, or show them again if it failed. */
internal fun PlaybackViewModel.settleQueueSkip(request: Long, accepted: Boolean) {
    if (request != queueSkipRequest || pendingQueueSkips.isEmpty()) return
    if (accepted) {
        queueSkipConfirmed = true
        retireConfirmedQueueSkips(latestQueueView)
        return
    }
    pendingQueueSkips.clear()
    queueSkipConfirmed = false
    queueReadGeneration++
    refreshQueue()
}

private fun PlaybackViewModel.retireConfirmedQueueSkips(view: QueueView) {
    if (!queueSkipConfirmed) return
    val present = view.entries.map { it.track.qid }.toSet()
    pendingQueueSkips.retainAll(present)
}

/**
 * Move [qid] next to [anchorQid] in the server's current order. An index from the displayed list
 * would be off by any removal or move the server has not applied yet.
 */
internal suspend fun PlaybackViewModel.sendQueueMove(
    pc: PlayerConnect,
    qid: String,
    anchorQid: String,
    afterAnchor: Boolean,
): Boolean = queueEditMutex.withLock {
    withContext(queueIoDispatcher) {
        val entries = pc.getState()?.next_tracks ?: return@withContext false
        val from = entries.indexOfFirst { it.qid == qid }
        val anchor = entries.indexOfFirst { it.qid == anchorQid }
        if (from < 0 || anchor < 0) return@withContext false
        val target = anchor - (if (from < anchor) 1 else 0) + (if (afterAnchor) 1 else 0)
        pc.moveInQueue(qid, target)
    }
}

private data class ParsedQueueTrack(val info: TrackInfo, val needsFetch: Boolean)

/**
 * Build the displayed queue from [view], filling blanks from the cache and, if [songApi] is
 * given, decorating whatever is still missing in one request.
 */
internal suspend fun PlaybackViewModel.applyQueue(view: QueueView, songApi: Song?) = withContext(Dispatchers.Main) {
    latestQueueView = view
    retireConfirmedQueueSkips(view)
    val hidden = pendingQueueRemovals + pendingQueueSkips
    _queuedCount.value = view.queued.count { it.track.qid !in hidden }
    val parsed = view.entries.map { (qt, rawIndex) ->
        val cached = decoratedByUri[qt.uri]
        val name = qt.name?.takeIf { it.isNotEmpty() } ?: cached?.name
        val artist = qt.artistName?.takeIf { it.isNotEmpty() } ?: cached?.artistName
        val art = normalizeSpfyImageUrl(qt.imageUrl) ?: normalizeSpfyImageUrl(cached?.imageUrl)
        val info = TrackInfo(
            uri = qt.uri,
            name = name ?: "Unknown",
            artist = artist ?: "Unknown",
            albumArt = art,
            durationMs = if (qt.durationMs > 0) qt.durationMs else cached?.durationMs ?: 0L,
            uid = qt.uid,
            qid = qt.qid,
            queueIndex = rawIndex,
            isRecommended = qt.metadata["provider"] == "enhanced_recommendation",
        )
        ParsedQueueTrack(info, name.isNullOrEmpty() || artist.isNullOrEmpty() || art == null)
    }
    _queue.value = parsed.map { it.info }.filterNot { it.qid in hidden }

    val missing = parsed.filter { it.needsFetch }.map { it.info.uri }.distinct()
    if (missing.isEmpty() || songApi == null) {
        LokiLogger.i(TAG, "Queue: ${parsed.size} rows, ${missing.size} missing, none fetched")
        return@withContext
    }

    // One request for the rest, the way the web player fills a queue. Spfy populates metadata
    // for only the first entry or two, so this was once a getSong per row awaited together.
    val started = System.currentTimeMillis()
    val decorated = try {
        withContext(queueIoDispatcher) { songApi.decorateTracks(missing) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        LokiLogger.e(TAG, "refreshQueue decorate", e)
        emptyList()
    }
    decorated.forEach { decoratedByUri[it.uri] = it }
    LokiLogger.i(
        TAG,
        "Queue: ${parsed.size} rows, ${missing.size} needed metadata, " +
            "${decorated.size} decorated in ${System.currentTimeMillis() - started}ms"
    )
    if (decorated.isEmpty()) return@withContext
    val byUri = decorated.associateBy { it.uri }
    // Metadata enriches current rows, never reinstates the snapshot fetched before a swipe.
    _queue.value = _queue.value.map { info ->
        val d = byUri[info.uri] ?: return@map info
        info.copy(
            name = d.name ?: info.name,
            artist = d.artistName ?: info.artist,
            albumArt = normalizeSpfyImageUrl(d.imageUrl) ?: info.albumArt,
            durationMs = if (d.durationMs > 0) d.durationMs else info.durationMs,
        )
    }
}
