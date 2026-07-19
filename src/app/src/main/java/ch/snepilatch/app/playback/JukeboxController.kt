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
 * Waveform-native, seamless Eternal Jukebox — no Spotify beats.
 *
 * On enable it restarts the track and lets it play through once, capturing the decoded PCM live from
 * the audio pipeline (no separate download). When the whole track is captured it analyses the raw
 * waveform for self-similar sections ([WaveformAnalyzer]) and hands playback to [PcmJukeboxEngine],
 * which plays from the captured PCM through its own AudioTrack and crossfades between matched points —
 * genuinely seamless because we own every sample. So the song plays once, then becomes eternal.
 *
 * Credits: a port of the Infinite/Eternal Jukebox idea (Pithaya's Spicetify app, Paul Lamere's
 * Infinite Jukebox, UnderMybrella's EternalJukebox) — reimagined to run entirely off the waveform.
 */
/**
 * Live snapshot of the jukebox for the UI "remix map": [buckets] is per-time-slice similarity density
 * (0..1), [bufferedFraction] how much of the track is captured, [playheadFraction] where we're reading
 * from, and [remixing] whether it has passed the centre and started jumping.
 */
class JukeboxViz(
    val buckets: FloatArray,
    val bufferedFraction: Float,
    val playheadFraction: Float,
    val remixing: Boolean
)

class JukeboxController(
    private val scope: CoroutineScope,
    private val currentTrackId: () -> String?,
) {
    private companion object {
        const val TAG = "Jukebox"
        const val CAPTURE_STALL_TICKS = 12 // ~12s of no new audio => proceed with what we have
        const val FALLBACK_HANDOFF_MS = 45_000L // used only when the track duration is unknown
        const val REANALYZE_GROWTH_S = 30 // re-analyse + enrich jumps every +30s of newly captured audio
        const val VIZ_BUCKETS = 56 // number of pillars in the remix map
        const val VIZ_TICK_MS = 120L // how often the remix map refreshes
        const val PREVIEW_START_S = 10 // run the first similarity search ~10s in (populates the remix map)
        const val PREVIEW_EVERY_S = 5 // refresh the preview similarities every +5s until the centre handoff
        const val HANDOFF_OVERLAP_MS = 180L // overlap engine + ExoPlayer briefly so the takeover has no gap
        const val LOOP_MARGIN_MS = 3000L // seek the muted keep-alive player back this far before the end
    }

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled

    private val _viz = MutableStateFlow<JukeboxViz?>(null)
    val viz: StateFlow<JukeboxViz?> = _viz

    private var job: Job? = null
    private var vizJob: Job? = null
    private var loopGuardJob: Job? = null

    @Volatile private var engine: PcmJukeboxEngine? = null

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
                runSeamlessJukebox(trackId)
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (t: Throwable) {
                val where = t.stackTrace.firstOrNull()?.let { "${it.className}.${it.methodName}:${it.lineNumber}" }
                LokiLogger.e(TAG, "jukebox failed: ${t.javaClass.name}: ${t.message} @ $where", RuntimeException(t))
                disable()
            }
        }
    }

    fun disable() {
        _enabled.value = false
        val wasRunning = engine != null
        engine?.stop()
        engine = null
        vizJob?.cancel()
        vizJob = null
        loopGuardJob?.cancel()
        loopGuardJob = null
        capturing = false
        _viz.value = null
        previewBuckets = null
        paused = false
        MusicPlaybackService.instance?.let {
            it.setJukeboxAnalyzing(false)
            it.setJukeboxPositionSource(null)
            it.setJukeboxStopHook(null)
            it.setJukeboxPauseHook(null)
            it.setJukeboxRemixing(false)
            if (wasRunning) it.jukeboxRestorePlayback() // unmute the underlying player we handed off from
        }
        job?.cancel()
        job = null
    }

    private suspend fun runSeamlessJukebox(trackId: String) {
        val svc = MusicPlaybackService.instance ?: run {
            LokiLogger.i(TAG, "no service — jukebox off")
            _enabled.value = false
            return
        }

        // 1. Capture pass: restart the track and record the whole thing.
        paused = false
        capturing = true
        svc.setJukeboxStopHook { disable() } // tear the engine down on any skip / app teardown
        svc.setJukeboxPauseHook { p ->
            paused = p
            engine?.setPaused(p)
        }
        svc.setJukeboxRemixing(true) // hide the notification seekbar for the whole session (no auto-advance)
        svc.jukeboxSeekToStart()
        svc.setJukeboxAnalyzing(true)
        LokiLogger.i(TAG, "capture pass started for $trackId")

        val rate = awaitRate(svc) ?: run {
            _enabled.value = false
            return
        }
        val durMs = withContext(Dispatchers.Main) { svc.jukeboxDurationMs() }
        // Let the song play through to its centre before going eternal, so the first half is heard
        // normally; only then does it start (optionally) switching. Falls back to a fixed point if the
        // duration isn't known yet.
        val captureMs = if (durMs > 0) durMs / 2 else FALLBACK_HANDOFF_MS
        val targetFrames = (captureMs * rate / 1000).toInt()
        val totalFrames = if (durMs > 0) (durMs * rate / 1000).toInt() else targetFrames * 2
        startViz(svc, rate, totalFrames) // drive the remix-map UI (buffering, similarities, playhead)

        var lastFrames = 0
        var stall = 0
        var lastPreview = 0
        var done = false
        while (!done && currentCoroutineContext().isActive && _enabled.value) {
            if (currentTrackId() != trackId) {
                LokiLogger.i(TAG, "track changed during capture — off")
                disable()
                return
            }
            val cf = svc.jukeboxCapturedFrames()
            if (!paused) {
                stall = if (cf == lastFrames) stall + 1 else 0
                lastFrames = cf
                // Early preview: run the first similarity search ~10s in, then refresh, so the remix map
                // shows found similarities while the first half is still playing normally.
                if (cf >= PREVIEW_START_S * rate && cf - lastPreview >= PREVIEW_EVERY_S * rate) {
                    val pMono = svc.jukeboxSnapshotMono().let { if (it.size > cf) it.copyOf(cf) else it }
                    val pr = WaveformAnalyzer.analyze(pMono, rate)
                    previewBuckets = bucketsFromParallels(pr.parallels, pr.frameHopSamples, totalFrames)
                    lastPreview = cf
                    LokiLogger.i(TAG, "preview @${cf / rate}s: ${pr.parallels.size} similarities")
                }
            }
            when {
                paused -> delay(300) // hold the capture where it is while paused
                cf >= targetFrames - rate -> done = true
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

    /** Analyse the captured opening, start the seamless engine, and keep enriching it to the full track. */
    private suspend fun handOffToEngine(svc: MusicPlaybackService, rate: Int, durMs: Long, trackId: String) {
        // 2. Analyse the captured waveform for self-similar sections.
        val mono = svc.jukeboxSnapshotMono()
        val res = WaveformAnalyzer.analyze(mono, rate)
        LokiLogger.i(TAG, "captured ${mono.size / rate}s, ${res.frameCount} frames, ${res.parallels.size} parallels")
        if (res.parallels.isEmpty()) {
            LokiLogger.i(TAG, "no waveform matches — jukebox off")
            disable()
            return
        }

        // 3. Hand off to the seamless PCM engine using the opening we've captured so far.
        val ch = svc.jukeboxChannels().coerceAtLeast(1)
        val snap = snapshotOf(svc, ch, res)
        // Start the engine exactly where the user is currently hearing the song, so the takeover is
        // inaudible — the engine replays the same samples, then starts wandering via crossfaded jumps.
        val posMs = withContext(Dispatchers.Main) { svc.getCurrentPosition() }
        val startFrame = (posMs * rate / 1000).toInt().coerceIn(0, maxOf(0, snap.frames - 1))

        val eng = PcmJukeboxEngine(snap, ch, rate, startFrame)
        if (!eng.hasJumps()) {
            LokiLogger.i(TAG, "engine has no usable jumps — off")
            disable()
            return
        }
        engine = eng
        eng.start()
        eng.setPaused(paused)
        svc.setJukeboxPositionSource { eng.positionMs() } // scrubber + Spotify now jump with the audio
        // Let the engine's AudioTrack fill and start sounding (same samples, same position) BEFORE muting
        // ExoPlayer, so the two overlap for a beat instead of leaving a gap — no dropout at the takeover.
        delay(HANDOFF_OVERLAP_MS)
        svc.jukeboxSilentKeepAlive() // mute ExoPlayer; the engine owns the speaker now
        startLoopGuard(svc, durMs) // loop the muted keep-alive player in code (no repeat mode)
        LokiLogger.i(TAG, "seamless engine running (${snap.frames / rate}s buffer, ${snap.jumps.size} jump srcs)")

        // 4. Keep decoding the rest of the track in the background (still muted) and swap richer
        //    snapshots into the engine as more loads, so it isn't confined to the opening window.
        growToFullTrack(svc, eng, trackId, rate, ch, durMs)
    }

    /** Snapshot the captured PCM (clamped to one clean playthrough) + its jump table for the engine. */
    private fun snapshotOf(
        svc: MusicPlaybackService,
        ch: Int,
        res: WaveformAnalyzer.Result
    ): PcmJukeboxEngine.Snapshot {
        val inter = svc.jukeboxSnapshotInterleaved()
        val frames = inter.size / ch
        val jumps = PcmJukeboxEngine.buildJumps(res.parallels, res.frameHopSamples)
        return PcmJukeboxEngine.Snapshot(inter, frames, jumps)
    }

    private suspend fun growToFullTrack(
        svc: MusicPlaybackService,
        eng: PcmJukeboxEngine,
        trackId: String,
        rate: Int,
        ch: Int,
        durMs: Long
    ) {
        if (durMs <= 0) {
            svc.setJukeboxAnalyzing(false)
            capturing = false
            return
        }
        val fullFrames = (durMs * rate / 1000).toInt()
        var analyzedFrames = svc.jukeboxCapturedFrames()
        while (currentCoroutineContext().isActive && _enabled.value) {
            if (currentTrackId() != trackId) {
                disable()
                return
            }
            val cap = svc.jukeboxCapturedFrames().coerceAtMost(fullFrames)
            val complete = cap >= fullFrames - rate
            if (complete || cap - analyzedFrames >= rate * REANALYZE_GROWTH_S) {
                val mono = svc.jukeboxSnapshotMono().let { if (it.size > cap) it.copyOf(cap) else it }
                val res = WaveformAnalyzer.analyze(mono, rate)
                if (res.parallels.isNotEmpty()) {
                    val interFull = svc.jukeboxSnapshotInterleaved()
                    val frames = minOf(interFull.size / ch, cap)
                    val inter = if (interFull.size > frames * ch) interFull.copyOf(frames * ch) else interFull
                    val jumps = PcmJukeboxEngine.buildJumps(res.parallels, res.frameHopSamples)
                    eng.update(PcmJukeboxEngine.Snapshot(inter, frames, jumps))
                    LokiLogger.i(TAG, "grew to ${frames / rate}s, ${res.parallels.size} parallels, ${jumps.size} jump srcs")
                }
                analyzedFrames = cap
            }
            if (complete) {
                svc.setJukeboxAnalyzing(false)
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
                val pos = withContext(Dispatchers.Main) { svc.jukeboxRawPositionMs() }
                if (pos >= durMs - LOOP_MARGIN_MS) {
                    svc.jukeboxSeekToStart()
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
            while (currentCoroutineContext().isActive && _enabled.value) {
                val eng = engine
                val posMs = if (eng != null) eng.positionMs() else withContext(Dispatchers.Main) { svc.getCurrentPosition() }
                val posFrames = (posMs * rate / 1000).toInt()
                val buffered = svc.jukeboxCapturedFrames().coerceIn(0, total)
                _viz.value = JukeboxViz(
                    buckets = normalizeBuckets(eng?.jumpBuckets(VIZ_BUCKETS, total) ?: previewBuckets),
                    bufferedFraction = buffered.toFloat() / total,
                    playheadFraction = (posFrames.toFloat() / total).coerceIn(0f, 1f),
                    remixing = eng != null
                )
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
    private fun bucketsFromParallels(
        parallels: List<WaveformAnalyzer.Parallel>,
        hopSamples: Int,
        totalFrames: Int
    ): IntArray {
        val out = IntArray(VIZ_BUCKETS)
        val span = maxOf(1, totalFrames)
        for (p in parallels) {
            val i = p.fromFrame * hopSamples
            val b = (i.toLong() * VIZ_BUCKETS / span).toInt().coerceIn(0, VIZ_BUCKETS - 1)
            out[b]++
        }
        return out
    }

    private suspend fun awaitRate(svc: MusicPlaybackService): Int? {
        var waited = 0
        while (currentCoroutineContext().isActive && _enabled.value) {
            val r = svc.jukeboxSampleRate()
            if (r > 0 && svc.jukeboxCapturedFrames() > 0) return r
            delay(500)
            waited++
            if (waited > 40) {
                LokiLogger.i(TAG, "no audio captured (20s) — jukebox off")
                return null
            }
        }
        return null
    }
}
