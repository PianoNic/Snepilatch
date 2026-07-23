package ch.snepilatch.app.playback

import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Waveform-native self-similarity analysis — the replacement for Spotify's beat/segment analysis.
 * Given the decoded mono PCM of a track (captured live from the audio pipeline, no separate download),
 * it splits the audio into short frames, computes a chroma (12 pitch-class) + coarse timbre feature per
 * frame via FFT, and finds pairs of frames that sound alike ("parallels") — the points the jukebox can
 * jump between. Onset-based beat snapping and sample-accurate splicing come next; this stage proves the
 * analysis finds musical parallels straight from the waveform.
 *
 * Pure/deterministic (no Android, no IO) so it can be unit-tested and reasoned about.
 */
object WaveformAnalyzer {

    /** A candidate jump: play head at [fromFrame] can cut to [toFrame] because they sound alike. */
    data class Parallel(val fromFrame: Int, val toFrame: Int, val distance: Double)

    data class Result(
        val frameCount: Int,
        val frameHopSamples: Int,
        val sampleRate: Int,
        val parallels: List<Parallel>
    ) {
        fun frameToMs(frame: Int): Long = 1000L * frame.toLong() * frameHopSamples / sampleRate
    }

    private const val FFT_SIZE = 2048 // ~46ms at 44.1k
    private const val HOP = 2048 // no overlap → fewer frames so full-track self-similarity stays fast
    private const val CHROMA_BINS = 12
    private const val TIMBRE_BANDS = 8
    private const val SILENCE_GATE = 0.05 // frames below 5% of peak energy can't be jump points

    // The Hann window is immutable and FFT_SIZE-sized; compute it once instead of rebuilding it on
    // every analyze() call (which runs repeatedly over the capture half during a jukebox session).
    private val WINDOW = DoubleArray(FFT_SIZE) { 0.5 - 0.5 * cos(2.0 * Math.PI * it / (FFT_SIZE - 1)) }

    /**
     * Analyse [mono] PCM at [sampleRate]. [minGapMs] keeps jumps from being trivially close; [maxDistance]
     * is the similarity cutoff (z-scored feature distance); [maxParallels] caps the returned list.
     */
    fun analyze(
        mono: ShortArray,
        sampleRate: Int,
        minGapMs: Int = 2000,
        maxDistance: Double = 0.85, // stricter: only genuinely similar frames match (was 1.1)
        maxParallels: Int = 4000
    ): Result {
        val nFrames = if (mono.size < FFT_SIZE) 0 else 1 + (mono.size - FFT_SIZE) / HOP
        if (nFrames < 4) return Result(nFrames, HOP, sampleRate, emptyList())

        val dim = CHROMA_BINS + TIMBRE_BANDS
        val feats = Array(nFrames) { DoubleArray(dim) }
        val energy = DoubleArray(nFrames)
        val window = WINDOW
        val re = DoubleArray(FFT_SIZE)
        val im = DoubleArray(FFT_SIZE)
        val mag = DoubleArray(FFT_SIZE / 2)

        for (f in 0 until nFrames) {
            val off = f * HOP
            for (i in 0 until FFT_SIZE) {
                re[i] = (mono[off + i] / 32768.0) * window[i]
                im[i] = 0.0
            }
            fft(re, im)
            var e = 0.0
            for (k in 0 until FFT_SIZE / 2) {
                mag[k] = sqrt(re[k] * re[k] + im[k] * im[k])
                e += mag[k]
            }
            energy[f] = e
            chromaAndTimbre(mag, sampleRate, feats[f]) // per-frame normalized (shape, not loudness)
        }

        zScore(feats)

        // Gate out silence / very-quiet frames: they otherwise all look identical (d≈0) and pollute the
        // matches. Only frames above a fraction of the track's peak energy can be jump points.
        var peakE = 0.0
        for (e in energy) if (e > peakE) peakE = e
        val gate = peakE * SILENCE_GATE
        val loud = BooleanArray(nFrames) { energy[it] >= gate }

        // Find similar frame pairs (parallels), far enough apart to be a real jump.
        val minGapFrames = (minGapMs.toLong() * sampleRate / 1000 / HOP).toInt().coerceAtLeast(1)
        val out = ArrayList<Parallel>()
        var i = 0
        while (i < nFrames && out.size < maxParallels) {
            if (loud[i]) {
                var j = i + minGapFrames
                while (j < nFrames) {
                    if (loud[j]) {
                        val d = dist(feats[i], feats[j])
                        if (d <= maxDistance) out.add(Parallel(i, j, d))
                    }
                    j++
                }
            }
            i++
        }
        out.sortBy { it.distance }
        return Result(nFrames, HOP, sampleRate, if (out.size > maxParallels) out.subList(0, maxParallels) else out)
    }

    // --- features -----------------------------------------------------------------------------

    private fun chromaAndTimbre(mag: DoubleArray, sampleRate: Int, out: DoubleArray) {
        // Chroma: fold FFT bins onto 12 pitch classes (A=440 reference).
        val binHz = sampleRate.toDouble() / FFT_SIZE
        for (k in 1 until mag.size) {
            val freq = k * binHz
            if (freq < 55.0 || freq > 5000.0) continue
            val midi = 69.0 + 12.0 * (ln(freq / 440.0) / ln(2.0))
            val pc = ((midi.toInt() % 12) + 12) % 12
            out[pc] += mag[k]
        }
        // L2-normalize chroma → pitch-class SHAPE, independent of loudness.
        var cn = 0.0
        for (p in 0 until CHROMA_BINS) cn += out[p] * out[p]
        cn = sqrt(cn)
        if (cn > 1e-9) for (p in 0 until CHROMA_BINS) out[p] /= cn

        // Timbre: log-energy in coarse bands, then mean-centre → spectral SHAPE, loudness-independent.
        val bandStart = CHROMA_BINS
        val perBand = (mag.size - 1) / TIMBRE_BANDS
        var tMean = 0.0
        for (b in 0 until TIMBRE_BANDS) {
            var e = 0.0
            val s = 1 + b * perBand
            val en = if (b == TIMBRE_BANDS - 1) mag.size else s + perBand
            for (k in s until en) e += mag[k] * mag[k]
            val v = ln(1.0 + e)
            out[bandStart + b] = v
            tMean += v
        }
        tMean /= TIMBRE_BANDS
        for (b in 0 until TIMBRE_BANDS) out[bandStart + b] -= tMean
    }

    private fun zScore(feats: Array<DoubleArray>) {
        val n = feats.size
        if (n == 0) return
        val dim = feats[0].size
        for (c in 0 until dim) {
            var mean = 0.0
            for (row in feats) mean += row[c]
            mean /= n
            var v = 0.0
            for (row in feats) {
                val d = row[c] - mean
                v += d * d
            }
            val sd = sqrt(v / n)
            if (sd > 1e-9) {
                for (row in feats) row[c] = (row[c] - mean) / sd
            } else {
                for (row in feats) row[c] = 0.0
            }
        }
    }

    private fun dist(a: DoubleArray, b: DoubleArray): Double {
        var s = 0.0
        for (k in a.indices) {
            val d = a[k] - b[k]
            s += d * d
        }
        return sqrt(s)
    }

    /** In-place iterative radix-2 FFT ([re]/[im] length must be a power of two). */
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
                val tr = re[i]
                re[i] = re[j]
                re[j] = tr
                val ti = im[i]
                im[i] = im[j]
                im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * Math.PI / len
            val wRe = cos(ang)
            val wIm = sin(ang)
            var i = 0
            while (i < n) {
                var curRe = 1.0
                var curIm = 0.0
                for (k in 0 until len / 2) {
                    val aRe = re[i + k]
                    val aIm = im[i + k]
                    val bRe = re[i + k + len / 2] * curRe - im[i + k + len / 2] * curIm
                    val bIm = re[i + k + len / 2] * curIm + im[i + k + len / 2] * curRe
                    re[i + k] = aRe + bRe
                    im[i + k] = aIm + bIm
                    re[i + k + len / 2] = aRe - bRe
                    im[i + k + len / 2] = aIm - bIm
                    val nRe = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe
                    curRe = nRe
                }
                i += len
            }
            len = len shl 1
        }
    }
}
