package ch.snepilatch.app.playback

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import ch.snepilatch.app.util.LokiLogger
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * The seamless Eternal Jukebox playback engine. Plays a track's already-decoded interleaved PCM
 * (captured live from the audio pipeline — no separate download) through our own [AudioTrack], and at
 * waveform-matched points jumps to a similar section with an equal-power **crossfade**. Because we own
 * every sample, the splice has no decoder flush and no click — genuinely seamless, unlike an ExoPlayer
 * seek. The match points come from [WaveformAnalyzer]; no Spotify beats involved.
 *
 * The captured audio + its jump candidates live in a swappable [Snapshot]: playback starts as soon as
 * the opening is captured, then [update] swaps in bigger snapshots as the rest of the track loads, so
 * more of the song becomes reachable over time. Each jump source keeps several candidate destinations
 * and a short memory of recently-visited regions steers away from them, so it keeps finding variation
 * instead of ping-ponging between the same two spots.
 *
 * Runs its own writer thread; call [start] once and [stop] to end.
 */
class PcmJukeboxEngine(
    initial: Snapshot,
    private val channels: Int,
    private val sampleRate: Int,
    startFrame: Int = 0
) {
    /** A swappable view of the captured audio + its jump candidates; grows as more of the track loads. */
    class Snapshot(
        val pcm: ShortArray,
        val frames: Int,
        val jumps: List<Jump>
    )

    /** One jump source (in sample frames) and its candidate destinations, nearest match first. */
    class Jump(val src: Int, val dsts: IntArray)

    companion object {
        private const val TAG = "JukeboxPCM"
        private const val CHUNK_FRAMES = 2048
        private const val XFADE_MS = 12
        private const val COOLDOWN_MS = 2500
        private const val JUMP_PROB = 0.35
        private const val RECENT = 10 // avoid re-landing in a region visited in the last N jumps
        private const val BUCKET_MS = 3000 // region granularity for the anti-repeat memory
        private const val TOP_PICK = 3 // randomise among the N closest non-recent candidates
        private const val MAX_DST_PER_SRC = 6
        private const val PAUSE_POLL_MS = 30L

        /**
         * Build the jump table from [WaveformAnalyzer] parallels: group candidate destinations per
         * source frame (both directions), nearest match first, capped per source, sorted by source.
         */
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
    }

    @Volatile private var snap: Snapshot = initial

    @Volatile private var running = false

    @Volatile private var paused = false

    @Volatile private var playhead = 0 // current read position in frames — jumps when we splice

    private val xfade = (sampleRate * XFADE_MS / 1000).coerceAtLeast(64)
    private val cooldownFrames = sampleRate * COOLDOWN_MS / 1000
    private val bucketFrames = (sampleRate.toLong() * BUCKET_MS / 1000).toInt().coerceAtLeast(1)
    private val startAt = startFrame
    private var thread: Thread? = null
    private var track: AudioTrack? = null

    /** Where the engine is actually reading from, in ms — jumps around as it splices. */
    fun positionMs(): Long = playhead.toLong() * 1000L / sampleRate

    fun hasJumps(): Boolean = snap.jumps.isNotEmpty()

    /** Frames currently loaded into the engine (grows as more of the track is captured). */
    fun bufferedFrames(): Int = snap.frames

    /** Similarity density per time-slice across [totalFrames], weighted by candidate matches — for the UI. */
    fun jumpBuckets(nBuckets: Int, totalFrames: Int): IntArray {
        val out = IntArray(nBuckets)
        val span = maxOf(1, totalFrames)
        for (j in snap.jumps) {
            val b = (j.src.toLong() * nBuckets / span).toInt().coerceIn(0, nBuckets - 1)
            out[b] += j.dsts.size
        }
        return out
    }

    /** Swap in a bigger capture + richer jump set as more of the track loads (a superset of the old). */
    fun update(next: Snapshot) { snap = next }

    /** Pause/resume audio output without tearing the engine down, so playback continues where it left off. */
    fun setPaused(p: Boolean) {
        if (paused == p) return
        paused = p
        track?.let { runCatching { if (p) it.pause() else it.play() } }
    }

    fun start() {
        if (running || channels <= 0) return
        running = true
        thread = Thread({ run() }, "PcmJukebox").apply { start() }
        LokiLogger.i(TAG, "engine start: ${snap.frames / sampleRate}s buffer, ${snap.jumps.size} jump srcs, xfade=${xfade}f")
    }

    fun stop() {
        running = false
        thread?.join(500)
        thread = null
        track?.let {
            runCatching {
                it.stop()
                it.release()
            }
        }
        track = null
    }

    private fun run() {
        val at = buildTrack()
        track = at
        at.play()

        val rnd = Random(System.nanoTime())
        val chunk = ShortArray(CHUNK_FRAMES * channels)
        val recent = IntArray(RECENT) { -1 }
        var recentIdx = 0
        playhead = startAt.coerceIn(0, maxOf(0, snap.frames - 1))
        var cooldown = 0
        var jumpCount = 0

        while (running) {
            if (paused) {
                Thread.sleep(PAUSE_POLL_MS)
                continue
            }
            val s = snap
            val frames = s.frames
            val jumps = s.jumps
            val srcIdx = firstSrcAtOrAfter(jumps, playhead)
            val toNext = (if (srcIdx < jumps.size) jumps[srcIdx].src else Int.MAX_VALUE) - playhead
            val n = minOf(CHUNK_FRAMES, frames - playhead)
            val doJump = cooldown <= 0 && toNext in 0..CHUNK_FRAMES && rnd.nextDouble() < JUMP_PROB

            if (doJump) {
                if (toNext > 0) {
                    writeLinear(at, chunk, s, playhead, toNext)
                    playhead += toNext
                }
                val dst = pickDst(jumps[srcIdx], recent, frames, rnd)
                crossfade(at, chunk, s, playhead, dst)
                playhead = dst + xfade
                recent[recentIdx] = dst / bucketFrames
                recentIdx = (recentIdx + 1) % RECENT
                cooldown = cooldownFrames
                jumpCount++
                if (jumpCount <= 3 || jumpCount % 20 == 0) {
                    LokiLogger.i(TAG, "jump #$jumpCount -> ${dst / sampleRate}s (${jumps.size} srcs, ${frames / sampleRate}s buf)")
                }
            } else if (n <= 0) {
                // Reached the end without taking a jump: begin the track anew from the top (a plain
                // restart, not a similarity match), crossfaded so there's no gap.
                crossfade(at, chunk, s, (frames - xfade - 1).coerceAtLeast(0), 0)
                playhead = xfade
                cooldown = cooldownFrames
                LokiLogger.i(TAG, "reached end — restarting from the top")
            } else {
                writeLinear(at, chunk, s, playhead, n)
                playhead += n
                cooldown -= n
            }
        }
        LokiLogger.i(TAG, "engine stopped after $jumpCount jumps")
    }

    private fun buildTrack(): AudioTrack {
        val channelMask = if (channels >= 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        val bufBytes = maxOf(minBuf, CHUNK_FRAMES * channels * 2 * 4)
        val builder = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(channelMask)
                    .build()
            )
            .setBufferSizeInBytes(bufBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
        return builder.build()
    }

    /** Pick a destination for [j]: nearest matches, biased away from recently-visited regions. */
    private fun pickDst(j: Jump, recent: IntArray, frames: Int, rnd: Random): Int {
        val inBounds = j.dsts.filter { it + xfade < frames }
        if (inBounds.isEmpty()) return (frames - xfade - 1).coerceAtLeast(0)
        val fresh = inBounds.filter { !recent.contains(it / bucketFrames) }
        val pool = if (fresh.isNotEmpty()) fresh else inBounds
        val top = pool.take(TOP_PICK)
        return top[rnd.nextInt(top.size)]
    }

    /** First index whose source is at or after [pos] ([jumps] is sorted by source). */
    private fun firstSrcAtOrAfter(jumps: List<Jump>, pos: Int): Int {
        var lo = 0
        var hi = jumps.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (jumps[mid].src < pos) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** Write [count] frames straight from [from]. */
    private fun writeLinear(at: AudioTrack, chunk: ShortArray, s: Snapshot, from: Int, count: Int) {
        var written = 0
        while (written < count && running) {
            val n = minOf(CHUNK_FRAMES, count - written)
            System.arraycopy(s.pcm, (from + written) * channels, chunk, 0, n * channels)
            at.write(chunk, 0, n * channels, AudioTrack.WRITE_BLOCKING)
            written += n
        }
    }

    /** Equal-power crossfade [xfade] frames from [from] into [dst], writing the blended region. */
    private fun crossfade(at: AudioTrack, chunk: ShortArray, s: Snapshot, from: Int, dst: Int) {
        val pcm = s.pcm
        for (k in 0 until xfade) {
            val t = k.toDouble() / xfade
            val gOut = cos(t * Math.PI / 2)
            val gIn = sin(t * Math.PI / 2)
            for (c in 0 until channels) {
                val o = pcm[(from + k) * channels + c].toInt()
                val i = pcm[(dst + k) * channels + c].toInt()
                chunk[k * channels + c] = (o * gOut + i * gIn).toInt().coerceIn(-32768, 32767).toShort()
            }
        }
        at.write(chunk, 0, xfade * channels, AudioTrack.WRITE_BLOCKING)
    }
}
