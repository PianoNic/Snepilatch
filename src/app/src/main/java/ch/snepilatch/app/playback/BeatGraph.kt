package ch.snepilatch.app.playback

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Builds the jump graph over a beat grid, following the Infinite Jukebox / Eternal Jukebox model
 * (Paul Lamere; Pithaya's Spicetify rewrite) rather than the naive "every similar frame is a jump".
 *
 * The rules that matter for how it SOUNDS, and which a plain nearest-neighbour search lacks:
 *  - branches are between BEATS, so a splice can never land mid-beat;
 *  - the similarity threshold is dynamic — raised until enough beats branch — instead of a constant
 *    that is either unreachable on one track and far too loose on the next;
 *  - at most [MAX_BRANCHES] nearest destinations per beat;
 *  - consecutive beats may not share a jump distance, which is what makes a remix stutter in place;
 *  - a reachability pass finds the last beat you can still branch from, and branches past it are
 *    dropped so the remix can never strand itself in the outro.
 */
object BeatGraph {

    private const val MAX_BRANCHES = 4

    /**
     * Beats per bar. A branch may only join beats at the SAME position in their bar — beat 3 to beat 3,
     * never beat 3 to beat 1. Two beats can be near-identical in pitch and timbre yet sit at different
     * points of the phrase, and cutting between those drops or inserts beats mid-bar: the listener hears
     * the band stumble. The reference does this with a flat distance penalty on a mismatched bar
     * position; since both beats sit on one grid, requiring (i - j) % BEATS_PER_BAR == 0 is the same
     * constraint and needs no downbeat detection.
     */
    private const val BEATS_PER_BAR = 4

    /**
     * Never branch closer than four bars. A short hop lands inside the phrase that just played, so the
     * listener hears the same material twice in a row — the "it played the similar thing again" trip.
     * (The processor's recent-region memory also discourages this, but the graph shouldn't offer it.)
     */
    private const val MIN_JUMP_BEATS = 4 * BEATS_PER_BAR

    // The join is judged across a window around the cut: lead-in, the cut itself, and — weighted
    // highest — the two beats that follow the landing point, because that's what the listener hears
    // as "does this continue sensibly".
    private val CONTEXT_OFFSETS = intArrayOf(-1, 0, 1, 2)
    private val CONTEXT_WEIGHTS = doubleArrayOf(0.5, 1.0, 1.0, 0.75)

    // Loudness/rhythm columns appended to the shape features (see beatExtras).
    private const val KICK_LP_HZ = 150.0
    private const val KICK_SLOTS = 4
    private const val LEVEL_WEIGHT = 2.0
    private const val KICK_WEIGHT = 0.8
    private const val LEVEL_VETO_DB = 4.0
    private const val KICK_VETO_DB = 6.0
    private const val ONSET_MS = 150
    private const val ONSET_VETO_DB = 4.0
    private const val JOIN_COS_MIN = 0.55
    private const val JOIN_COS_SLACK = 0.10
    private const val LANDING_MARGIN_BEATS = 2 * BEATS_PER_BAR
    private const val INTRO_GUARD_S = 10 // no branch may start or land inside the song's opening
    private const val MAX_EDGES_PER_BEATS = 1 / 4.0 // keep the best edges up to n/4 of the beat count

    /** Everything the jukebox needs from a capture: the grid, the graph, and where it must branch by. */
    class Analysis(
        val jumps: List<JukeboxRemixProcessor.Jump>,
        val lastBranchFrame: Int,
        val branchPoints: Int,
        val beats: Int,
        val bpm: Int,
        /**
         * The graph's chosen fallback for the forced end-of-song branch: the destination of the best
         * long backward branch. Without it the player fell back to a blind cut at track/3, which the
         * seam analysis flagged as one of the worst joins.
         */
        val endJumpFrame: Int = 0
    )

    /** Beat grid + features + graph in one call — the whole analysis side of the jukebox. */
    fun analyse(mono: ShortArray, sampleRate: Int): Analysis {
        val grid = BeatGrid.detect(mono, sampleRate)
        if (grid.beats.size < 8) return Analysis(emptyList(), mono.size, 0, grid.beats.size, 0)
        val frames = WaveformAnalyzer.features(mono, sampleRate)
        val extras = beatExtras(mono, sampleRate, grid.beats)
        val levels = DoubleArray(grid.beats.size) { i ->
            val to = if (i + 1 < grid.beats.size) grid.beats[i + 1] else mono.size
            dbOf(rmsOf(mono, grid.beats[i], to))
        }
        val kickLevels = kickLevels(mono, sampleRate, grid.beats)
        val onsetLevels = DoubleArray(grid.beats.size) { i ->
            dbOf(rmsOf(mono, grid.beats[i], grid.beats[i] + sampleRate * ONSET_MS / 1000))
        }
        val joinSpectra = joinSpectra(mono, grid.beats)
        val beatFeatures = Array(grid.beats.size) { i ->
            val to = if (i + 1 < grid.beats.size) grid.beats[i + 1] else mono.size
            beatFeature(frames.rows, frames.hopSamples, grid.beats[i], to) + extras[i]
        }
        // The intro is out of bounds for BOTH ends of a branch: landing there sounds like a restart,
        // and jumping OUT of its sparse texture into the full arrangement never blends — the ear
        // hears the band appear mid-bar. Let the song establish itself first.
        val introBeat = grid.beats.indexOfFirst { it >= sampleRate * INTRO_GUARD_S }.coerceAtLeast(0)
        val graph = build(grid.beats, beatFeatures, levels, kickLevels, onsetLevels, joinSpectra, introBeat)
        val lastBranchFrame = grid.beats[graph.lastBranchPoint.coerceIn(0, grid.beats.size - 1)]
        val endJump = grid.beats[graph.endJumpBeat.coerceIn(0, grid.beats.size - 1)]
        return Analysis(
            graph.jumps, lastBranchFrame, graph.jumps.size, grid.beats.size, BeatGrid.bpmOf(grid), endJump
        )
    }

    class Result(
        val jumps: List<JukeboxRemixProcessor.Jump>,
        val beatCount: Int,
        val branchingBeats: Int,
        val threshold: Double,
        val lastBranchPoint: Int,
        val endJumpBeat: Int = 0
    )

    /**
     * [beats] are beat start positions in sample frames; [features] is a vector per beat (the
     * analyser's per-frame chroma/timbre averaged across it plus the loudness/kick extras); [levels]
     * is the raw per-beat level in dB, used for the hard dynamics veto.
     */
    fun build(
        beats: IntArray,
        features: Array<DoubleArray>,
        levels: DoubleArray? = null,
        kickLevels: DoubleArray? = null,
        onsetLevels: DoubleArray? = null,
        joinSpectra: JoinSpectra? = null,
        minBeat: Int = 0
    ): Result {
        val n = beats.size
        if (n < 8) return Result(emptyList(), n, 0, 0.0, 0)

        // Every candidate edge, nearest few per beat, before any threshold is applied.
        val candidates = Array(n) { i ->
            val scored = ArrayList<Pair<Int, Double>>(n)
            for (j in 0 until n) {
                val allowed = i != j &&
                    i >= minBeat && j >= minBeat &&
                    (i - j).mod(BEATS_PER_BAR) == 0 &&
                    abs(i - j) >= MIN_JUMP_BEATS &&
                    levelCompatible(levels, i, j, n) &&
                    levelVeto(kickLevels, i, j, n, KICK_VETO_DB) &&
                    onsetVeto(onsetLevels, i, j) &&
                    spectralJoinOk(joinSpectra, i, j)
                if (allowed) scored.add(j to contextDistance(features, i, j, n))
            }
            scored.sortBy { it.second }
            scored.take(MAX_BRANCHES)
        }

        // No count-targeting threshold. The hard vetoes are the quality gate; here we only cap HOW MANY
        // of the passing edges to keep — globally best first. A loop that loosens until it hits a
        // branch-count target undoes the vetoes: every candidate they remove gets replaced by a worse
        // one that was previously over the line.
        val kept = candidates.withIndex()
            .flatMap { (i, list) -> list.map { Triple(i, it.first, it.second) } }
            .sortedBy { it.third }
            .take((n * MAX_EDGES_PER_BEATS).toInt().coerceAtLeast(MAX_BRANCHES))
        val threshold = kept.lastOrNull()?.third ?: 0.0
        val neighbours: Array<List<Pair<Int, Double>>> = Array(n) { emptyList<Pair<Int, Double>>() }
        kept.groupBy { it.first }.forEach { (i, edges) ->
            neighbours[i] = edges.map { it.second to it.third }
        }

        // Consecutive beats sharing a jump distance is what makes a remix stutter in one spot.
        val deSequenced = Array(n) { i ->
            if (i == 0) {
                neighbours[i]
            } else {
                val previousDeltas = neighbours[i - 1].map { (i - 1) - it.first }.toSet()
                neighbours[i].filter { (i - it.first) !in previousDeltas }
            }
        }

        val lastBranchPoint = findLastBranchPoint(deSequenced, n)
        // Landings need runway: a destination just under the last branch point triggers the forced
        // end-branch within a second of arriving, which chains two jumps back to back — heard as a
        // stutter of material that just played. Leave at least two bars before the forced zone.
        val landingCeiling = lastBranchPoint - LANDING_MARGIN_BEATS
        val pruned = Array(n) { i ->
            if (i < lastBranchPoint) deSequenced[i].filter { it.first < landingCeiling } else deSequenced[i]
        }

        val jumps = ArrayList<JukeboxRemixProcessor.Jump>(n)
        for (i in 0 until n) {
            val dsts = pruned[i]
            if (dsts.isEmpty()) continue
            jumps.add(JukeboxRemixProcessor.Jump(beats[i], IntArray(dsts.size) { beats[dsts[it].first] }))
        }

        // The end fallback: the best backward branch near the last branch point, chosen with the same
        // scoring as every other edge — so the forced end-of-song jump is never a blind cut.
        var endJumpBeat = n / 3
        var endBest = Double.MAX_VALUE
        for (i in (lastBranchPoint - BEATS_PER_BAR).coerceAtLeast(0)..lastBranchPoint.coerceAtMost(n - 1)) {
            for ((j, d) in candidates[i]) {
                if (j < landingCeiling && j > n / 8 && d < endBest) {
                    endBest = d
                    endJumpBeat = j
                }
            }
        }
        return Result(jumps, n, pruned.count { it.isNotEmpty() }, threshold, lastBranchPoint, endJumpBeat)
    }

    /**
     * Hard dynamics veto: however well two beats match in shape, if their levels — or the levels of the
     * two beats that follow them — differ by more than [LEVEL_VETO_DB], the join steps audibly in
     * loudness and is not offered at all. The z-scored level feature only discourages this; the seam
     * analysis showed +7..10 dB joins still slipping through, hence the veto.
     */
    private fun levelCompatible(levels: DoubleArray?, i: Int, j: Int, n: Int): Boolean =
        levelVeto(levels, i, j, n, LEVEL_VETO_DB)

    private fun levelVeto(levels: DoubleArray?, i: Int, j: Int, n: Int, limitDb: Double): Boolean {
        if (levels == null) return true
        for (off in 0..2) {
            val a = i + off
            val b = j + off
            if (a >= n || b >= n) break
            if (abs(levels[a] - levels[b]) > limitDb) return false
        }
        return true
    }

    /**
     * The join replaces the OPENING of the source beat with the opening of the destination beat, so
     * the step the listener actually hears is between those two onsets — beat averages can agree while
     * the first 150ms differ by 8 dB, which is exactly the seam the ear rejected. Compare the onsets
     * themselves.
     */
    private fun onsetVeto(onsets: DoubleArray?, i: Int, j: Int): Boolean {
        if (onsets == null) return true
        return abs(onsets[i] - onsets[j]) <= ONSET_VETO_DB
    }

    /** Per-beat spectra either side of each beat boundary, for the join-continuity veto. */
    class JoinSpectra(val tail: Array<DoubleArray>, val onset: Array<DoubleArray>)

    private fun joinSpectra(mono: ShortArray, beats: IntArray): JoinSpectra {
        val len = WaveformAnalyzer.SPECTRUM_SAMPLES
        fun window(from: Int): DoubleArray {
            val out = DoubleArray(len)
            for (k in 0 until len) {
                val idx = from + k
                if (idx in mono.indices) out[k] = mono[idx] / 32768.0
            }
            return out
        }

        // One fine-resolution window either side of the boundary — the same ~186ms the listener
        // judges, at a bin width where individual harmonics resolve and a wrong chord shows.
        val tail = Array(beats.size) { WaveformAnalyzer.magnitudeSpectrum(window(beats[it] - len)) }
        val onset = Array(beats.size) { WaveformAnalyzer.magnitudeSpectrum(window(beats[it])) }
        return JoinSpectra(tail, onset)
    }

    /**
     * The join-continuity veto, the offline seam metric moved into the graph: every seam the ear
     * rejected had a LOW spectral cosine between the audio leading into the cut and the audio landing
     * after it, while level-based vetoes let them through. A branch is only offered when landing on j
     * is spectrally at least almost as continuous as the original continuation onto i was.
     */
    private fun spectralJoinOk(spectra: JoinSpectra?, i: Int, j: Int): Boolean {
        if (spectra == null) return true
        // Relative to the ORIGINAL transition at this boundary: a natural drop steps spectrally too,
        // and an absolute floor would forbid it — while a landing that is much less continuous than
        // what the song itself did there is exactly the join the ear rejects.
        val natural = cosine(spectra.tail[i], spectra.onset[i])
        val joined = cosine(spectra.tail[i], spectra.onset[j])
        return joined >= JOIN_COS_MIN && joined >= natural - JOIN_COS_SLACK
    }

    private fun cosine(a: DoubleArray, b: DoubleArray): Double {
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (k in a.indices) {
            dot += a[k] * b[k]
            na += a[k] * a[k]
            nb += b[k] * b[k]
        }
        val denom = sqrt(na) * sqrt(nb)
        return if (denom > 1e-12) dot / denom else 0.0
    }

    /** Raw per-beat kick-band level in dB — the drums either side of a join must actually match. */
    private fun kickLevels(mono: ShortArray, sampleRate: Int, beats: IntArray): DoubleArray {
        val lp = DoubleArray(mono.size)
        val a = kotlin.math.exp(-2.0 * Math.PI * KICK_LP_HZ / sampleRate)
        var acc = 0.0
        for (i in mono.indices) {
            acc = a * acc + (1 - a) * mono[i]
            lp[i] = acc
        }
        return DoubleArray(beats.size) { i ->
            val to = if (i + 1 < beats.size) beats[i + 1] else mono.size
            dbOf(rmsLp(lp, beats[i], to))
        }
    }

    /**
     * How far the song can still reach from each beat, following branches. The last beat with a good
     * reach is the last point it is safe to branch from — past it the remix would run into the outro
     * with nowhere to go.
     */
    private fun findLastBranchPoint(neighbours: Array<List<Pair<Int, Double>>>, n: Int): Int {
        val reach = IntArray(n) { n - it }
        repeat(REACH_ITERATIONS) {
            var changed = false
            for (i in n - 1 downTo 0) {
                var best = reach[i]
                for ((dst, _) in neighbours[i]) if (reach[dst] > best) best = reach[dst]
                if (i < n - 1 && reach[i + 1] > best) best = reach[i + 1]
                if (best > reach[i]) {
                    reach[i] = best
                    changed = true
                }
            }
            if (!changed) return@repeat
        }
        var longest = 0
        var longestReach = 0
        var i = n - 1
        while (i >= 0 && longestReach < REACH_THRESHOLD_PERCENT) {
            val r = if (neighbours[i].isEmpty()) -1 else (reach[i] - (n - i)) * 100 / n
            if (r > longestReach) {
                longestReach = r
                longest = i
            }
            i--
        }
        return longest
    }

    /**
     * Per-beat loudness and rhythm features the chroma/timbre rows deliberately lack (they are
     * per-frame normalized — shape, not level). Without them a quiet breakdown branches happily into a
     * full-scale chorus: the shapes match while the energy differs by 10 dB, and the seam analysis
     * showed exactly that as the worst joins. Columns, all z-scored then weighted:
     *  - overall beat level in dB, so branches stay within a similar dynamic section;
     *  - a 4-slot kick pattern: low-passed energy per beat quarter, so the drum figure on both sides
     *    of a join actually lines up rather than merely the harmony.
     */
    private fun beatExtras(mono: ShortArray, sampleRate: Int, beats: IntArray): Array<DoubleArray> {
        val n = beats.size
        // One-pole low-pass around the kick band; analysis-grade is all this needs.
        val lp = DoubleArray(mono.size)
        val a = kotlin.math.exp(-2.0 * Math.PI * KICK_LP_HZ / sampleRate)
        var acc = 0.0
        for (i in mono.indices) {
            acc = a * acc + (1 - a) * mono[i]
            lp[i] = acc
        }
        val cols = 1 + KICK_SLOTS
        val rows = Array(n) { DoubleArray(cols) }
        for (i in 0 until n) {
            val from = beats[i]
            val to = if (i + 1 < n) beats[i + 1] else mono.size
            rows[i][0] = dbOf(rmsOf(mono, from, to))
            val slot = ((to - from) / KICK_SLOTS).coerceAtLeast(1)
            for (q in 0 until KICK_SLOTS) {
                rows[i][1 + q] = dbOf(rmsLp(lp, from + q * slot, from + (q + 1) * slot))
            }
        }
        // z-score each column, then weight so level+kick together balance the 20 shape dims.
        for (c in 0 until cols) {
            var mean = 0.0
            for (r in rows) mean += r[c]
            mean /= n
            var v = 0.0
            for (r in rows) {
                val d = r[c] - mean
                v += d * d
            }
            val sd = kotlin.math.sqrt(v / n)
            val w = if (c == 0) LEVEL_WEIGHT else KICK_WEIGHT
            for (r in rows) r[c] = if (sd > 1e-9) w * (r[c] - mean) / sd else 0.0
        }
        return rows
    }

    private fun rmsOf(x: ShortArray, from: Int, to: Int): Double {
        var acc = 0.0
        var count = 0
        var i = from.coerceAtLeast(0)
        while (i < to && i < x.size) {
            acc += x[i].toDouble() * x[i]
            count++
            i += 4
        }
        return kotlin.math.sqrt(acc / count.coerceAtLeast(1))
    }

    private fun rmsLp(x: DoubleArray, from: Int, to: Int): Double {
        var acc = 0.0
        var count = 0
        var i = from.coerceAtLeast(0)
        while (i < to && i < x.size) {
            acc += x[i] * x[i]
            count++
            i += 4
        }
        return kotlin.math.sqrt(acc / count.coerceAtLeast(1))
    }

    private fun dbOf(rms: Double): Double = 20.0 * kotlin.math.log10(rms.coerceAtLeast(1.0))

    /** Mean feature vector across the analyser frames covering [from, to). */
    fun beatFeature(frameFeatures: Array<DoubleArray>, hop: Int, from: Int, to: Int): DoubleArray {
        val dim = if (frameFeatures.isEmpty()) 0 else frameFeatures[0].size
        val out = DoubleArray(dim)
        val first = (from / hop).coerceIn(0, (frameFeatures.size - 1).coerceAtLeast(0))
        val last = (to / hop).coerceIn(first, (frameFeatures.size - 1).coerceAtLeast(0))
        var count = 0
        for (f in first..last) {
            val row = frameFeatures.getOrNull(f)
            if (row != null) {
                for (k in 0 until dim) out[k] += row[k]
                count++
            }
        }
        if (count > 1) for (k in 0 until dim) out[k] = out[k] / count
        return out
    }

    /**
     * Similarity of a branch judged IN CONTEXT, not beat-to-beat. After a splice i -> j the listener
     * hears ...i-1, i, then j+1, j+2... — so the join only makes musical sense when the beats around
     * the cut line up too: the lead-in must match (i-1 vs j-1) and, above all, what FOLLOWS the landing
     * point must sound like the continuation of what was playing (i+1 vs j+1, i+2 vs j+2). Two beats
     * that match in isolation but whose surroundings diverge are exactly the "transition makes no
     * sense" cut; weighting the following beats highest is what rules those out.
     */
    private fun contextDistance(features: Array<DoubleArray>, i: Int, j: Int, n: Int): Double {
        var acc = 0.0
        var weightSum = 0.0
        for (k in CONTEXT_OFFSETS.indices) {
            val off = CONTEXT_OFFSETS[k]
            val a = i + off
            val b = j + off
            if (a !in 0 until n || b !in 0 until n) continue
            acc += CONTEXT_WEIGHTS[k] * distance(features[a], features[b])
            weightSum += CONTEXT_WEIGHTS[k]
        }
        return if (weightSum > 0) acc / weightSum else Double.MAX_VALUE
    }

    private fun distance(a: DoubleArray, b: DoubleArray): Double {
        var s = 0.0
        for (k in a.indices) {
            val d = a[k] - b[k]
            s += d * d
        }
        return sqrt(s)
    }

    /** Beat index nearest to [frame], for starting the remix where the listener already is. */
    fun nearestBeat(beats: IntArray, frame: Int): Int {
        var best = 0
        var bestD = Int.MAX_VALUE
        for (i in beats.indices) {
            val d = abs(beats[i] - frame)
            if (d < bestD) {
                bestD = d
                best = i
            }
        }
        return best
    }

    private const val REACH_ITERATIONS = 200
    private const val REACH_THRESHOLD_PERCENT = 50
}
