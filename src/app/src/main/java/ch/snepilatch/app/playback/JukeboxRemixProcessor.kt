package ch.snepilatch.app.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import ch.snepilatch.app.util.LokiLogger
import java.nio.ByteBuffer
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * The Eternal Jukebox, as a filter in ExoPlayer's own audio chain: while a [Snapshot] is set this
 * replaces the decoded stream with captured audio played back out of order, splicing at matched points
 * with a short PCM crossfade so a jump has no gap.
 *
 * It sits AFTER [JukeboxAudioTap] (which must keep seeing the real stream) and BEFORE
 * [GainAudioProcessor] (so EQ headroom applies to the remix exactly as it does to normal playback).
 *
 * Being a processor rather than a second AudioTrack is the whole point: there is one stream, one
 * volume and one audio session, so the remix cannot double up against the player, drift out of level,
 * or keep playing on its own. Pause, seek and teardown need no handling here — when the sink stops
 * pulling, the remix stops.
 */
class JukeboxRemixProcessor : BaseAudioProcessor() {

    /** A splice candidate: playing through [src] may cut to any of [dsts]. */
    class Jump(val src: Int, val dsts: IntArray)

    /**
     * Captured audio plus the jump points found in it; swapped in as more of the track is analysed.
     * [lastBranchFrame] is the last position it is safe to branch from — the remix must never play
     * past it, or it runs into the outro, finishes, and has to restart. That is the one thing an
     * endless remix may not do.
     */
    class Snapshot(
        val pcm: ShortArray,
        val frames: Int,
        val jumps: List<Jump>,
        val lastBranchFrame: Int = frames,
        /** Graph-chosen destination for the forced end-of-song branch; never a blind cut. */
        val endJumpFrame: Int = frames / 3
    )

    /**
     * Whether the jukebox session is on. The sink only re-reads [isActive] on a pipeline flush, so the
     * processor joins the chain when the session starts (the capture pass seeks, which flushes) and
     * simply passes audio through until a [Snapshot] arrives. Waiting for the snapshot to activate it
     * would need a flush at takeover — and a seek to the position we are already at is a no-op, so
     * there would not be one.
     */
    @Volatile var engaged: Boolean = false

    @Volatile private var snapshot: Snapshot? = null

    @Volatile private var playheadFrame = 0

    private var channels = 2
    private var sampleRate = 44100
    private var xfade = 512
    private var cooldown = 0

    // A crossfade is longer than one sink buffer, so it runs as a small state machine across calls.
    private var fadeRemaining = 0
    private var fadeFrom = 0
    private var fadeTo = 0
    private var fadeGain = 1f
    private var jumpCount = 0
    private val rnd = Random(System.nanoTime())
    private val recent = IntArray(RECENT) { -1 }

    /** How often a splice has landed in each second of the track — the remix-map heat. */
    private val visitsPerSecond = IntArray(MAX_TRACK_S)
    private var recentIdx = 0

    /** Frames of captured audio the remix has available. */
    val bufferedFrames: Int get() = snapshot?.frames ?: 0

    /** Similarity density per time-slice across [totalFrames], weighted by candidates — for the UI. */
    fun jumpBuckets(nBuckets: Int, totalFrames: Int): IntArray? {
        val snap = snapshot ?: return null
        val out = IntArray(nBuckets)
        val span = maxOf(1, totalFrames)
        for (j in snap.jumps) {
            val b = (j.src.toLong() * nBuckets / span).toInt().coerceIn(0, nBuckets - 1)
            out[b] += j.dsts.size
        }
        return out
    }

    /** Splice landings per time-slice across [totalFrames] — how often the remix looped each part. */
    fun visitBuckets(nBuckets: Int, totalFrames: Int): IntArray? {
        if (snapshot == null) return null
        val out = IntArray(nBuckets)
        val totalSecs = maxOf(1, totalFrames / sampleRate)
        for (sec in visitsPerSecond.indices) {
            val hits = visitsPerSecond[sec]
            if (hits == 0) continue
            out[(sec.toLong() * nBuckets / totalSecs).toInt().coerceIn(0, nBuckets - 1)] += hits
        }
        return out
    }

    /** Where the remix is inside the captured audio, for the UI's playhead. */
    fun playheadMs(): Long = 1000L * playheadFrame / sampleRate

    /** Start (or enrich) the remix. Passing null hands the stream straight back to normal playback. */
    fun setSnapshot(snap: Snapshot?, startFrame: Int = -1) {
        if (snap != null && startFrame >= 0) playheadFrame = startFrame.coerceIn(0, maxOf(0, snap.frames - 1))
        snapshot = snap
        if (snap == null) {
            jumpCount = 0
            cooldown = 0
            visitsPerSecond.fill(0)
        }
    }

    // In the chain for the whole jukebox session; normal playback still pays nothing.
    override fun isActive(): Boolean = engaged || snapshot != null

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            LokiLogger.i(TAG, "unsupported encoding ${inputAudioFormat.encoding} — remix bypassed")
            return AudioProcessor.AudioFormat.NOT_SET
        }
        channels = inputAudioFormat.channelCount.coerceAtLeast(1)
        sampleRate = inputAudioFormat.sampleRate
        xfade = (sampleRate * XFADE_MS / 1000).coerceAtLeast(64)
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val snap = snapshot
        val out = replaceOutputBuffer(remaining)

        if (snap == null || snap.frames <= xfade * 2) {
            // Nothing to remix with: pass the live stream through untouched.
            out.put(inputBuffer)
            out.flip()
            return
        }

        val dst = out.asShortBuffer()
        var framesLeft = remaining / 2 / channels
        while (framesLeft > 0) {
            val toEnd = snap.frames - playheadFrame
            val idx = firstSrcAtOrAfter(snap.jumps, playheadFrame)
            val toNext = if (idx < snap.jumps.size) snap.jumps[idx].src - playheadFrame else Int.MAX_VALUE
            // Decide once: spliceAllowed consumes a random draw and pickDst updates the recent-regions
            // memory, so neither may be evaluated twice per iteration.
            // Past the last safe branch point the coin flip no longer applies: we MUST leave now.
            val splice = when {
                fadeRemaining > 0 -> null // one already in flight
                playheadFrame >= snap.lastBranchFrame ->
                    pickDst(snap, lastJumpAtOrBefore(snap, playheadFrame), forced = true) ?: snap.endJumpFrame
                toEnd <= 1 -> pickDst(snap, snap.jumps.lastOrNull(), forced = true) ?: snap.endJumpFrame
                // Standing exactly ON a matched source is the only place a splice may start, because
                // the crossfade's outgoing side has to BE the matched audio. A source with no legal
                // destination is played through rather than forced somewhere bad.
                toNext <= 0 && spliceAllowed() -> pickDst(snap, snap.jumps[idx])
                else -> null
            }
            if (splice != null) startSplice(snap, splice)
            framesLeft -= when {
                // A splice in flight always finishes first. It is longer than one sink buffer, so it
                // continues across calls instead of being limited to what this one happens to hold.
                fadeRemaining > 0 -> writeFade(dst, snap, minOf(framesLeft, fadeRemaining))
                // Play straight, stopping exactly on the next source — or on the last branch point,
                // whichever comes first, so neither is ever overshot.
                else -> {
                    val toLast = snap.lastBranchFrame - playheadFrame
                    val room = minOf(framesLeft, toEnd).coerceAtLeast(1)
                    val nextSrc = if (toNext > 0) toNext else Int.MAX_VALUE
                    val nextLast = if (toLast > 0) toLast else Int.MAX_VALUE
                    val next = minOf(nextSrc, nextLast)
                    val run = if (next in 1..room) next else minOf(room, DECLINE_STEP)
                    writeLinear(dst, snap, run)
                    run
                }
            }
        }

        inputBuffer.position(inputBuffer.limit())
        out.position(remaining)
        out.flip()
    }

    /** Off cooldown and it wins the coin flip — most sources are simply played through. */
    private fun spliceAllowed(): Boolean = cooldown <= 0 && rnd.nextDouble() < JUMP_PROB

    /** First jump whose source is at or after [pos]; [jumps] is sorted by source. */
    private fun firstSrcAtOrAfter(jumps: List<Jump>, pos: Int): Int {
        var lo = 0
        var hi = jumps.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (jumps[mid].src < pos) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /**
     * Destination for [j], or null when it has none outside the intro. Never falls back into the first
     * [INTRO_GUARD_S] seconds: landing there sounds like the song restarting, so a source whose only
     * matches are in the intro is simply played through instead.
     */
    private fun pickDst(snap: Snapshot, j: Jump?, forced: Boolean = false): Int? {
        if (j == null) return null
        val guard = sampleRate * INTRO_GUARD_S
        val bucket = (sampleRate.toLong() * BUCKET_MS / 1000).toInt().coerceAtLeast(1)
        // Runway guard: landing just before the forced-branch zone chains two jumps back to back,
        // which reads as a stutter of material that just played.
        val runway = snap.lastBranchFrame - sampleRate * RUNWAY_S
        val legal = j.dsts.filter { it >= guard && it + xfade < snap.frames && it < runway }
        if (legal.isEmpty()) return null
        // Anti-boredom, two layers. When every legal destination was visited recently, DECLINE and
        // play onward — repeating one of them anyway is how the remix got stuck cycling the same loop.
        // A FORCED jump (end of the song, nowhere to go) relaxes freshness instead: a quality-checked
        // graph edge that repeats still beats the blind end fallback, which bypasses every veto.
        val fresh = legal.filter { !recent.contains(it / bucket) }.ifEmpty { if (forced) legal else emptyList() }
        if (fresh.isEmpty()) return null
        // And among the fresh ones, prefer the least-replayed region of the song (the same counters
        // that drive the remix-map heat), so the walk spreads out instead of wearing a groove.
        val pick = fresh
            .sortedBy { visitsPerSecond.getOrElse(it / sampleRate) { 0 } }
            .take(TOP_PICK)
            .let { it[rnd.nextInt(it.size)] }
        recent[recentIdx] = pick / bucket
        recentIdx = (recentIdx + 1) % RECENT
        return pick
    }

    /**
     * Slide [target] by up to [ALIGN_SEARCH_MS] to wherever its waveform best matches the audio we are
     * leaving. Beat detection is only accurate to a few ms; without this the two sides can join out of
     * phase, which cancels low end and reads as a lurch even though both beats are "correct".
     */
    private fun alignToWaveform(snap: Snapshot, target: Int): Int {
        val search = sampleRate * ALIGN_SEARCH_MS / 1000
        val window = sampleRate * ALIGN_WINDOW_MS / 1000
        if (target < search || target + window + search >= snap.frames) return target
        var bestOffset = 0
        var bestScore = -Double.MAX_VALUE
        var offset = -search
        while (offset <= search) {
            var acc = 0.0
            var i = 0
            while (i < window) {
                val a = snap.pcm.getOrElse((playheadFrame + i) * channels) { 0 }.toDouble()
                val b = snap.pcm.getOrElse((target + offset + i) * channels) { 0 }.toDouble()
                acc += a * b
                i += ALIGN_STRIDE
            }
            if (acc > bestScore) {
                bestScore = acc
                bestOffset = offset
            }
            offset += ALIGN_STRIDE
        }
        return target + bestOffset
    }

    /** Gain that brings the destination's level to the outgoing one, bounded so it stays musical. */
    private fun levelMatch(snap: Snapshot, dstFrame: Int): Float {
        val window = sampleRate * LEVEL_WINDOW_MS / 1000
        val here = rms(snap, playheadFrame, window)
        val there = rms(snap, dstFrame, window)
        if (here <= 1.0 || there <= 1.0) return 1f
        val limit = 10.0.pow(MAX_LEVEL_FIX_DB / 20.0)
        return (here / there).coerceIn(1.0 / limit, limit).toFloat()
    }

    private fun rms(snap: Snapshot, from: Int, frames: Int): Double {
        var acc = 0.0
        var i = 0
        while (i < frames) {
            val v = snap.pcm.getOrElse((from + i) * channels) { 0 }.toDouble()
            acc += v * v
            i += ALIGN_STRIDE
        }
        return sqrt(acc / (frames / ALIGN_STRIDE).coerceAtLeast(1))
    }

    /** The branch point we are standing on or have just passed — the one that must carry us onward. */
    private fun lastJumpAtOrBefore(snap: Snapshot, frame: Int): Jump? {
        val idx = firstSrcAtOrAfter(snap.jumps, frame)
        return snap.jumps.getOrNull(idx) ?: snap.jumps.lastOrNull()
    }

    private fun writeLinear(dst: java.nio.ShortBuffer, snap: Snapshot, frames: Int) {
        var f = 0
        while (f < frames) {
            val src = (playheadFrame + f).coerceIn(0, snap.frames - 1) * channels
            for (c in 0 until channels) dst.put(snap.pcm.getOrElse(src + c) { 0 })
            f++
        }
        playheadFrame = (playheadFrame + frames).coerceAtMost(snap.frames)
        cooldown = (cooldown - frames).coerceAtLeast(0)
    }

    /**
     * The seam. Three things happen here that a plain cut-on-the-beat does not do, and together they
     * are what makes a jump stop sounding like the band stumbled:
     *
     *  1. **Waveform alignment.** The beat grid is only accurate to a few milliseconds, and at that
     *     scale two matching beats can still be out of phase — joining them cancels bass and smears
     *     the attack. So we slide the destination within [ALIGN_SEARCH_MS] and keep the offset whose
     *     waveform correlates best with what is already playing.
     *  2. **Equal-power crossfade.** Linear fading dips ~3dB in the middle when both sides are
     *     uncorrelated; sqrt weights hold the energy flat across the join.
     *  3. **Level match.** If the destination sits at a different dynamic point of the song, its gain
     *     is nudged (bounded by [MAX_LEVEL_FIX_DB]) so the seam doesn't step in loudness.
     */
    private fun startSplice(snap: Snapshot, requestedDst: Int) {
        val dstFrame = alignToWaveform(snap, requestedDst)
        fadeGain = levelMatch(snap, dstFrame)
        fadeFrom = playheadFrame
        fadeTo = dstFrame
        fadeRemaining = xfade
        val sec = dstFrame / sampleRate
        if (sec in visitsPerSecond.indices) visitsPerSecond[sec]++
        cooldown = sampleRate * COOLDOWN_MS / 1000
        jumpCount++
        if (jumpCount <= 3 || jumpCount % 20 == 0) {
            LokiLogger.i(TAG, "splice #$jumpCount -> ${sec}s (slid ${dstFrame - requestedDst} frames)")
        }
    }

    /** Write [n] frames of the splice in flight; equal-power so the energy stays flat across the join. */
    private fun writeFade(dst: java.nio.ShortBuffer, snap: Snapshot, n: Int): Int {
        val done = xfade - fadeRemaining
        for (f in 0 until n) {
            val w = (done + f).toFloat() / xfade
            val fadeOut = sqrt(1.0 - w).toFloat()
            val fadeIn = sqrt(w.toDouble()).toFloat()
            val a = (fadeFrom + done + f).coerceIn(0, snap.frames - 1) * channels
            val b = (fadeTo + done + f).coerceIn(0, snap.frames - 1) * channels
            for (c in 0 until channels) {
                val av = snap.pcm.getOrElse(a + c) { 0 }
                val bv = snap.pcm.getOrElse(b + c) { 0 } * fadeGain
                dst.put((av * fadeOut + bv * fadeIn).toInt().coerceIn(-32768, 32767).toShort())
            }
        }
        fadeRemaining -= n
        playheadFrame = if (fadeRemaining <= 0) fadeTo + xfade else fadeFrom + done + n
        return n
    }

    companion object {
        /** Group analyser pairs into per-source jump tables, nearest destinations first. */
        fun buildJumps(parallels: List<WaveformAnalyzer.Parallel>, hopSamples: Int): List<Jump> {
            val map = HashMap<Int, ArrayList<Pair<Int, Double>>>()
            fun add(a: Int, b: Int, d: Double) { map.getOrPut(a) { ArrayList() }.add(b to d) }
            for (p in parallels) {
                val i = p.fromFrame * hopSamples
                val j = p.toFrame * hopSamples
                add(i, j, p.distance)
                add(j, i, p.distance)
            }
            return map.entries
                .map { (src, list) ->
                    list.sortBy { it.second }
                    Jump(src, IntArray(minOf(MAX_DST_PER_SRC, list.size)) { list[it].first })
                }
                .sortedBy { it.src }
        }

        const val MAX_DST_PER_SRC = 6
        private const val TAG = "JukeboxRemix"
        private const val XFADE_MS = 40 // long enough to blend, short enough not to smear the attack
        private const val ALIGN_SEARCH_MS = 12 // how far the splice may slide to find phase agreement
        private const val ALIGN_WINDOW_MS = 25 // correlation window used to judge that agreement
        private const val ALIGN_STRIDE = 4 // subsample the correlation; this runs on the audio thread
        private const val LEVEL_WINDOW_MS = 120
        private const val MAX_LEVEL_FIX_DB = 3.0
        private const val COOLDOWN_MS = 2500
        private const val JUMP_PROB = 0.35
        private const val DECLINE_STEP = 512 // frames to advance past a source we chose not to take
        private const val RECENT = 16
        private const val BUCKET_MS = 3000
        private const val TOP_PICK = 3
        private const val INTRO_GUARD_S = 10
        private const val RUNWAY_S = 4 // a landing must leave this much room before the forced-branch zone
        private const val MAX_TRACK_S = 900 // heat is per second; 15 min covers anything we play
    }
}
