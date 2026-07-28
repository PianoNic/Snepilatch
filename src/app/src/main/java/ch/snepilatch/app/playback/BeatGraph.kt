package ch.snepilatch.app.playback

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Builds the jump graph over a beat grid: which beat may splice to which. Every rule and constant
 * here exists because its absence was audible — see docs/eternal-infiniPlay.md for the full rationale.
 */
object BeatGraph {

    private const val MAX_BRANCHES = 4
    private const val BEATS_PER_BAR = 4
    private const val MIN_JUMP_BEATS = 4 * BEATS_PER_BAR
    private val CONTEXT_OFFSETS = intArrayOf(-1, 0, 1, 2)
    private val CONTEXT_WEIGHTS = doubleArrayOf(0.5, 1.0, 1.0, 0.75)
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
    private const val INTRO_GUARD_S = 10
    private const val MAX_EDGES_PER_BEATS = 1 / 4.0

    /** Everything the infiniPlay needs from a capture: the grid, the graph, and where it must branch by. */
    class Analysis(
        val jumps: List<InfiniPlayRemixProcessor.Jump>,
        val lastBranchFrame: Int,
        val branchPoints: Int,
        val beats: Int,
        val bpm: Int,
        /** Graph-chosen destination for the forced end-of-song branch. */
        val endJumpFrame: Int = 0
    )

    /** Beat grid + features + graph in one call — the whole analysis side of the infiniPlay. */
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
        val introBeat = grid.beats.indexOfFirst { it >= sampleRate * INTRO_GUARD_S }.coerceAtLeast(0)
        val graph = build(grid.beats, beatFeatures, levels, kickLevels, onsetLevels, joinSpectra, introBeat)
        val lastBranchFrame = grid.beats[graph.lastBranchPoint.coerceIn(0, grid.beats.size - 1)]
        val endJump = grid.beats[graph.endJumpBeat.coerceIn(0, grid.beats.size - 1)]
        return Analysis(
            graph.jumps, lastBranchFrame, graph.jumps.size, grid.beats.size, BeatGrid.bpmOf(grid), endJump
        )
    }

    class Result(
        val jumps: List<InfiniPlayRemixProcessor.Jump>,
        val beatCount: Int,
        val branchingBeats: Int,
        val threshold: Double,
        val lastBranchPoint: Int,
        val endJumpBeat: Int = 0
    )

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

        // Vetoes gate quality; selection only caps the count, globally best first (no count target).
        val kept = candidates.withIndex()
            .flatMap { (i, list) -> list.map { Triple(i, it.first, it.second) } }
            .sortedBy { it.third }
            .take((n * MAX_EDGES_PER_BEATS).toInt().coerceAtLeast(MAX_BRANCHES))
        val threshold = kept.lastOrNull()?.third ?: 0.0
        val neighbours: Array<List<Pair<Int, Double>>> = Array(n) { emptyList<Pair<Int, Double>>() }
        kept.groupBy { it.first }.forEach { (i, edges) ->
            neighbours[i] = edges.map { it.second to it.third }
        }

        // No consecutive beats sharing a jump distance.
        val deSequenced = Array(n) { i ->
            if (i == 0) {
                neighbours[i]
            } else {
                val previousDeltas = neighbours[i - 1].map { (i - 1) - it.first }.toSet()
                neighbours[i].filter { (i - it.first) !in previousDeltas }
            }
        }

        val lastBranchPoint = findLastBranchPoint(deSequenced, n)
        val landingCeiling = lastBranchPoint - LANDING_MARGIN_BEATS
        val pruned = Array(n) { i ->
            if (i < lastBranchPoint) deSequenced[i].filter { it.first < landingCeiling } else deSequenced[i]
        }

        val jumps = ArrayList<InfiniPlayRemixProcessor.Jump>(n)
        for (i in 0 until n) {
            val dsts = pruned[i]
            if (dsts.isEmpty()) continue
            jumps.add(InfiniPlayRemixProcessor.Jump(beats[i], IntArray(dsts.size) { beats[dsts[it].first] }))
        }

        // End fallback: best backward branch near the last branch point, same scoring as any edge.
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

        val tail = Array(beats.size) { WaveformAnalyzer.magnitudeSpectrum(window(beats[it] - len)) }
        val onset = Array(beats.size) { WaveformAnalyzer.magnitudeSpectrum(window(beats[it])) }
        return JoinSpectra(tail, onset)
    }

    /** Join must be nearly as spectrally continuous as the song's own transition at this boundary. */
    private fun spectralJoinOk(spectra: JoinSpectra?, i: Int, j: Int): Boolean {
        if (spectra == null) return true
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

    /** Last beat it is safe to branch from; past it the remix would run into the outro. */
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

    /** Per-beat loudness + 4-slot kick pattern, z-scored and weighted (shape features are level-blind). */
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
