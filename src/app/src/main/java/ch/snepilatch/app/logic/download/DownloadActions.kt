package ch.snepilatch.app.logic.download

import ch.snepilatch.app.R
import ch.snepilatch.app.data.TrackInfo
import ch.snepilatch.app.logic.playback.MusicPlaybackService
import ch.snepilatch.app.logic.shared.AppSettings
import ch.snepilatch.app.logic.shared.LokiLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * Starting, cancelling and removing downloads, and the auto-save of a track played to the end
 * (#900). Each download runs in the scope the caller passes, the playback view model's, so it
 * ends with it as before.
 */
object DownloadActions {

    private const val TAG = "DownloadActions"

    /** How much of a track has to have played before it counts as listened through. */
    private const val LISTENED_THROUGH_FRACTION = 0.9

    /** The download request for a track, so the three entry points cannot drift in what they send. */
    private fun TrackInfo.toRequest(
        capture: MusicPlaybackService.Capture? = null,
        localOnly: Boolean = false,
        contextUri: String? = null,
        contextName: String? = null,
        contextType: String? = null,
        contextImageUrl: String? = null,
    ) = DownloadRequest(
        trackUri = uri,
        title = name,
        artist = artist,
        // TrackInfo.albumName is only populated for podcast episodes, so for music the album the user
        // downloaded from is the one thing that knows the name. Without this no download ever carried
        // an ALBUM tag, even though both taggers write one.
        album = albumName ?: contextName?.takeIf { contextType == "album" },
        coverUrl = albumArt,
        durationMs = durationMs,
        capture = capture,
        localOnly = localOnly,
        contextUri = contextUri,
        contextName = contextName,
        contextType = contextType,
        contextImageUrl = contextImageUrl,
    )

    /** Tells the user a download went nowhere because there is still no folder to put it in. */
    private fun warnNoFolder(context: android.content.Context, outcome: DownloadOutcome, title: String) {
        if (outcome is DownloadOutcome.NoFolder) {
            DownloadNotifier.failed(context, title, context.getString(R.string.download_needs_folder))
        }
    }

    /**
     * Keeps a track the user listened through, when the setting is on.
     *
     * A track played to the end has already been decoded in full, and the audio chain kept those
     * samples, so this claims them and re-encodes rather than downloading the song a second time
     * from somewhere else. The claim is synchronous because the buffer is about to be handed to the
     * incoming track; only the encoding is deferred.
     */
    fun autoSaveIfListenedThrough(scope: CoroutineScope, track: TrackInfo?, positionMs: Long) {
        if (track == null || !worthAutoSaving(track, positionMs)) return
        val context = MusicPlaybackService.instance ?: return
        // The encoded bytes win when the playback cache has them, so don't claim a capture that
        // would only be thrown away: detaching costs the tap a fresh buffer for the next track.
        val capture = if (TrackDownloader.needsCapture(track.uri)) {
            context.detachCapture(track.uri, track.durationMs) ?: run {
                // Never re-fetch here. The point of this setting is to keep the recording that was
                // just played; downloading somebody else's upload instead is a different file, and
                // it spends data to get something worse.
                LokiLogger.i(TAG, "listened through '${track.name}' but it wasn't captured in full — not saving")
                return
            }
        } else {
            null
        }
        LokiLogger.i(TAG, "listened through '${track.name}', saving from ${if (capture != null) "the capture" else "the playback cache"}")
        downloadTrack(scope, track, context, capture, localOnly = true)
    }

    /**
     * Arms the decoded-PCM capture for the track now loading, or stands it down when the setting is off.
     *
     * Armed for every source, not just Widevine, because whether the playback cache will hold this
     * stream is not known yet: a selected source is no guarantee, since Deezer is encrypted and plays
     * through the loopback proxy with no cache key, so those tracks do need the capture.
     * [standDownCapture] drops it again at the point that turns out otherwise. That costs an allocate
     * and free of the ~69MB buffer per track on the sources that do cache — the price of not guessing.
     *
     * Must run on every track change: the instant-tap path returns before resolveAndPlay's own call,
     * and without its own the captured uri stayed on the previous track while the buffer filled with
     * the new one, so neither could be saved.
     *
     * Safe to call twice for one track: [MusicPlaybackService.startCapture] leaves an armed capture
     * alone, so a second call can only stand a stale one down.
     */
    fun armCapture(trackUri: String, durationMs: Long) {
        val service = MusicPlaybackService.instance ?: return
        if (AppSettings.autoSaveListened.value) {
            service.startCapture(trackUri, durationMs)
        } else {
            service.stopCapture()
        }
    }

    /**
     * Drops the decoded capture once we know the playback cache is taking these bytes. The cached
     * encoded stream remuxes out byte-identical, while the capture is a re-encode of the decoded
     * samples, so [autoSaveIfListenedThrough] would take the cache and discard the capture anyway —
     * after paying a memcpy of every decoded buffer on the audio thread for the whole track.
     */
    fun standDownCapture() { MusicPlaybackService.instance?.stopCapture() }

    /** The cheap checks: setting on, somewhere to put it, played far enough, not already saved. */
    private fun worthAutoSaving(track: TrackInfo, positionMs: Long): Boolean {
        if (!AppSettings.autoSaveListened.value) return false
        if (track.durationMs <= 0) return false
        if (!DownloadFolder.isConfigured) return false
        if (positionMs < track.durationMs * LISTENED_THROUGH_FRACTION) return false
        return Downloads.find(track.uri) == null
    }

    /**
     * Downloads one track, with its own progress notification.
     *
     * [localOnly] saves are the auto-save path: they never touch the network and finish in the time
     * it takes to encode, so they stay off the manager's active list rather than flashing a card.
     */
    fun downloadTrack(
        scope: CoroutineScope,
        track: TrackInfo,
        context: android.content.Context,
        capture: MusicPlaybackService.Capture? = null,
        localOnly: Boolean = false,
    ) {
        // applicationContext, or a batch outlives the Activity that started it and pins it — and its
        // whole Compose tree — for the minutes the download runs. Nothing here needs an Activity.
        val ctx = context.applicationContext
        // A localOnly save is the auto-save path: it re-encodes what was played rather than fetching,
        // so it shows as its own kind of entry. It no longer has to wait for an idle slot — every
        // entry carries its own progress now.
        val id = DownloadQueue.enqueue(
            name = track.name,
            type = if (localOnly) DownloadQueue.TYPE_REENCODE else "single",
            imageUrl = track.albumArt,
            total = 1,
        )
        val job = scope.launch(Dispatchers.IO) {
            val progress: ((Int) -> Unit)? = if (localOnly) {
                null
            } else {
                { percent ->
                    DownloadQueue.updateJob(id, done = 1, trackPercent = percent)
                    DownloadQueue.reportTrack(track.uri, percent)
                }
            }
            var state = DownloadQueue.QueueEntry.State.Failed
            try {
                val outcome = TrackDownloader.download(
                    track.toRequest(capture, localOnly), ctx, onProgress = progress
                )
                if (outcome is DownloadOutcome.Done) state = DownloadQueue.QueueEntry.State.Done
                warnNoFolder(ctx, outcome, track.name)
            } catch (e: CancellationException) {
                state = DownloadQueue.QueueEntry.State.Cancelled
                throw e
            } finally {
                DownloadQueue.clearTrack(track.uri)
                // In a finally because cancellation (backing out of the app) has to settle the entry
                // too; it used to be left running, so the tab claimed a download that had stopped.
                DownloadQueue.finishJob(id, state)
            }
        }
        keep(id, job)
    }

    /**
     * Downloads a whole album or playlist, one track at a time so the per-track notifications are
     * replaced by a single count. Tracks already on disk are skipped by the downloader itself.
     */
    /** Every running download by queue id, so one entry can be stopped without touching the others. */
    private val downloadJobs = java.util.concurrent.ConcurrentHashMap<Int, Job>()

    private fun keep(id: Int, job: Job) {
        // Prune here rather than when a download ends: removing from inside the job races the put
        // that registered it, and a job that removed itself first would linger for good.
        downloadJobs.values.removeAll { it.isCompleted }
        downloadJobs[id] = job
    }

    /** Stops one queue entry. It settles its own notification and state on the way out. */
    fun cancel(id: Int) { downloadJobs[id]?.cancel() }

    fun downloadTracks(
        scope: CoroutineScope,
        tracks: List<TrackInfo>,
        context: android.content.Context,
        contextUri: String? = null,
        contextName: String? = null,
        contextType: String? = null,
        contextImageUrl: String? = null,
    ) {
        if (tracks.isEmpty()) return
        val ctx = context.applicationContext
        val id = DownloadQueue.enqueue(
            name = contextName ?: tracks.first().name,
            type = contextType ?: "single",
            imageUrl = contextImageUrl ?: tracks.first().albumArt,
            total = tracks.size,
        )
        val job = scope.launch(Dispatchers.IO) {
            val failed = mutableListOf<String>()
            var state = DownloadQueue.QueueEntry.State.Done
            try {
                tracks.forEachIndexed { index, track ->
                    // Between tracks, not mid-file: a paused entry stops before starting the next one
                    // rather than abandoning bytes already fetched.
                    DownloadQueue.awaitResume(id)
                    DownloadNotifier.batch(ctx, track.name, index + 1, tracks.size)
                    // Cleared in a finally: a cancelled batch unwinds before the call returns, and a
                    // percentage left behind would freeze that row's ring for the rest of the process.
                    val outcome = try {
                        TrackDownloader.download(
                            track.toRequest(
                                contextUri = contextUri,
                                contextName = contextName,
                                contextType = contextType,
                                contextImageUrl = contextImageUrl,
                            ),
                            ctx,
                            notify = false,
                        ) { percent ->
                            DownloadQueue.updateJob(id, index + 1, percent)
                            DownloadQueue.reportTrack(track.uri, percent)
                            DownloadNotifier.batch(ctx, track.name, index + 1, tracks.size, percent)
                        }
                    } finally {
                        DownloadQueue.clearTrack(track.uri)
                    }
                    if (outcome !is DownloadOutcome.Done) failed += track.name
                    if (outcome is DownloadOutcome.NoFolder) {
                        warnNoFolder(ctx, outcome, track.name)
                        state = DownloadQueue.QueueEntry.State.Failed
                        return@launch
                    }
                }
                if (failed.size == tracks.size) state = DownloadQueue.QueueEntry.State.Failed
                DownloadNotifier.batchFinished(ctx, tracks.size, failed)
            } catch (e: CancellationException) {
                // The batch posts its own ongoing notification, so notify=false keeps TrackDownloader
                // from clearing it. Below API 34 an ongoing bar is not user-swipeable, so a batch
                // cancelled with the Activity would leave "Downloading 7 of 30" posted for a download
                // that is not running.
                DownloadNotifier.clear(ctx)
                state = DownloadQueue.QueueEntry.State.Cancelled
                throw e
            } finally {
                DownloadQueue.finishJob(id, state)
            }
        }
        keep(id, job)
    }

    fun removeDownload(scope: CoroutineScope, trackUri: String) {
        scope.launch(Dispatchers.IO) { TrackDownloader.delete(trackUri) }
    }
}
