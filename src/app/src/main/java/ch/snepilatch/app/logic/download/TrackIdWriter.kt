package ch.snepilatch.app.logic.download

import java.io.ByteArrayOutputStream

/**
 * Adds the track id tag (#566) to a file that is already on the phone (#932). Only the tag area
 * changes: every other tag and every audio byte stay as they were. Pure bytes in, bytes out, so the
 * caller decides how the result replaces the file. Null when the layout is not one this knows how to
 * change safely; the file is then left alone.
 */
internal object TrackIdWriter {

    private const val KEY = TrackTags.TRACK_ID_KEY

    private val TAGGABLE = setOf("opus", "ogg", "flac", "m4a", "mp4")

    /** Whether a file of this name is a format [withTrackId] can write to; MP3 is not. */
    fun canTag(fileName: String): Boolean = fileName.substringAfterLast('.', "").lowercase() in TAGGABLE

    fun withTrackId(bytes: ByteArray, id: String): ByteArray? = when {
        bytes.startsWith("OggS") -> Ogg.withComment(bytes, "$KEY=$id")
        bytes.startsWith("fLaC") -> Flac.withComment(bytes, "$KEY=$id")
        bytes.size > 8 && String(bytes, 4, 4, Charsets.ISO_8859_1) == "ftyp" -> Mp4.withFreeform(bytes, KEY, id)
        else -> null
    }

    private fun ByteArray.startsWith(magic: String) =
        size >= magic.length && String(this, 0, magic.length, Charsets.ISO_8859_1) == magic

    /**
     * A Vorbis comment block with [comment] added and any earlier value for its key dropped. [prefix] is
     * what comes before the vendor string: "OpusTags" in Opus, nothing in FLAC. Bytes after the last
     * comment (Opus allows some) are kept.
     */
    private fun commentsWith(block: ByteArray, prefix: Int, comment: String): ByteArray {
        var at = prefix
        val vendorLength = readLe32(block, at)
        at += 4 + vendorLength
        val count = readLe32(block, at)
        at += 4
        val comments = ArrayList<ByteArray>(count + 1)
        repeat(count) {
            val length = readLe32(block, at)
            comments += block.copyOfRange(at + 4, at + 4 + length)
            at += 4 + length
        }
        val key = comment.substringBefore('=') + "="
        comments.removeAll { String(it, Charsets.UTF_8).startsWith(key, ignoreCase = true) }
        comments += comment.toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream()
        out.write(block, 0, prefix + 4 + vendorLength)
        writeLe32(out, comments.size)
        comments.forEach {
            writeLe32(out, it.size)
            out.write(it)
        }
        out.write(block, at, block.size - at)
        return out.toByteArray()
    }

    /** Ogg Opus: the comment packet is rewritten into new pages, and the pages after it are renumbered. */
    private object Ogg {
        private const val HEADER = 27
        private const val MAX_SEGMENT = 255
        private const val MAX_SEGMENTS_PER_PAGE = 255
        private const val CONTINUED = 0x01

        private class Page(val start: Int, val segments: IntArray) {
            val headerLength = HEADER + segments.size
            val bodyLength = segments.sum()
            val end get() = start + headerLength + bodyLength
        }

        fun withComment(bytes: ByteArray, comment: String): ByteArray? {
            val pages = pages(bytes) ?: return null
            if (pages.size < 3) return null
            // Page 0 is OpusHead alone. The comment packet starts on page 1 and must end exactly where a
            // page ends, as the spec asks, so the audio pages after it can be kept whole.
            var last = 1
            while (true) {
                val segments = pages[last].segments
                val endsAt = segments.indexOfFirst { it < MAX_SEGMENT }
                if (endsAt != -1) {
                    if (endsAt != segments.lastIndex) return null
                    break
                }
                last++
                if (last >= pages.size) return null
            }
            val packet = ByteArrayOutputStream()
            for (i in 1..last) packet.write(bytes, pages[i].start + pages[i].headerLength, pages[i].bodyLength)
            val tags = packet.toByteArray()
            if (String(tags, 0, 8, Charsets.ISO_8859_1) != "OpusTags") return null

            val serial = readLe32(bytes, pages[0].start + 14)
            val newTagPages = page(commentsWith(tags, 8, comment), serial, firstSequence = 1)
            val shift = newTagPages.size - last

            val out = ByteArrayOutputStream(bytes.size + 4096)
            out.write(bytes, 0, pages[0].end)
            newTagPages.forEach { out.write(it) }
            for (i in last + 1 until pages.size) {
                val page = bytes.copyOfRange(pages[i].start, pages[i].end)
                if (shift != 0) {
                    writeLe32(page, 18, readLe32(page, 18) + shift)
                    writeLe32(page, 22, 0)
                    writeLe32(page, 22, OggCrc.of(page))
                }
                out.write(page)
            }
            // Anything after the last page (there should be nothing) stays as it was.
            out.write(bytes, pages.last().end, bytes.size - pages.last().end)
            return out.toByteArray()
        }

        private fun pages(bytes: ByteArray): List<Page>? {
            val pages = ArrayList<Page>()
            var at = 0
            while (at + HEADER <= bytes.size && String(bytes, at, 4, Charsets.ISO_8859_1) == "OggS") {
                val count = bytes[at + 26].toInt() and 0xFF
                if (at + HEADER + count > bytes.size) return null
                val page = Page(at, IntArray(count) { bytes[at + HEADER + it].toInt() and 0xFF })
                if (page.end > bytes.size) return null
                pages += page
                at = page.end
            }
            return pages.takeIf { it.isNotEmpty() }
        }

        /** [packet] as pages of at most 255 segments; granule -1 until the page that ends it, which has 0. */
        private fun page(packet: ByteArray, serial: Int, firstSequence: Int): List<ByteArray> {
            val lacing = ArrayList<Int>()
            var remaining = packet.size
            while (remaining >= MAX_SEGMENT) {
                lacing += MAX_SEGMENT
                remaining -= MAX_SEGMENT
            }
            lacing += remaining
            val pages = ArrayList<ByteArray>()
            var segment = 0
            var body = 0
            while (segment < lacing.size) {
                val segments = lacing.subList(segment, minOf(segment + MAX_SEGMENTS_PER_PAGE, lacing.size))
                val length = segments.sum()
                val finalPage = segment + segments.size == lacing.size
                val page = ByteArray(HEADER + segments.size + length)
                "OggS".toByteArray(Charsets.ISO_8859_1).copyInto(page)
                page[5] = (if (segment > 0) CONTINUED else 0).toByte()
                writeLe64(page, 6, if (finalPage) 0L else -1L)
                writeLe32(page, 14, serial)
                writeLe32(page, 18, firstSequence + pages.size)
                page[26] = segments.size.toByte()
                segments.forEachIndexed { i, v -> page[HEADER + i] = v.toByte() }
                packet.copyInto(page, HEADER + segments.size, body, body + length)
                writeLe32(page, 22, OggCrc.of(page))
                pages += page
                segment += segments.size
                body += length
            }
            return pages
        }
    }

    /** FLAC: the VORBIS_COMMENT block grows or is added; the audio frames after the metadata are untouched. */
    private object Flac {
        private const val TYPE_VORBIS_COMMENT = 4
        private const val LAST_BLOCK = 0x80

        fun withComment(bytes: ByteArray, comment: String): ByteArray? {
            class Block(val type: Int, val data: ByteArray)
            val blocks = ArrayList<Block>()
            var at = 4
            while (true) {
                if (at + 4 > bytes.size) return null
                val header = bytes[at].toInt() and 0xFF
                val length = ((bytes[at + 1].toInt() and 0xFF) shl 16) or
                    ((bytes[at + 2].toInt() and 0xFF) shl 8) or (bytes[at + 3].toInt() and 0xFF)
                if (at + 4 + length > bytes.size) return null
                blocks += Block(header and 0x7F, bytes.copyOfRange(at + 4, at + 4 + length))
                at += 4 + length
                if (header and LAST_BLOCK != 0) break
            }
            val index = blocks.indexOfFirst { it.type == TYPE_VORBIS_COMMENT }
            val empty = ByteArrayOutputStream().apply {
                writeLe32(this, 0)
                writeLe32(this, 0)
            }.toByteArray()
            val current = if (index >= 0) blocks[index].data else empty
            val updated = Block(TYPE_VORBIS_COMMENT, commentsWith(current, 0, comment))
            if (index >= 0) blocks[index] = updated else blocks.add(1.coerceAtMost(blocks.size), updated)
            val out = ByteArrayOutputStream(bytes.size + 256)
            out.write(bytes, 0, 4)
            blocks.forEachIndexed { i, block ->
                out.write(block.type or (if (i == blocks.lastIndex) LAST_BLOCK else 0))
                out.write((block.data.size ushr 16) and 0xFF)
                out.write((block.data.size ushr 8) and 0xFF)
                out.write(block.data.size and 0xFF)
                out.write(block.data)
            }
            out.write(bytes, at, bytes.size - at)
            return out.toByteArray()
        }
    }

    /**
     * MP4: a `----` atom is appended to the `moov/udta/meta/ilst` that holds the tags, and every box on that
     * path grows by its size. If `moov` sits before the audio, the chunk offsets in `stco`/`co64` move by the
     * same amount.
     */
    private object Mp4 {
        private const val HEADER = 8

        /** [large] for a box with a 64-bit size field: it can be walked past but not grown. */
        private class Box(val start: Int, val size: Int, val type: String, val large: Boolean = false) {
            val end get() = start + size
        }

        fun withFreeform(bytes: ByteArray, name: String, value: String): ByteArray? {
            val top = children(bytes, 0, bytes.size) ?: return null
            // A box with a 64-bit size can be walked past but not grown, so none on the path may have one.
            val moov = top.firstOrNull { it.type == "moov" && !it.large } ?: return null
            val atom = freeform(name, value)
            val udtas = children(bytes, moov.start + HEADER, moov.end)?.filter { it.type == "udta" } ?: return null
            // Some recorders put a udta of their own (Samsung's SDLN, smrd, smta) before the one that
            // holds the tags, so the tags are looked for in each, not only the first.
            val tags = udtas.firstNotNullOfOrNull { udta ->
                val meta = children(bytes, udta.start + HEADER, udta.end)?.firstOrNull { it.type == "meta" }
                val ilst = meta?.let { children(bytes, it.start + HEADER + 4, it.end) }?.firstOrNull { it.type == "ilst" }
                ilst?.let { listOf(moov, udta, meta, it) }?.takeIf { path -> path.none { it.large } }
            }

            // Where the new bytes go, what they are, and which boxes enclose that point.
            val (insertAt, inserted, enclosing) = when {
                tags != null -> Triple(tags.last().end, atom, tags)
                udtas.isEmpty() -> Triple(moov.end, box("udta", box("meta", ByteArray(4) + hdlr() + box("ilst", atom))), listOf(moov))
                else -> return null
            }
            val delta = inserted.size
            val out = bytes.copyOf(bytes.size + delta)
            inserted.copyInto(out, insertAt)
            bytes.copyInto(out, insertAt + delta, insertAt, bytes.size)
            enclosing.forEach { writeBe32(out, it.start, it.size + delta) }
            // Audio after moov moved by delta, so its chunk offsets have to follow.
            if (top.any { it.type == "mdat" && it.start > moov.start }) {
                val grown = Box(moov.start, moov.size + delta, "moov")
                if (!shiftChunkOffsets(out, grown, delta)) return null
            }
            return out
        }

        private fun children(bytes: ByteArray, from: Int, to: Int): List<Box>? {
            val boxes = ArrayList<Box>()
            var at = from
            while (at + HEADER <= to) {
                val type = String(bytes, at + 4, 4, Charsets.ISO_8859_1)
                // Size 1 means a 64-bit size follows: some recorders write mdat that way even when it is small.
                val large = readBe32(bytes, at) == 1
                val size = if (large && at + 16 <= to) readBe64(bytes, at + 8) else readBe32(bytes, at).toLong()
                if (size < HEADER || at + size > to || size > Int.MAX_VALUE) return null // broken sizes: leave the file alone
                boxes += Box(at, size.toInt(), type, large)
                at += size.toInt()
            }
            return boxes
        }

        private val CONTAINERS = setOf("trak", "mdia", "minf", "stbl")

        private fun shiftChunkOffsets(bytes: ByteArray, moov: Box, delta: Int): Boolean {
            fun walk(from: Int, to: Int): Boolean {
                for (child in children(bytes, from, to) ?: return false) {
                    when (child.type) {
                        in CONTAINERS -> if (child.large || !walk(child.start + HEADER, child.end)) return false
                        "stco" -> {
                            val count = readBe32(bytes, child.start + 12)
                            for (i in 0 until count) {
                                val at = child.start + 16 + i * 4
                                writeBe32(bytes, at, readBe32(bytes, at) + delta)
                            }
                        }
                        "co64" -> return false
                    }
                }
                return true
            }
            return walk(moov.start + HEADER, moov.end)
        }

        private fun freeform(name: String, value: String): ByteArray {
            val mean = box("mean", ByteArray(4) + "com.apple.iTunes".toByteArray(Charsets.US_ASCII))
            val key = box("name", ByteArray(4) + name.toByteArray(Charsets.US_ASCII))
            val data = box("data", byteArrayOf(0, 0, 0, 1) + ByteArray(4) + value.toByteArray(Charsets.UTF_8))
            return box("----", mean + key + data)
        }

        private fun hdlr() = box(
            "hdlr",
            ByteArray(8) + "mdir".toByteArray(Charsets.US_ASCII) + "appl".toByteArray(Charsets.US_ASCII) + ByteArray(9),
        )

        private fun box(type: String, payload: ByteArray): ByteArray {
            val out = ByteArray(HEADER + payload.size)
            writeBe32(out, 0, out.size)
            type.toByteArray(Charsets.ISO_8859_1).copyInto(out, 4)
            payload.copyInto(out, HEADER)
            return out
        }
    }

    private fun readLe32(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) or
            ((b[at + 2].toInt() and 0xFF) shl 16) or ((b[at + 3].toInt() and 0xFF) shl 24)

    private fun writeLe32(b: ByteArray, at: Int, v: Int) {
        for (i in 0 until 4) b[at + i] = ((v ushr (8 * i)) and 0xFF).toByte()
    }

    private fun writeLe32(out: ByteArrayOutputStream, v: Int) {
        for (i in 0 until 4) out.write((v ushr (8 * i)) and 0xFF)
    }

    private fun writeLe64(b: ByteArray, at: Int, v: Long) {
        for (i in 0 until 8) b[at + i] = ((v ushr (8 * i)) and 0xFF).toByte()
    }

    private fun readBe32(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0xFF) shl 24) or ((b[at + 1].toInt() and 0xFF) shl 16) or
            ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)

    private fun readBe64(b: ByteArray, at: Int): Long =
        (readBe32(b, at).toLong() shl 32) or (readBe32(b, at + 4).toLong() and 0xFFFFFFFFL)

    private fun writeBe32(b: ByteArray, at: Int, v: Int) {
        for (i in 0 until 4) b[at + i] = ((v ushr (8 * (3 - i))) and 0xFF).toByte()
    }
}
