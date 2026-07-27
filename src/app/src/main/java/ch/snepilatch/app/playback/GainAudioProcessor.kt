package ch.snepilatch.app.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import ch.snepilatch.app.util.LokiLogger
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Attenuates the decoded PCM by a settable linear gain — the headroom that lets an EQ (Wavelet, or our
 * own) boost without clipping. Sits AFTER [JukeboxAudioTap] in the sink's processor chain so the tap
 * still sees the unmodified signal its beat matching was tuned on.
 *
 * 16-bit PCM only; anything else returns [AudioProcessor.AudioFormat.NOT_SET] from [onConfigure] so the
 * sink bypasses us instead of us mangling the buffer. Gain changes ramp over [RAMP_MS] to avoid a click.
 */
class GainAudioProcessor : BaseAudioProcessor() {

    @Volatile private var targetGain = 1f
    private var currentGain = 1f
    private var channels = 1
    private var rampPerFrame = 1f

    /** Set the linear gain (attenuation only — values above 1 would re-introduce the clipping). */
    fun setGain(gain: Float) {
        targetGain = gain.coerceIn(0f, 1f)
    }

    fun gain(): Float = targetGain

    // Zero cost when there is nothing to apply. Re-read on a pipeline flush, so a gain set mid-track
    // takes effect on the next seek / track change — which is when normalization is applied anyway.
    override fun isActive(): Boolean = targetGain != 1f || currentGain != 1f

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            LokiLogger.i(TAG, "unsupported encoding ${inputAudioFormat.encoding} — bypassing gain")
            return AudioProcessor.AudioFormat.NOT_SET
        }
        channels = inputAudioFormat.channelCount
        rampPerFrame = 1f / (inputAudioFormat.sampleRate * RAMP_MS / 1000f)
        // A fresh configure is a new track: start at the target so there is no audible level jump.
        currentGain = targetGain
        LokiLogger.i(TAG, "gain configured: ${inputAudioFormat.sampleRate}Hz ch=$channels gain=$targetGain")
        return inputAudioFormat
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        currentGain = targetGain
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        val src = inputBuffer.duplicate().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val out = replaceOutputBuffer(remaining)
        val dst = out.asShortBuffer()

        val target = targetGain
        var g = currentGain
        var i = 0
        val total = remaining / 2
        while (i < total) {
            if (g != target) {
                g = if (target > g) minOf(target, g + rampPerFrame) else maxOf(target, g - rampPerFrame)
            }
            // One gain step per frame, so both channels of a frame are scaled identically.
            var c = 0
            while (c < channels && i < total) {
                dst.put((src.get() * g).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
                c++
                i++
            }
        }
        currentGain = g

        inputBuffer.position(inputBuffer.limit())
        out.position(total * 2)
        out.flip()
    }

    private companion object {
        const val TAG = "GainProcessor"
        const val RAMP_MS = 30f
    }
}
