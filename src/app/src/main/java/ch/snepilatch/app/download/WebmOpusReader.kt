package ch.snepilatch.app.download

import java.io.EOFException
import java.io.InputStream

/**
 * Just enough Matroska to pull the Opus packets out of the WebM YouTube serves: the track's
 * CodecPrivate (an OpusHead block) and every frame in every cluster. Streams rather than buffering,
 * since a long mix is tens of megabytes.
 *
 * Anything that is not an audio-only Opus track, or that uses laced blocks, is reported as
 * unsupported so the caller can keep the original file instead of writing a broken one.
 */
internal object WebmOpusReader {

    private const val ID_SEGMENT = 0x18538067L
    private const val ID_TRACKS = 0x1654AE6BL
    private const val ID_TRACK_ENTRY = 0xAEL
    private const val ID_TRACK_NUMBER = 0xD7L
    private const val ID_CODEC_ID = 0x86L
    private const val ID_CODEC_PRIVATE = 0x63A2L
    private const val ID_CLUSTER = 0x1F43B675L
    private const val ID_BLOCK_GROUP = 0xA0L
    private const val ID_SIMPLE_BLOCK = 0xA3L
    private const val ID_BLOCK = 0xA1L

    private val DESCEND = setOf(ID_SEGMENT, ID_TRACKS, ID_TRACK_ENTRY, ID_CLUSTER, ID_BLOCK_GROUP)

    class UnsupportedWebm(message: String) : Exception(message)

    /**
     * Reads [input], handing the OpusHead to [onHeader] and then each Opus packet to [onPacket].
     * Throws [UnsupportedWebm] when the file is not a plain Opus stream this can rehouse.
     */
    fun read(input: InputStream, onHeader: (ByteArray) -> Unit, onPacket: (ByteArray) -> Unit) {
        val reader = Reader(input)
        var opusTrack: Long? = null
        var codecId: String? = null
        var pendingHeader: ByteArray? = null
        var headerEmitted = false

        try {
            while (true) {
                val id = reader.readId() ?: break
                val size = reader.readSize()

                // Master elements have no payload of their own; falling through descends into them.
                if (id !in DESCEND) {
                    when (id) {
                        ID_TRACK_NUMBER -> opusTrack = reader.readUInt(size)
                        ID_CODEC_ID -> codecId = reader.readString(size)
                        ID_CODEC_PRIVATE -> pendingHeader = reader.readBytes(size.toInt())
                        ID_SIMPLE_BLOCK, ID_BLOCK -> {
                            if (!headerEmitted) {
                                onHeader(opusHeader(pendingHeader, codecId))
                                headerEmitted = true
                            }
                            readBlock(reader, size.toInt(), opusTrack, onPacket)
                        }
                        else -> reader.skip(size)
                    }
                }
            }
        } catch (e: EOFException) {
            // Input that stops mid-element is a tail-truncated capture, not a broken file: every
            // packet handed to onPacket was whole, so the caller can close a valid stream around them
            // and the result is simply a little short. Reported only when nothing usable came out —
            // without this, a track the playback cache held all but the last few kilobytes of failed
            // the whole remux and fell back to keeping the untagged original container.
            if (!headerEmitted) throw UnsupportedWebm("truncated before the first Opus block: ${e.message}")
        }
        if (!headerEmitted) throw UnsupportedWebm("no Opus track found")
    }

    private fun opusHeader(pendingHeader: ByteArray?, codecId: String?): ByteArray {
        val head = pendingHeader ?: throw UnsupportedWebm("no CodecPrivate before the first block")
        if (codecId != null && !codecId.contains("OPUS", ignoreCase = true)) {
            throw UnsupportedWebm("track is $codecId, not Opus")
        }
        return head
    }

    /** Block layout: track number, 16-bit timecode, flags, then the frame data. */
    private fun readBlock(reader: Reader, size: Int, expectedTrack: Long?, onPacket: (ByteArray) -> Unit) {
        val before = reader.position
        val track = reader.readVint(stripMarker = true)
        reader.skip(2) // timecode, relative to the cluster and irrelevant once repacked
        val flags = reader.readBytes(1)[0].toInt() and 0xFF
        val consumed = (reader.position - before).toInt()
        val payload = size - consumed

        val lacing = (flags shr 1) and 0x03
        if (lacing != 0) throw UnsupportedWebm("laced blocks are not supported")

        if (expectedTrack != null && track != expectedTrack) {
            reader.skip(payload.toLong())
            return
        }
        onPacket(reader.readBytes(payload))
    }

    private class Reader(private val input: InputStream) {
        var position = 0L
            private set

        /** Element ids keep their length marker, so they compare against the constants above. */
        fun readId(): Long? {
            val first = input.read()
            if (first < 0) return null
            position++
            val length = leadingLength(first)
            var value = first.toLong()
            repeat(length - 1) {
                val b = input.read()
                if (b < 0) throw EOFException("truncated element id")
                position++
                value = (value shl 8) or b.toLong()
            }
            return value
        }

        fun readSize(): Long = readVint(stripMarker = true)

        fun readVint(stripMarker: Boolean): Long {
            val first = input.read()
            if (first < 0) throw EOFException("truncated vint")
            position++
            val length = leadingLength(first)
            var value = if (stripMarker) (first and (0xFF shr length)).toLong() else first.toLong()
            repeat(length - 1) {
                val b = input.read()
                if (b < 0) throw EOFException("truncated vint")
                position++
                value = (value shl 8) or b.toLong()
            }
            return value
        }

        fun readBytes(count: Int): ByteArray {
            val out = ByteArray(count)
            var read = 0
            while (read < count) {
                val n = input.read(out, read, count - read)
                if (n < 0) throw EOFException("truncated element body")
                read += n
            }
            position += count
            return out
        }

        fun readUInt(size: Long): Long {
            var value = 0L
            readBytes(size.toInt()).forEach { value = (value shl 8) or (it.toLong() and 0xFF) }
            return value
        }

        fun readString(size: Long): String = String(readBytes(size.toInt())).trimEnd('\u0000')

        fun skip(count: Long) {
            var remaining = count
            val buf = ByteArray(8192)
            while (remaining > 0) {
                val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                if (n < 0) return
                remaining -= n
                position += n
            }
        }

        private fun leadingLength(first: Int): Int = when {
            first and 0x80 != 0 -> 1
            first and 0x40 != 0 -> 2
            first and 0x20 != 0 -> 3
            first and 0x10 != 0 -> 4
            first and 0x08 != 0 -> 5
            first and 0x04 != 0 -> 6
            first and 0x02 != 0 -> 7
            else -> 8
        }
    }
}
