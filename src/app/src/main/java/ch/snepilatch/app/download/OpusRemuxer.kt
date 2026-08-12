package ch.snepilatch.app.download

import ch.snepilatch.app.util.LokiLogger
import java.io.InputStream
import java.io.OutputStream

/**
 * Rehouses the Opus stream YouTube serves inside WebM into Ogg, copying the encoded packets
 * untouched. Nothing is decoded or re-encoded, so the audio is bit-identical; only the container
 * changes, to one that players and Vorbis-comment tagging both understand.
 */
internal object OpusRemuxer {

    private const val TAG = "OpusRemux"
    private const val OPUS_HEAD_PRE_SKIP_OFFSET = 10

    /** Returns false when the input is not a plain Opus WebM, leaving [output] untouched. */
    fun remux(input: InputStream, output: OutputStream, tags: TrackTags, serial: Int): Boolean = try {
        var writer: OggOpusWriter? = null
        WebmOpusReader.read(
            input = input,
            onHeader = { opusHead ->
                writer = OggOpusWriter(output, serial, preSkip(opusHead)).also {
                    it.writeHeaders(opusHead, VorbisComments.opusTags(tags))
                }
            },
            onPacket = { packet -> writer?.add(packet) },
        )
        writer?.finish()
        writer != null
    } catch (e: WebmOpusReader.UnsupportedWebm) {
        LokiLogger.w(TAG, "keeping original container: ${e.message}")
        false
    } catch (e: Exception) {
        LokiLogger.e(TAG, "remux failed: ${e.message}")
        false
    }

    /** Samples the decoder discards at the start, carried in OpusHead and needed for granule maths. */
    private fun preSkip(opusHead: ByteArray): Int {
        if (opusHead.size < OPUS_HEAD_PRE_SKIP_OFFSET + 2) return 0
        val lo = opusHead[OPUS_HEAD_PRE_SKIP_OFFSET].toInt() and 0xFF
        val hi = opusHead[OPUS_HEAD_PRE_SKIP_OFFSET + 1].toInt() and 0xFF
        return lo or (hi shl 8)
    }
}
