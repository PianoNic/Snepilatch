package ch.snepilatch.app.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import ch.snepilatch.app.util.LokiLogger
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Taps the DECODED PCM in ExoPlayer's audio pipeline (proven readable despite Widevine DRM) and, while
 * [analyzing] is on, accumulates the track as interleaved stereo samples — the raw material for both
 * [WaveformAnalyzer] (mono downmix) and the seamless [PcmJukeboxEngine] (stereo playback). No separate
 * download: this is the same audio being decoded for playback. Pure pass-through; playback is untouched.
 */
class JukeboxAudioTap : BaseAudioProcessor() {

    @Volatile var analyzing: Boolean = false

    private var sampleRate = 0
    private var channels = 0
    private var pcm16 = false

    // Rolling interleaved capture of the current track (cap ~5.5 min so memory is bounded).
    private var pcm = ShortArray(0)
    private var len = 0 // number of shorts written (interleaved)
    private var capturedRate = 0
    private var capturedChannels = 0

    fun sampleRate(): Int = capturedRate
    fun channelCount(): Int = capturedChannels

    /** Frames captured so far (a frame = one sample per channel). */
    fun capturedFrames(): Int = if (capturedChannels > 0) len / capturedChannels else 0

    /** Interleaved stereo (or mono) PCM captured so far — for the playback engine. */
    @Synchronized
    fun snapshotInterleaved(): ShortArray = pcm.copyOf(len)

    /** Mono downmix of the capture — for the analyzer. */
    @Synchronized
    fun snapshotMono(): ShortArray {
        val ch = capturedChannels
        if (ch <= 1) return pcm.copyOf(len)
        val frames = len / ch
        val out = ShortArray(frames)
        var i = 0
        var f = 0
        while (f < frames) {
            var s = 0
            repeat(ch) { s += pcm[i++] }
            out[f++] = (s / ch).toShort()
        }
        return out
    }

    @Synchronized
    fun resetCapture() {
        len = 0
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        sampleRate = inputAudioFormat.sampleRate
        channels = inputAudioFormat.channelCount
        pcm16 = inputAudioFormat.encoding == C.ENCODING_PCM_16BIT
        capturedRate = sampleRate
        capturedChannels = channels
        val needed = sampleRate * channels * 60 * MAX_MINUTES
        if (pcm.size < needed && sampleRate > 0 && channels > 0) pcm = ShortArray(needed)
        LokiLogger.i(TAG, "tap configured: ${sampleRate}Hz ch=$channels enc=${inputAudioFormat.encoding} pcm16=$pcm16")
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        val canCapture = analyzing && pcm16
        if (canCapture && pcm.isNotEmpty()) {
            capture(inputBuffer.duplicate().order(ByteOrder.LITTLE_ENDIAN))
        }

        // Pure pass-through.
        val out = replaceOutputBuffer(remaining)
        out.put(inputBuffer)
        out.flip()
    }

    @Synchronized
    private fun capture(buf: ByteBuffer) {
        var i = len
        val cap = pcm.size
        while (buf.remaining() >= 2 && i < cap) {
            pcm[i++] = buf.short
        }
        len = i
    }

    private companion object {
        const val TAG = "JukeboxTap"
        const val MAX_MINUTES = 6
    }
}
