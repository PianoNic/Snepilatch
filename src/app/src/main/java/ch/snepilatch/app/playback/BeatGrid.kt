package ch.snepilatch.app.playback

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Finds the beat grid of a track straight from its PCM — the thing Spfy's analysis hands the original
 * Infinite Jukebox for free, and which we have to derive ourselves.
 *
 * It matters because every splice has to land ON a beat: a jump that arrives mid-beat is heard as a
 * stumble no matter how well the two points match spectrally.
 *
 * Spectral flux onset envelope → autocorrelation for the tempo → phase search for the downbeat offset.
 * Constant tempo is assumed, which holds for the produced music this feature is used on.
 */
object BeatGrid {

    /** Beat start positions in sample frames, plus the tempo they came from. */
    class Grid(val beats: IntArray, val bpm: Double)

    private const val FFT = 1024
    private const val HOP = 512 // ~11.6ms at 44.1k: fine enough to place a beat inaudibly close
    private const val MIN_BPM = 70.0
    private const val MAX_BPM = 190.0
    private const val SNAP_HOPS = 3 // snap a beat to the strongest onset within ±3 hops (~35ms)

    private val WINDOW = DoubleArray(FFT) { 0.5 - 0.5 * cos(2.0 * Math.PI * it / (FFT - 1)) }

    fun detect(mono: ShortArray, sampleRate: Int): Grid {
        val flux = onsetEnvelope(mono)
        if (flux.size < 8) return Grid(IntArray(0), 0.0)

        val fps = sampleRate.toDouble() / HOP
        val minLag = (fps * 60.0 / MAX_BPM).toInt().coerceAtLeast(1)
        val maxLag = (fps * 60.0 / MIN_BPM).toInt().coerceAtMost(flux.size / 2)
        if (maxLag <= minLag) return Grid(IntArray(0), 0.0)

        // Tempo: the lag whose autocorrelation is strongest.
        val scores = DoubleArray(maxLag + 1)
        var bestLag = minLag
        var bestScore = -1.0
        for (lag in minLag..maxLag) {
            var acc = 0.0
            var i = lag
            while (i < flux.size) {
                acc += flux[i] * flux[i - lag]
                i++
            }
            scores[lag] = acc / (flux.size - lag)
            if (scores[lag] > bestScore) {
                bestScore = scores[lag]
                bestLag = lag
            }
        }

        // Sub-lag precision via parabolic interpolation of the autocorrelation peak. The lag grid is
        // HOP samples (~11.6ms) coarse — up to half a step of period error, which ACCUMULATES beat by
        // beat: by mid-track the grid can sit hundreds of milliseconds beside the real beats, and
        // every splice there lands audibly off-beat ("rushed"). Fractional period + per-beat rounding
        // keeps the drift under one sample per beat instead.
        val lagFrac = if (bestLag in (minLag + 1) until maxLag) {
            val y1 = scores[bestLag - 1]
            val y2 = scores[bestLag]
            val y3 = scores[bestLag + 1]
            val denom = y1 - 2 * y2 + y3
            if (abs(denom) > 1e-12) (0.5 * (y1 - y3) / denom).coerceIn(-0.5, 0.5) else 0.0
        } else {
            0.0
        }
        val periodSamples = (bestLag + lagFrac) * HOP

        // Phase: the offset where a pulse train at that period collects the most onset energy.
        var bestPhase = 0
        var bestPhaseScore = -1.0
        for (phase in 0 until bestLag) {
            var acc = 0.0
            var i = phase
            while (i < flux.size) {
                acc += flux[i]
                i += bestLag
            }
            if (acc > bestPhaseScore) {
                bestPhaseScore = acc
                bestPhase = phase
            }
        }

        val first = bestPhase * HOP
        val count = ((mono.size - first) / periodSamples).toInt().coerceAtLeast(0)
        // Snap each nominal beat to the strongest onset within ±SNAP_HOPS hops: absorbs what is left
        // of the drift plus the track's own micro-timing, beat by beat, instead of trusting a rigid
        // grid end to end.
        val beats = IntArray(count) {
            val nominal = first + (it * periodSamples).roundToInt()
            val centre = nominal / HOP
            var best = centre
            for (h in (centre - SNAP_HOPS).coerceAtLeast(0)..(centre + SNAP_HOPS).coerceAtMost(flux.size - 1)) {
                if (flux[h] > flux[best.coerceIn(0, flux.size - 1)]) best = h
            }
            (best * HOP).coerceIn(0, mono.size - 1)
        }
        return Grid(beats, 60.0 * sampleRate / periodSamples)
    }

    /** Half-wave-rectified spectral flux: how much the spectrum brightened since the previous frame. */
    private fun onsetEnvelope(mono: ShortArray): DoubleArray {
        if (mono.size < FFT) return DoubleArray(0)
        val frames = 1 + (mono.size - FFT) / HOP
        val out = DoubleArray(frames)
        val re = DoubleArray(FFT)
        val im = DoubleArray(FFT)
        var prev = DoubleArray(FFT / 2)
        for (f in 0 until frames) {
            val off = f * HOP
            for (i in 0 until FFT) {
                re[i] = (mono[off + i] / 32768.0) * WINDOW[i]
                im[i] = 0.0
            }
            fft(re, im)
            val mag = DoubleArray(FFT / 2)
            var flux = 0.0
            for (k in 0 until FFT / 2) {
                mag[k] = sqrt(re[k] * re[k] + im[k] * im[k])
                val d = mag[k] - prev[k]
                if (d > 0) flux += d
            }
            out[f] = flux
            prev = mag
        }
        // Subtract a local mean so steady loud passages don't read as a continuous onset.
        val smoothed = DoubleArray(out.size)
        val w = 20
        for (i in out.indices) {
            var acc = 0.0
            var n = 0
            for (j in (i - w).coerceAtLeast(0)..(i + w).coerceAtMost(out.size - 1)) {
                acc += out[j]
                n++
            }
            smoothed[i] = (out[i] - acc / n).coerceAtLeast(0.0)
        }
        return smoothed
    }

    /** In-place iterative radix-2 FFT. */
    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                var t = re[i]
                re[i] = re[j]
                re[j] = t
                t = im[i]
                im[i] = im[j]
                im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * Math.PI / len
            val wr = cos(ang)
            val wi = kotlin.math.sin(ang)
            var i = 0
            while (i < n) {
                var curR = 1.0
                var curI = 0.0
                for (k in 0 until len / 2) {
                    val uR = re[i + k]
                    val uI = im[i + k]
                    val vR = re[i + k + len / 2] * curR - im[i + k + len / 2] * curI
                    val vI = re[i + k + len / 2] * curI + im[i + k + len / 2] * curR
                    re[i + k] = uR + vR
                    im[i + k] = uI + vI
                    re[i + k + len / 2] = uR - vR
                    im[i + k + len / 2] = uI - vI
                    val nR = curR * wr - curI * wi
                    curI = curR * wi + curI * wr
                    curR = nR
                }
                i += len
            }
            len = len shl 1
        }
    }

    /** Nearest beat to [frame], for snapping an arbitrary position onto the grid. */
    fun snap(grid: Grid, frame: Int): Int {
        if (grid.beats.isEmpty()) return frame
        var lo = 0
        var hi = grid.beats.size - 1
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (grid.beats[mid] < frame) lo = mid + 1 else hi = mid
        }
        val a = grid.beats[lo]
        val b = grid.beats[(lo - 1).coerceAtLeast(0)]
        return if (abs(a - frame) <= abs(b - frame)) a else b
    }

    /** Beats per minute, rounded, for logging. */
    fun bpmOf(grid: Grid): Int = grid.bpm.roundToInt()
}
