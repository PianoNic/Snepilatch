package ch.snepilatch.app.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

class GainAudioProcessorTest {

    private val stereo16 = AudioProcessor.AudioFormat(44100, 2, C.ENCODING_PCM_16BIT)

    private fun buffer(samples: ShortArray): ByteBuffer =
        ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            samples.forEach { putShort(it) }
            flip()
        }

    private fun shorts(buf: ByteBuffer): ShortArray {
        val out = ShortArray(buf.remaining() / 2)
        buf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(out)
        return out
    }

    /** Push [samples] through a processor already settled at its gain (configure starts at target). */
    private fun process(processor: GainAudioProcessor, samples: ShortArray): ShortArray {
        processor.configure(stereo16)
        processor.flush(AudioProcessor.StreamMetadata.DEFAULT)
        processor.queueInput(buffer(samples))
        return shorts(processor.output)
    }

    @Test
    fun `applies gain to 16 bit samples`() {
        val p = GainAudioProcessor()
        p.setGain(0.5f)
        val out = process(p, shortArrayOf(1000, -1000, 32767, -32768))
        assertTrue(out.contentEquals(shortArrayOf(500, -500, 16383, -16384)))
    }

    @Test
    fun `unity gain is inactive and leaves the buffer untouched`() {
        val p = GainAudioProcessor()
        assertFalse(p.isActive())
        // configure() reports NOT_SET for an inactive processor — the sink then bypasses it entirely.
        assertEquals(AudioProcessor.AudioFormat.NOT_SET, p.configure(stereo16))

        // Even if it is in the chain (gain returned to 1.0 while configured), audio passes unchanged.
        p.setGain(0.5f)
        val input = shortArrayOf(1000, -1000, 500, -500)
        p.setGain(1f)
        assertTrue(process(p, input).contentEquals(input))
    }

    @Test
    fun `unsupported encoding is bypassed via NOT_SET`() {
        val p = GainAudioProcessor()
        p.setGain(0.5f)
        val float32 = AudioProcessor.AudioFormat(44100, 2, C.ENCODING_PCM_FLOAT)
        assertEquals(AudioProcessor.AudioFormat.NOT_SET, p.configure(float32))
    }

    @Test
    fun `gain change ramps without a discontinuity`() {
        val p = GainAudioProcessor()
        p.setGain(1f)
        p.configure(stereo16)
        p.flush(AudioProcessor.StreamMetadata.DEFAULT)
        // Full-scale DC: every output sample is the gain curve itself, so a jump would show up directly.
        val dc = ShortArray(8000) { 20000 }
        p.setGain(0.25f)
        p.queueInput(buffer(dc))
        val out = shorts(p.output)

        var maxStep = 0
        for (i in 2 until out.size) {
            // Compare across a frame (2 channels): within a frame the gain is constant by design.
            maxStep = maxOf(maxStep, abs(out[i] - out[i - 2]))
        }
        // 30 ms ramp at 44.1 kHz => ~0.00076 gain per frame => ~16 counts at 20000 full scale.
        assertTrue("max step was $maxStep", maxStep <= 32)
        assertEquals(5000, out.last().toInt())
    }
}
