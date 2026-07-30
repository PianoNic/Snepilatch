package ch.snepilatch.app.download

import java.io.OutputStream

/**
 * Writes an Ogg Opus stream. Used to rehouse the Opus packets YouTube ships inside WebM without
 * touching the audio: the encoded packets are copied verbatim, so the result is bit-identical audio
 * in a container that players and taggers actually handle.
 */
internal class OggOpusWriter(
    private val out: OutputStream,
    private val serial: Int,
    private val preSkip: Int,
) {

    private var sequence = 0
    private var granule = 0L

    /** Opus always reports granule positions at 48 kHz regardless of the original sample rate. */
    private companion object {
        const val SAMPLE_RATE = 48_000
        const val MAX_SEGMENT = 255
        const val MAX_SEGMENTS_PER_PAGE = 255
    }

    fun writeHeaders(opusHead: ByteArray, comments: ByteArray) {
        writePage(listOf(opusHead), granulePosition = 0, firstPage = true, lastPage = false)
        writePage(listOf(comments), granulePosition = 0, firstPage = false, lastPage = false)
    }

    private var pending = mutableListOf<ByteArray>()
    private var pendingSegments = 0

    /**
     * Queues one audio packet, emitting a page whenever the next one would overflow the 255-segment
     * limit. Call [finish] once the stream is done so the last page carries the end-of-stream flag.
     */
    fun add(packet: ByteArray) {
        val needed = packet.size / MAX_SEGMENT + 1
        if (pendingSegments + needed > MAX_SEGMENTS_PER_PAGE && pending.isNotEmpty()) {
            flush(pending, last = false)
            pending = mutableListOf()
            pendingSegments = 0
        }
        pending += packet
        pendingSegments += needed
    }

    fun finish() {
        flush(pending, last = true)
        pending = mutableListOf()
        pendingSegments = 0
    }

    private fun flush(packets: List<ByteArray>, last: Boolean) {
        if (packets.isEmpty()) {
            if (last) writePage(emptyList(), granule, firstPage = false, lastPage = true)
            return
        }
        packets.forEach { granule += OpusPacket.samples(it, SAMPLE_RATE) }
        writePage(packets, granule + preSkip, firstPage = false, lastPage = last)
    }

    private fun writePage(packets: List<ByteArray>, granulePosition: Long, firstPage: Boolean, lastPage: Boolean) {
        val lacing = ArrayList<Int>()
        for (packet in packets) {
            var remaining = packet.size
            while (remaining >= MAX_SEGMENT) {
                lacing += MAX_SEGMENT
                remaining -= MAX_SEGMENT
            }
            lacing += remaining
        }

        val header = ByteArray(27 + lacing.size)
        header[0] = 'O'.code.toByte()
        header[1] = 'g'.code.toByte()
        header[2] = 'g'.code.toByte()
        header[3] = 'S'.code.toByte()
        header[4] = 0
        header[5] = ((if (firstPage) 0x02 else 0) or (if (lastPage) 0x04 else 0)).toByte()
        writeLong(header, 6, granulePosition)
        writeInt(header, 14, serial)
        writeInt(header, 18, sequence++)
        // 22..25 is the checksum, left zero while it is computed over the whole page.
        header[26] = lacing.size.toByte()
        lacing.forEachIndexed { i, value -> header[27 + i] = value.toByte() }

        val body = ByteArray(packets.sumOf { it.size })
        var offset = 0
        for (packet in packets) {
            packet.copyInto(body, offset)
            offset += packet.size
        }

        val crc = OggCrc.of(header, body)
        writeInt(header, 22, crc)
        out.write(header)
        out.write(body)
    }

    private fun writeInt(target: ByteArray, at: Int, value: Int) {
        for (i in 0 until 4) target[at + i] = ((value ushr (8 * i)) and 0xFF).toByte()
    }

    private fun writeLong(target: ByteArray, at: Int, value: Long) {
        for (i in 0 until 8) target[at + i] = ((value ushr (8 * i)) and 0xFF).toByte()
    }
}

/**
 * Ogg's own CRC32: polynomial 0x04C11DB7, no input or output reflection and no final xor, which is
 * why java.util.zip.CRC32 cannot be used here.
 */
internal object OggCrc {

    private val TABLE = IntArray(256) { i ->
        var r = i shl 24
        repeat(8) { r = if (r and 0x80000000.toInt() != 0) (r shl 1) xor 0x04C11DB7 else r shl 1 }
        r
    }

    fun of(vararg chunks: ByteArray): Int {
        var crc = 0
        for (chunk in chunks) {
            for (byte in chunk) {
                crc = (crc shl 8) xor TABLE[((crc ushr 24) and 0xFF) xor (byte.toInt() and 0xFF)]
            }
        }
        return crc
    }
}

/** Decoded length of an Opus packet, read from its table-of-contents byte (RFC 6716 section 3.1). */
internal object OpusPacket {

    /** Frame duration in microseconds for each of the 32 TOC configurations. */
    private val FRAME_US = IntArray(32) { config ->
        when {
            config < 12 -> intArrayOf(10_000, 20_000, 40_000, 60_000)[config % 4]
            config < 16 -> intArrayOf(10_000, 20_000)[config % 2]
            else -> intArrayOf(2_500, 5_000, 10_000, 20_000)[config % 4]
        }
    }

    fun samples(packet: ByteArray, sampleRate: Int): Long {
        if (packet.isEmpty()) return 0
        val toc = packet[0].toInt() and 0xFF
        val frameUs = FRAME_US[toc ushr 3]
        val frames = when (toc and 0x03) {
            0 -> 1
            1, 2 -> 2
            else -> if (packet.size < 2) 0 else packet[1].toInt() and 0x3F
        }
        return frameUs.toLong() * frames * sampleRate / 1_000_000L
    }
}
