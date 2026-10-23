package ch.snepilatch.app.logic.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The capture is written by position, so a seek re-anchors it instead of spoiling it. */
class InfiniPlayAudioTapTest {

    private val tap = InfiniPlayAudioTap()

    @Before fun setUp() {
        tap.recording = true
        tap.configure(AudioProcessor.AudioFormat(RATE, 1, C.ENCODING_PCM_16BIT))
        tap.flush()
        tap.resetCapture()
    }

    /** Feeds [ms] of mono audio whose samples carry their own frame number, so placement is checkable. */
    private fun play(fromMs: Int, ms: Int) {
        val first = fromMs * RATE / 1000
        val frames = ms * RATE / 1000
        val buf = ByteBuffer.allocateDirect(frames * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (f in first until first + frames) buf.putShort((f % Short.MAX_VALUE).toShort())
        buf.flip()
        tap.queueInput(buf)
        tap.getOutput()
    }

    private fun seek(toMs: Int) {
        tap.markPosition(toMs.toLong())
        tap.flush()
    }

    private val tenSeconds = 10 * RATE

    @Test fun aTrackPlayedStraightThroughIsCovered() {
        play(0, 10_000)
        assertTrue(tap.isCovered(tenSeconds))
    }

    @Test fun jumpingBackRecordsOverItselfAndStillCovers() {
        play(0, 6_000)
        seek(2_000)
        play(2_000, 8_000)
        assertTrue(tap.isCovered(tenSeconds))
        assertEquals(tenSeconds, tap.capturedFrames())
        val pcm = tap.snapshotInterleaved()
        assertEquals((7 * RATE % Short.MAX_VALUE).toShort(), pcm[7 * RATE])
    }

    @Test fun jumpingForwardLeavesAHole() {
        play(0, 3_000)
        seek(6_000)
        play(6_000, 4_000)
        assertFalse(tap.isCovered(tenSeconds))
    }

    @Test fun aTrackResumedPartWayInIsMissingItsStart() {
        tap.markPosition(1_000)
        tap.flush()
        play(1_000, 9_000)
        assertFalse(tap.isCovered(tenSeconds))
        assertEquals((5 * RATE % Short.MAX_VALUE).toShort(), tap.snapshotInterleaved()[5 * RATE])
    }

    @Test fun buffersDecodedBeforeTheSeekStayWhereTheyBelong() {
        play(0, 4_000)
        tap.markPosition(1_000)
        play(4_000, 1_000) // still in flight when the seek was asked for
        tap.flush()
        play(1_000, 9_000)
        assertTrue(tap.isCovered(tenSeconds))
    }

    private companion object {
        const val RATE = 8_000
    }
}
