package ch.snepilatch.app.playback

import androidx.media3.common.audio.AudioProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Offline listening lab for InfiniPlay — renders a remix from a WAV on the JVM, driving the real
 * [InfiniPlayRemixProcessor] the way ExoPlayer's sink does, and verifies the graph invariants.
 * Skipped unless LAB_IN is set; see tools/infiniplay-lab/README.md for the full workflow.
 *
 *   LAB_IN=track.wav LAB_OUT=remix.wav LAB_SECS=180 LAB_MODE=growth \
 *     ./gradlew :app:testProdDebugUnitTest --tests "ch.snepilatch.app.playback.InfiniPlayLab"
 */
class InfiniPlayLab {

    private class Wav(val pcm: ShortArray, val channels: Int, val sampleRate: Int) {
        val frames get() = pcm.size / channels
    }

    private class Render(val pcm: ShortArray, val seams: List<Triple<Int, Long, Long>>, val overrunFrames: Long)

    @Test
    fun render() {
        val input = System.getenv("LAB_IN")
        assumeTrue("LAB_IN not set — lab run skipped", input != null)
        val w = readWav(File(input!!))
        val outFile = File(System.getenv("LAB_OUT") ?: "$input-remix.wav")
        val secs = (System.getenv("LAB_SECS") ?: "180").toInt()
        val growth = System.getenv("LAB_MODE") == "growth"
        val m = mono(w)
        println("track: ${w.frames / w.sampleRate}s, ${w.channels}ch @ ${w.sampleRate}Hz, mode=${if (growth) "growth" else "full"}")

        val r = if (growth) renderGrowth(w, m, secs) else renderFull(w, m, secs)
        writeWav(outFile, r.pcm, w.channels, w.sampleRate)
        println("rendered ${secs}s -> ${outFile.name}")
        writeSeamsCsv(outFile, r.seams)
        reportJolts(w, m, r)
        assertTrue(
            "played ${r.overrunFrames / w.sampleRate.toDouble()}s past a live snapshot's last branch point",
            r.overrunFrames <= w.sampleRate
        )
    }

    /** One analysis of the complete file, like the device holds once capture finishes. */
    private fun renderFull(w: Wav, m: ShortArray, secs: Int): Render {
        val graph = BeatGraph.analyse(m, w.sampleRate)
        println("graph: ${graph.branchPoints} branch points of ${graph.beats} beats @ ${graph.bpm} BPM")
        assertTrue("no branches found", graph.jumps.isNotEmpty())
        assertGraphInvariants(m, w.sampleRate, graph)
        val proc = startedProcessor(w)
        proc.setSnapshot(snapshotOf(w, w.frames, graph), 0)
        return drive(proc, w, secs, graph.lastBranchFrame, startFrame = 0, onChunk = null)
    }

    /**
     * Replays the device timeline: capture grows at 1x while the remix renders, handoff at the first
     * usable graph, richer snapshots swapped in on InfiniPlayController's schedule.
     */
    private fun renderGrowth(w: Wav, m: ShortArray, secs: Int): Render {
        val rate = w.sampleRate
        var cap = minOf(HANDOFF_S * rate, m.size)
        var res = BeatGraph.analyse(m.copyOf(cap), rate)
        println("preview @${cap / rate}s: ${res.branchPoints} branch points, ${res.bpm} BPM")
        while (cap < m.size && !handoffReady(cap, rate, res.branchPoints)) {
            cap = minOf(cap + PREVIEW_STEP_S * rate, m.size)
            res = BeatGraph.analyse(m.copyOf(cap), rate)
            println("preview @${cap / rate}s: ${res.branchPoints} branch points, ${res.bpm} BPM")
        }
        assertTrue("no branches at handoff", res.jumps.isNotEmpty())
        println("HANDOFF @${cap / rate}s: ${res.branchPoints} branch points, lastBranch=${res.lastBranchFrame / rate}s")

        val proc = startedProcessor(w)
        // The user listens at the capture edge at handoff — past the young graph's last branch point,
        // so the first splice is the forced jump back. Expected; see docs/infiniplay.md.
        proc.setSnapshot(snapshotOf(w, cap, res), cap - 1)
        var lastBranch = res.lastBranchFrame
        var analysed = cap
        return drive(proc, w, secs, lastBranch, startFrame = cap - 1) { written ->
            val capSim = minOf(m.size, cap + written)
            val complete = capSim >= m.size
            if ((complete && analysed < m.size) || capSim - analysed >= GROW_STEP_S * rate) {
                val g = BeatGraph.analyse(m.copyOf(capSim), rate)
                if (g.jumps.isNotEmpty()) {
                    proc.setSnapshot(snapshotOf(w, capSim, g))
                    lastBranch = g.lastBranchFrame
                    val msg = "grow @${written / rate}s out (${capSim / rate}s captured): " +
                        "${g.branchPoints} branch points, ${g.bpm} BPM, lastBranch=${g.lastBranchFrame / rate}s"
                    println(msg)
                }
                analysed = capSim
            }
            lastBranch
        }
    }

    /** Feeds the processor in sink-sized buffers; logs seams and outro overrun against the live snapshot. */
    private fun drive(
        proc: InfiniPlayRemixProcessor,
        w: Wav,
        secs: Int,
        initialLastBranch: Int,
        startFrame: Int,
        onChunk: ((Int) -> Int)?
    ): Render {
        val rate = w.sampleRate
        val outFrames = secs * rate
        val rendered = ShortArray(outFrames * w.channels)
        val seams = ArrayList<Triple<Int, Long, Long>>()
        var lastBranch = initialLastBranch
        var written = 0
        var src = 0
        var lastHead = -1L
        var overrun = 0L
        while (written < outFrames) {
            lastBranch = onChunk?.invoke(written) ?: lastBranch
            val n = minOf(CHUNK, outFrames - written)
            val buf = ByteBuffer.allocate(n * w.channels * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until n * w.channels) buf.putShort(w.pcm.getOrElse(src * w.channels + i) { 0 })
            buf.flip()
            src = (src + n) % maxOf(1, w.frames - CHUNK)
            proc.queueInput(buf)
            val o = proc.output
            val got = o.remaining() / 2
            val tmp = ShortArray(got)
            o.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(tmp)
            System.arraycopy(tmp, 0, rendered, written * w.channels, minOf(got, rendered.size - written * w.channels))
            written += got / w.channels
            val head = proc.playheadMs() * rate / 1000
            val limit = maxOf(lastBranch.toLong(), startFrame.toLong())
            if (head > limit) overrun = maxOf(overrun, head - limit)
            if (lastHead >= 0 && abs(head - lastHead - n) > n) seams.add(Triple(written, lastHead, head))
            lastHead = head
        }
        return Render(rendered, seams, overrun)
    }

    private fun handoffReady(cap: Int, rate: Int, parallels: Int): Boolean =
        parallels >= MIN_PARALLELS || (cap >= FALLBACK_S * rate && parallels > 0)

    /** The guarantees the reference algorithm exists for: on-grid jumps, no consecutive same-distance branches. */
    private fun assertGraphInvariants(m: ShortArray, rate: Int, graph: BeatGraph.Analysis) {
        val grid = BeatGrid.detect(m, rate)
        val beatSet = grid.beats.toHashSet()
        val offGrid = graph.jumps.count { j -> j.src !in beatSet || j.dsts.any { it !in beatSet } }
        assertEquals("branches off the beat grid", 0, offGrid)
        val bySrc = graph.jumps.associateBy { it.src }
        var sequential = 0
        for (idx in 1 until grid.beats.size) {
            val prev = bySrc[grid.beats[idx - 1]]
            val cur = bySrc[grid.beats[idx]]
            if (prev != null && cur != null) {
                val prevDeltas = prev.dsts.map { grid.beats[idx - 1] - it }.toSet()
                if (cur.dsts.any { (grid.beats[idx] - it) in prevDeltas }) sequential++
            }
        }
        assertEquals("consecutive beats sharing a jump distance", 0, sequential)
    }

    private fun startedProcessor(w: Wav): InfiniPlayRemixProcessor {
        val proc = InfiniPlayRemixProcessor()
        proc.engaged = true
        proc.configure(AudioProcessor.AudioFormat(w.sampleRate, w.channels, 2))
        proc.flush(AudioProcessor.StreamMetadata.DEFAULT)
        return proc
    }

    private fun snapshotOf(w: Wav, frames: Int, res: BeatGraph.Analysis) = InfiniPlayRemixProcessor.Snapshot(
        if (w.pcm.size > frames * w.channels) w.pcm.copyOf(frames * w.channels) else w.pcm,
        frames, res.jumps, res.lastBranchFrame, res.endJumpFrame
    )

    private fun writeSeamsCsv(outFile: File, seams: List<Triple<Int, Long, Long>>) {
        val nl = 10.toChar()
        val csv = StringBuilder("out_frame,from_frame,to_frame").append(nl)
        for ((at, from, to) in seams) csv.append(at).append(',').append(from).append(',').append(to).append(nl)
        File(outFile.parentFile ?: File("."), outFile.nameWithoutExtension + "-seams.csv").writeText(csv.toString())
    }

    /** "The band had a stroke" detector: seam energy jolts measured against the track's own distribution. */
    private fun reportJolts(w: Wav, srcMono: ShortArray, r: Render) {
        val rate = w.sampleRate
        val renderedMono = mono(Wav(r.pcm, w.channels, rate))
        val own = ArrayList<Double>()
        var i = rate * JOLT_WIN_MS / 1000
        while (i + rate * JOLT_WIN_MS / 1000 < srcMono.size) {
            own.add(joltAt(srcMono, i, rate))
            i += rate * JOLT_WIN_MS / 1000
        }
        own.sort()
        val p99 = own[(own.size * 99 / 100).coerceAtMost(own.size - 1)]
        var bad = 0
        var worst = 0.0
        for ((pos, _, _) in r.seams) {
            val j = joltAt(renderedMono, pos, rate)
            if (j > worst) worst = j
            if (j > p99) bad++
        }
        val msg = "seams: ${r.seams.size}, jolt above the track's own 99th percentile: $bad " +
            "(worst %.2f vs p99 %.2f)".format(worst, p99)
        println(msg)
    }

    private fun joltAt(m: ShortArray, frame: Int, rate: Int): Double {
        val win = rate * JOLT_WIN_MS / 1000
        if (frame - win < 0 || frame + win >= m.size) return 0.0
        var a = 0.0
        var b = 0.0
        for (i in 0 until win) {
            a += m[frame - win + i].toDouble() * m[frame - win + i]
            b += m[frame + i].toDouble() * m[frame + i]
        }
        val ra = sqrt(a / win)
        val rb = sqrt(b / win)
        return if (ra < 1.0 || rb < 1.0) 0.0 else abs(ln(rb / ra))
    }

    private fun readWav(f: File): Wav {
        val b = f.readBytes()
        require(String(b, 0, 4) == "RIFF" && String(b, 8, 4) == "WAVE") { "not a RIFF/WAVE file" }
        var pos = 12
        var channels = 2
        var rate = 44100
        var dataOff = -1
        var dataLen = 0
        while (pos + 8 <= b.size) {
            val id = String(b, pos, 4)
            val size = ByteBuffer.wrap(b, pos + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            val body = pos + 8
            when (id) {
                "fmt " -> {
                    val bb = ByteBuffer.wrap(b, body, size).order(ByteOrder.LITTLE_ENDIAN)
                    bb.short
                    channels = bb.short.toInt()
                    rate = bb.int
                }
                "data" -> {
                    dataOff = body
                    dataLen = size
                }
            }
            pos = body + size + (size and 1)
        }
        require(dataOff >= 0) { "no data chunk" }
        val shorts = ShortArray(dataLen / 2)
        ByteBuffer.wrap(b, dataOff, dataLen).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
        return Wav(shorts, channels, rate)
    }

    private fun writeWav(f: File, pcm: ShortArray, channels: Int, rate: Int) {
        val dataLen = pcm.size * 2
        val out = ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN)
        out.put("RIFF".toByteArray()).putInt(36 + dataLen).put("WAVE".toByteArray())
        out.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(channels.toShort())
        out.putInt(rate).putInt(rate * channels * 2).putShort((channels * 2).toShort()).putShort(16)
        out.put("data".toByteArray()).putInt(dataLen)
        for (s in pcm) out.putShort(s)
        f.writeBytes(out.array())
    }

    private fun mono(w: Wav): ShortArray {
        if (w.channels == 1) return w.pcm
        val out = ShortArray(w.frames)
        for (i in out.indices) {
            var acc = 0
            for (c in 0 until w.channels) acc += w.pcm[i * w.channels + c]
            out[i] = (acc / w.channels).toShort()
        }
        return out
    }

    private companion object {
        const val CHUNK = 1024 // frames per queueInput, like the sink
        const val JOLT_WIN_MS = 20

        // The device's handoff/growth schedule, in seconds — taken from the controller so it can't drift.
        val HANDOFF_S = (InfiniPlayController.HANDOFF_AFTER_MS / 1000).toInt()
        val MIN_PARALLELS = InfiniPlayController.MIN_PARALLELS_FOR_HANDOFF
        val FALLBACK_S = (InfiniPlayController.FALLBACK_HANDOFF_MS / 1000).toInt()
        val PREVIEW_STEP_S = InfiniPlayController.PREVIEW_EVERY_S
        val GROW_STEP_S = InfiniPlayController.REANALYZE_GROWTH_S
    }
}
