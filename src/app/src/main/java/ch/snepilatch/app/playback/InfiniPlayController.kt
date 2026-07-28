package ch.snepilatch.app.playback

import ch.snepilatch.app.util.LokiLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Waveform-native, seamless Eternal InfiniPlay — no Spfy beats.
 *
 * On enable it restarts the track and lets it play through once, capturing the decoded PCM live from
 * the audio pipeline (no separate download). When the whole track is captured it analyses the raw
 * waveform for self-similar sections ([WaveformAnalyzer]) and hands playback to [PcmInfiniPlayEngine],
 * which plays from the captured PCM through its own AudioTrack and crossfades between matched points —
 * genuinely seamless because we own every sample. So the song plays once, then becomes eternal.
 *
 * Credits: a port of the Infinite/Eternal InfiniPlay idea (Pithaya's Spicetify app, Paul Lamere's
 * Infinite Jukebox, UnderMybrella's EternalInfiniPlay) — reimagined to run entirely off the waveform.
 */
/**
 * Live snapshot of the infiniPlay for the UI "remix map": [buckets] is per-time-slice similarity density
 * (0..1), [bufferedFraction] how much of the track is captured, [playheadFraction] where we're reading
 * from, and [remixing] whether it has passed the centre and started jumping.
 */
class InfiniPlayViz(
    val buckets: FloatArray,
    val bufferedFraction: Float,
    val playheadFraction: Float,
    val remixing: Boolean,
    /** Per-slice replay heat (0..1): how often the remix has looped back into that part. */
    val heat: FloatArray = FloatArray(0)
)

class InfiniPlayController(
    private val scope: CoroutineScope,
    private val currentTrackId: () -> String?,
) {
    private companion object {
        const val TAG = "InfiniPlay"
        const val CAPTURE_STALL_TICKS = 12 // ~12s of no new audio => proceed with what we have
        const val FALLBACK_HANDOFF_MS = 45_000L // used only when the track duration is unknown

        // Hand off once this much is captured (jumps become possible; most beats still play through).
        const val HANDOFF_AFTER_MS = 30_000L
        const val MIN_PARALLELS_FOR_HANDOFF = 24
        const val REANALYZE_GROWTH_S = 30 // re-analyse + enrich jumps every +30s of newly captured audio
        const val VIZ_BUCKETS = 56 // number of pillars in the remix map
        const val VIZ_TICK_MS = 200L // how often the remix map refreshes (InfiniPlayTimeline eases between ticks)
        const val PREVIEW_START_S = 10 // run the first similarity search ~10s in (populates the remix map)

        // Refresh cadence for the preview similarities until the centre handoff. Was 5s, but each refresh
        // is a whole-buffer FFT + O(n^2) pass feeding only the cosmetic 56-bar histogram, so ~18s cuts the
        // reanalysis count ~4x with no correctness/handoff impact.
        const val PREVIEW_EVERY_S = 5 // refresh the similarity search every +5s of captured audio
        const val HANDOFF_OVERLAP_MS = 180L // overlap engine + ExoPlayer briefly so the takeover has no gap
        const val LOOP_MARGIN_MS = 3000L // seek the muted keep-alive player back this far before the end
    }

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled

    private val _viz = MutableStateFlow<InfiniPlayViz?>(null)
    val viz: StateFlow<InfiniPlayViz?> = _viz

    private var job: Job? = null
    private var vizJob: Job? = null
    private var loopGuardJob: Job? = null

    @Volatile private var remixing = false

    // Similarity density shown on the remix map BEFORE the engine takes over (during first-half capture).
    @Volatile private var previewBuckets: IntArray? = null

    @Volatile private var paused = false

    @Volatile private var capturing = false

    fun isEnabled(): Boolean = _enabled.value

    fun enable(trackUri: String) {
        disable()
        val trackId = trackUri.substringAfterLast(":")
        _enabled.value = true
        job = scope.launch(Dispatchers.Default) {
            try {
                runSeamlessInfiniPlay(trackId)
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (t: Throwable) {
                val where = t.stackTrace.firstOrNull()?.let { "${it.className}.${it.methodName}:${it.lineNumber}" }
                LokiLogger.e(TAG, "infiniPlay failed: ${t.javaClass.name}: ${t.message} @ $where", RuntimeException(t))
                disable()
            }
        }
    }

    fun disable() {
        _enabled.value = false
        val wasRunning = remixing
        remixing = false
        MusicPlaybackService.instance?.setInfiniPlayRemixEngaged(false)
        vizJob?.cancel()
        vizJob = null
        loopGuardJob?.cancel()
        loopGuardJob = null
        capturing = false
        _viz.value = null
        previewBuckets = null
        paused = false
        MusicPlaybackService.instance?.let {
            it.setInfiniPlayAnalyzing(false)
            it.setInfiniPlayPositionSource(null)
            it.setInfiniPlayStopHook(null)
            it.setInfiniPlayPauseHook(null)
            it.setInfiniPlayRemixing(false)
            if (wasRunning) it.infiniPlayRestorePlayback() // unmute the underlying player we handed off from
        }
        job?.cancel()
        job = null
    }

    private suspend fun runSeamlessInfiniPlay(trackId: String) {
        val svc = MusicPlaybackService.instance ?: run {
            LokiLogger.i(TAG, "no service — infiniPlay off")
            _enabled.value = false
            return
        }

        // 1. Capture pass: restart the track and record the whole thing.
        paused = false
        capturing = true
        svc.setInfiniPlayStopHook { disable() } // tear the engine down on any skip / app teardown
        svc.setInfiniPlayPauseHook { p ->
            paused = p
            // Nothing to do: the remix is a filter in the player's chain, so it pauses with it.
        }
        svc.setInfiniPlayRemixing(true) // hide the notification seekbar for the whole session (no auto-advance)
        // Enable analysis BEFORE seeking: the tap's isActive() is re-read on the seek's pipeline flush,
        // so analyzing must already be true for the flush to add the tap back into the audio chain.
        svc.setInfiniPlayAnalyzing(true)
        svc.setInfiniPlayRemixEngaged(true) // join the audio chain on the seek below
        svc.infiniPlaySeekToStart()
        LokiLogger.i(TAG, "capture pass started for $trackId")

        val rate = awaitRate(svc) ?: run {
            _enabled.value = false
            return
        }
        val durMs = withContext(Dispatchers.Main) { svc.infiniPlayDurationMs() }
        // Let the song play through to its centre before going eternal, so the first half is heard
        // normally; only then does it start (optionally) switching. Falls back to a fixed point if the
        // duration isn't known yet.
        val captureMs = if (durMs > 0) minOf(durMs / 2, HANDOFF_AFTER_MS) else FALLBACK_HANDOFF_MS
        val targetFrames = (captureMs * rate / 1000).toInt()
        val totalFrames = if (durMs > 0) (durMs * rate / 1000).toInt() else targetFrames * 2
        startViz(svc, rate, totalFrames) // drive the remix-map UI (buffering, similarities, playhead)

        var lastFrames = 0
        var stall = 0
        var lastPreview = 0
        var previewParallels = 0
        var done = false
        while (!done && currentCoroutineContext().isActive && _enabled.value) {
            if (currentTrackId() != trackId) {
                LokiLogger.i(TAG, "track changed during capture — off")
                disable()
                return
            }
            val cf = svc.infiniPlayCapturedFrames()
            if (!paused) {
                stall = if (cf == lastFrames) stall + 1 else 0
                lastFrames = cf
                // Early preview: run the first similarity search ~10s in, then refresh, so the remix map
                // shows found similarities while the first half is still playing normally.
                val firstPreview = lastPreview == 0 && cf >= PREVIEW_START_S * rate
                if (firstPreview || (lastPreview > 0 && cf - lastPreview >= PREVIEW_EVERY_S * rate)) {
                    val pMono = svc.infiniPlaySnapshotMono().let { if (it.size > cf) it.copyOf(cf) else it }
                    val pr = BeatGraph.analyse(pMono, rate)
                    previewBuckets = bucketsFromJumps(pr.jumps, totalFrames)
                    previewParallels = pr.branchPoints
                    lastPreview = cf
                    val msg = "preview @${cf / rate}s: ${pr.branchPoints} branch points, " +
                        "${pr.beats} beats @ ${pr.bpm} BPM"
                    LokiLogger.i(TAG, msg)
                }
            }
            when {
                paused -> delay(300) // hold the capture where it is while paused
                readyToHandOff(cf, targetFrames, rate, previewParallels, durMs) -> done = true
                stall >= CAPTURE_STALL_TICKS -> {
                    LokiLogger.i(TAG, "capture stalled at ${cf / rate}s — using partial")
                    done = true
                }
                else -> delay(1000)
            }
        }
        if (!_enabled.value) return
        handOffToEngine(svc, rate, durMs, trackId)
    }

    /** Past the capture target with a usable jump table, or past the half-track mark. */
    private fun readyToHandOff(
        captured: Int,
        targetFrames: Int,
        rate: Int,
        parallels: Int,
        durMs: Long
    ): Boolean {
        if (captured >= targetFrames - rate && parallels >= MIN_PARALLELS_FOR_HANDOFF) return true
        val halfFrames = if (durMs > 0) (durMs / 2 * rate / 1000).toInt() else Int.MAX_VALUE
        return captured >= halfFrames - rate
    }

    /** Analyse the captured opening, start the seamless engine, and keep enriching it to the full track. */
    private suspend fun handOffToEngine(svc: MusicPlaybackService, rate: Int, durMs: Long, trackId: String) {
        // 2. Analyse the captured waveform for self-similar sections.
        val mono = svc.infiniPlaySnapshotMono()
        val res = BeatGraph.analyse(mono, rate)
        val captured = "captured ${mono.size / rate}s, ${res.beats} beats @ ${res.bpm} BPM, " +
            "${res.branchPoints} branch points"
        LokiLogger.i(TAG, captured)
        if (res.jumps.isEmpty()) {
            LokiLogger.i(TAG, "no branches found — infiniPlay off")
            disable()
            return
        }

        // 3. Hand off to the seamless PCM engine using the opening we've captured so far.
        val ch = svc.infiniPlayChannels().coerceAtLeast(1)
        val snap = snapshotOf(svc, ch, res)
        LokiLogger.i(TAG, "last branch point at ${res.lastBranchFrame / rate}s")
        // Start the engine exactly where the user is currently hearing the song, so the takeover is
        // inaudible — the engine replays the same samples, then starts wandering via crossfaded jumps.
        // Both must be read on the player's thread.
        val posMs = withContext(Dispatchers.Main) { svc.getCurrentPosition() }
        val startFrame = (posMs * rate / 1000).toInt().coerceIn(0, maxOf(0, snap.frames - 1))

        if (snap.jumps.isEmpty()) {
            LokiLogger.i(TAG, "no usable jumps — off")
            disable()
            return
        }
        // Hand the stream to the remix filter. No mute, no second track, no overlap window: the next
        // buffer the sink pulls simply comes from the remix instead of the decoder.
        remixing = true
        svc.setInfiniPlayRemix(snap, startFrame)
        svc.setInfiniPlayPositionSource { svc.infiniPlayRemixPlayheadMs() } // scrubber follows the remix
        startLoopGuard(svc, durMs) // keep the decoder fed: it must not run off the end of the track
        LokiLogger.i(TAG, "remix running (${snap.frames / rate}s buffer, ${snap.jumps.size} jump srcs)")

        // 4. Keep decoding the rest of the track in the background (still muted) and swap richer
        //    snapshots into the engine as more loads, so it isn't confined to the opening window.
        growToFullTrack(svc, trackId, rate, ch, durMs)
    }

    /** Snapshot the captured PCM (clamped to one clean playthrough) + its jump table for the engine. */
    private fun snapshotOf(
        svc: MusicPlaybackService,
        ch: Int,
        res: BeatGraph.Analysis
    ): InfiniPlayRemixProcessor.Snapshot {
        val inter = svc.infiniPlaySnapshotInterleaved()
        val frames = inter.size / ch
        return InfiniPlayRemixProcessor.Snapshot(inter, frames, res.jumps, res.lastBranchFrame, res.endJumpFrame)
    }

    private suspend fun growToFullTrack(
        svc: MusicPlaybackService,
        trackId: String,
        rate: Int,
        ch: Int,
        durMs: Long
    ) {
        if (durMs <= 0) {
            svc.setInfiniPlayAnalyzing(false)
            capturing = false
            return
        }
        val fullFrames = (durMs * rate / 1000).toInt()
        var analyzedFrames = svc.infiniPlayCapturedFrames()
        while (currentCoroutineContext().isActive && _enabled.value) {
            if (currentTrackId() != trackId) {
                disable()
                return
            }
            val cap = svc.infiniPlayCapturedFrames().coerceAtMost(fullFrames)
            val complete = cap >= fullFrames - rate
            if (complete || cap - analyzedFrames >= rate * REANALYZE_GROWTH_S) {
                val mono = svc.infiniPlaySnapshotMono().let { if (it.size > cap) it.copyOf(cap) else it }
                val res = BeatGraph.analyse(mono, rate)
                if (res.jumps.isNotEmpty()) {
                    val interFull = svc.infiniPlaySnapshotInterleaved()
                    val frames = minOf(interFull.size / ch, cap)
                    val inter = if (interFull.size > frames * ch) interFull.copyOf(frames * ch) else interFull
                    // startFrame omitted: the remix keeps playing where it is, just with more to work with.
                    svc.setInfiniPlayRemix(
                        InfiniPlayRemixProcessor.Snapshot(
                            inter, frames, res.jumps, res.lastBranchFrame, res.endJumpFrame
                        )
                    )
                    val grew = "grew to ${frames / rate}s, ${res.branchPoints} branch points, " +
                        "last branch ${res.lastBranchFrame / rate}s"
                    LokiLogger.i(TAG, grew)
                }
                analyzedFrames = cap
            }
            if (complete) {
                svc.setInfiniPlayAnalyzing(false)
                capturing = false
                LokiLogger.i(TAG, "full track captured (${cap / rate}s) — capture off")
                return
            }
            delay(2000)
        }
    }

    /**
     * Loop the muted keep-alive player in code instead of using ExoPlayer's repeat (which sometimes fails
     * to loop and lets the track end): once capture is done, seek back to the start whenever it nears the
     * end, so it never actually ends. Skipped while paused or still capturing (so it plays through once).
     */
    private fun startLoopGuard(svc: MusicPlaybackService, durMs: Long) {
        if (durMs <= 0) return
        loopGuardJob?.cancel()
        loopGuardJob = scope.launch(Dispatchers.Default) {
            while (currentCoroutineContext().isActive && _enabled.value) {
                delay(1000)
                if (paused || capturing) continue
                val pos = withContext(Dispatchers.Main) { svc.infiniPlayRawPositionMs() }
                if (pos >= durMs - LOOP_MARGIN_MS) {
                    svc.infiniPlaySeekToStart()
                    LokiLogger.i(TAG, "loop-guard: near end (${pos / 1000}s) — seeking to start")
                }
            }
        }
    }

    /** Publish the remix-map state (buffered fraction, similarity density, playhead) a few times a sec. */
    private fun startViz(svc: MusicPlaybackService, rate: Int, totalFrames: Int) {
        vizJob?.cancel()
        vizJob = scope.launch(Dispatchers.Default) {
            val total = maxOf(1, totalFrames)
            var lastPosFrames = -1
            var lastBuffered = -1
            var lastRemixing: Boolean? = null
            var lastBuckets: FloatArray? = null
            var lastHeat: FloatArray? = null
            while (currentCoroutineContext().isActive && _enabled.value) {
                // Frozen while paused: the playhead doesn't move and buckets don't change, so skip the
                // recompute + emit entirely and just idle until resume.
                if (paused) {
                    delay(VIZ_TICK_MS)
                    continue
                }
                val live = remixing
                val posMs = if (live) {
                    svc.infiniPlayRemixPlayheadMs()
                } else {
                    withContext(Dispatchers.Main) { svc.getCurrentPosition() }
                }
                val posFrames = (posMs * rate / 1000).toInt()
                val buffered = svc.infiniPlayCapturedFrames().coerceIn(0, total)
                val buckets = normalizeBuckets(svc.infiniPlayRemixBuckets(VIZ_BUCKETS, total) ?: previewBuckets)
                val heat = normalizeBuckets(svc.infiniPlayRemixVisits(VIZ_BUCKETS, total) ?: IntArray(0))
                // Emit (and trigger downstream recomposition) only when something observable changed.
                val changed = lastBuckets == null ||
                    posFrames != lastPosFrames ||
                    buffered != lastBuffered ||
                    live != lastRemixing ||
                    !buckets.contentEquals(lastBuckets) ||
                    !heat.contentEquals(lastHeat)
                if (changed) {
                    _viz.value = InfiniPlayViz(
                        buckets = buckets,
                        bufferedFraction = buffered.toFloat() / total,
                        playheadFraction = (posFrames.toFloat() / total).coerceIn(0f, 1f),
                        remixing = live,
                        heat = heat
                    )
                    lastPosFrames = posFrames
                    lastBuffered = buffered
                    lastRemixing = live
                    lastBuckets = buckets
                    lastHeat = heat
                }
                delay(VIZ_TICK_MS)
            }
            _viz.value = null
        }
    }

    private fun normalizeBuckets(raw: IntArray?): FloatArray {
        if (raw == null) return FloatArray(VIZ_BUCKETS)
        var mx = 0
        for (v in raw) if (v > mx) mx = v
        if (mx == 0) return FloatArray(VIZ_BUCKETS)
        return FloatArray(VIZ_BUCKETS) { raw[it].toFloat() / mx }
    }

    /** Similarity density per time-slice from raw parallels — for the pre-handoff remix-map preview. */
    /** Remix-map density before takeover: how many destinations each slice can branch to. */
    private fun bucketsFromJumps(jumps: List<InfiniPlayRemixProcessor.Jump>, totalFrames: Int): IntArray {
        val out = IntArray(VIZ_BUCKETS)
        val span = maxOf(1, totalFrames)
        for (j in jumps) {
            val b = (j.src.toLong() * VIZ_BUCKETS / span).toInt().coerceIn(0, VIZ_BUCKETS - 1)
            out[b] += j.dsts.size
        }
        return out
    }

    private suspend fun awaitRate(svc: MusicPlaybackService): Int? {
        var waited = 0
        while (currentCoroutineContext().isActive && _enabled.value) {
            val r = svc.infiniPlaySampleRate()
            if (r > 0 && svc.infiniPlayCapturedFrames() > 0) return r
            delay(500)
            waited++
            if (waited > 40) {
                LokiLogger.i(TAG, "no audio captured (20s) — infiniPlay off")
                return null
            }
        }
        return null
    }
}
