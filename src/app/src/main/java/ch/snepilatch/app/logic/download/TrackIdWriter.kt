package ch.snepilatch.app.logic.download

import java.io.ByteArrayOutputStream

/**
 * Adds the track id tag (#566) to a file that is already on the phone (#932), and the title, artist,
 * album and cover it lacks (#946). Only the tag area changes: every tag already there and every audio
 * byte stay as they were, and nothing is overwritten. Pure bytes in, bytes out, so the caller decides how
 * the result replaces the file. Null when the layout is not one this knows how to change safely; the file
 * is then left alone.
 */
internal object TrackIdWriter {

    private const val KEY = TrackTags.TRACK_ID_KEY
    private const val PICTURE_KEY = "METADATA_BLOCK_PICTURE"

    private val TAGGABLE = setOf("opus", "ogg", "flac", "m4a", "mp4", "mp3")

    /** What the downloads write besides the id, and so what a file can be missing. */
    enum class Field { TITLE, ARTIST, ALBUM, COVER }

    /** Whether a file of this name is a format [withTags] can write to. */
    fun canTag(fileName: String): Boolean = fileName.substringAfterLast('.', "").lowercase() in TAGGABLE

    /** The fields [bytes] has no value for; null when its layout is not one this can change. */
    fun missing(bytes: ByteArray): Set<Field>? = when {
        bytes.startsWith("OggS") -> Ogg.missing(bytes)
        bytes.startsWith("fLaC") -> Flac.missing(bytes)
        isMp4(bytes) -> Mp4.missing(bytes)
        Id3Tags.isMp3(bytes) -> Id3Tags.missing(bytes)
        else -> null
    }

    /**
     * [bytes] with each field of [tags] the file has no value for added, and [TrackTags.trackId] (when set)
     * replacing any id already there. Blank or null fields in [tags] add nothing.
     */
    fun withTags(bytes: ByteArray, tags: TrackTags): ByteArray? = when {
        bytes.startsWith("OggS") -> Ogg.withTags(bytes, tags)
        bytes.startsWith("fLaC") -> Flac.withTags(bytes, tags)
        isMp4(bytes) -> Mp4.withTags(bytes, tags)
        Id3Tags.isMp3(bytes) -> Id3Tags.withTags(bytes, tags)
        else -> null
    }

    fun withTrackId(bytes: ByteArray, id: String): ByteArray? = withTags(bytes, TrackTags("", "", trackId = id))

    private fun isMp4(bytes: ByteArray) = bytes.size > 8 && String(bytes, 4, 4, Charsets.ISO_8859_1) == "ftyp"

    private fun ByteArray.startsWith(magic: String) =
        size >= magic.length && String(this, 0, magic.length, Charsets.ISO_8859_1) == magic

    /** A parsed Vorbis comment block: what precedes the comments, the comments, and what follows them. */
    private class Comments(val head: ByteArray, val comments: List<ByteArray>, val tail: ByteArray) {
        /** Upper-case keys that carry a value. */
        val keys: Set<String> = comments.map { String(it, Charsets.UTF_8) }
            .filter { it.substringAfter('=', "").isNotBlank() }
            .mapTo(HashSet()) { it.substringBefore('=').uppercase() }
    }

    /** [prefix] is what comes before the vendor string: "OpusTags" in Opus, nothing in FLAC. */
    private fun parseComments(block: ByteArray, prefix: Int): Comments {
        var at = prefix
        val vendorLength = readLe32(block, at)
        at += 4 + vendorLength
        val head = block.copyOfRange(0, at)
        val count = readLe32(block, at)
        at += 4
        val comments = ArrayList<ByteArray>(count + 1)
        repeat(count) {
            val length = readLe32(block, at)
            comments += block.copyOfRange(at + 4, at + 4 + length)
            at += 4 + length
        }
        // Bytes after the last comment (Opus allows some) are kept.
        return Comments(head, comments, block.copyOfRange(at, block.size))
    }

    private fun missingFrom(keys: Set<String>, hasCover: Boolean) = buildSet {
        if ("TITLE" !in keys) add(Field.TITLE)
        if ("ARTIST" !in keys) add(Field.ARTIST)
        if ("ALBUM" !in keys) add(Field.ALBUM)
        if (!hasCover) add(Field.COVER)
    }

    /**
     * The comment block with the fields it lacks added from [tags] and the id replaced. Opus carries its
     * cover as a comment, FLAC in a PICTURE block of its own, hence [coverAsComment].
     */
    private fun commentsWith(block: ByteArray, prefix: Int, tags: TrackTags, coverAsComment: Boolean): ByteArray {
        val parsed = parseComments(block, prefix)
        val added = buildList {
            if ("TITLE" !in parsed.keys && tags.title.isNotBlank()) add("TITLE=${tags.title}")
            if ("ARTIST" !in parsed.keys && tags.artist.isNotBlank()) add("ARTIST=${tags.artist}")
            if ("ALBUM" !in parsed.keys && !tags.album.isNullOrBlank()) add("ALBUM=${tags.album}")
            tags.trackId?.let { add("$KEY=$it") }
            val cover = tags.cover.takeIf { coverAsComment && PICTURE_KEY !in parsed.keys }
            cover?.let { add("$PICTURE_KEY=${VorbisComments.encodePicture(it)}") }
        }
        val replaced = added.map { it.substringBefore('=').uppercase() + "=" }.toSet()
        val kept = parsed.comments.filter { comment ->
            replaced.none { String(comment, Charsets.UTF_8).startsWith(it, ignoreCase = true) }
        }
        val comments = kept + added.map { it.toByteArray(Charsets.UTF_8) }
        val out = ByteArrayOutputStream()
        out.write(parsed.head)
        writeLe32(out, comments.size)
        comments.forEach {
            writeLe32(out, it.size)
            out.write(it)
        }
        out.write(parsed.tail)
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

        /** The pages, the index of the page that ends the comment packet, and the packet. */
        private class Tags(val pages: List<Page>, val last: Int, val packet: ByteArray)

        fun missing(bytes: ByteArray): Set<Field>? {
            val tags = tags(bytes) ?: return null
            val keys = parseComments(tags.packet, 8).keys
            return missingFrom(keys, hasCover = PICTURE_KEY in keys)
        }

        fun withTags(bytes: ByteArray, add: TrackTags): ByteArray? {
            val found = tags(bytes) ?: return null
            val pages = found.pages
            val last = found.last
            val serial = readLe32(bytes, pages[0].start + 14)
            val newTagPages = page(commentsWith(found.packet, 8, add, coverAsComment = true), serial, firstSequence = 1)
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

        private fun tags(bytes: ByteArray): Tags? {
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
            if (tags.size < 8 || String(tags, 0, 8, Charsets.ISO_8859_1) != "OpusTags") return null
            return Tags(pages, last, tags)
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

    /**
     * FLAC: the VORBIS_COMMENT block grows or is added, and a PICTURE block is added when there is none; the
     * audio frames after the metadata are untouched.
     */
    private object Flac {
        private const val TYPE_VORBIS_COMMENT = 4
        private const val TYPE_PICTURE = 6
        private const val LAST_BLOCK = 0x80

        private class Block(val type: Int, val data: ByteArray)

        /** The metadata blocks and where the audio frames start. */
        private class Metadata(val blocks: MutableList<Block>, val audioAt: Int)

        private val EMPTY_COMMENTS = ByteArray(8)

        fun missing(bytes: ByteArray): Set<Field>? {
            val metadata = metadata(bytes) ?: return null
            val comments = metadata.blocks.firstOrNull { it.type == TYPE_VORBIS_COMMENT }?.data ?: EMPTY_COMMENTS
            return missingFrom(parseComments(comments, 0).keys, hasCover = metadata.blocks.any { it.type == TYPE_PICTURE })
        }

        fun withTags(bytes: ByteArray, tags: TrackTags): ByteArray? {
            val metadata = metadata(bytes) ?: return null
            val blocks = metadata.blocks
            val index = blocks.indexOfFirst { it.type == TYPE_VORBIS_COMMENT }
            val current = if (index >= 0) blocks[index].data else EMPTY_COMMENTS
            val updated = Block(TYPE_VORBIS_COMMENT, commentsWith(current, 0, tags, coverAsComment = false))
            if (index >= 0) blocks[index] = updated else blocks.add(1.coerceAtMost(blocks.size), updated)
            if (blocks.none { it.type == TYPE_PICTURE }) {
                tags.cover?.let { blocks.add(blocks.indexOf(updated) + 1, Block(TYPE_PICTURE, VorbisComments.pictureBlock(it))) }
            }
            val out = ByteArrayOutputStream(bytes.size + 256)
            out.write(bytes, 0, 4)
            blocks.forEachIndexed { i, block ->
                out.write(block.type or (if (i == blocks.lastIndex) LAST_BLOCK else 0))
                out.write((block.data.size ushr 16) and 0xFF)
                out.write((block.data.size ushr 8) and 0xFF)
                out.write(block.data.size and 0xFF)
                out.write(block.data)
            }
            out.write(bytes, metadata.audioAt, bytes.size - metadata.audioAt)
            return out.toByteArray()
        }

        private fun metadata(bytes: ByteArray): Metadata? {
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
            return Metadata(blocks, at)
        }
    }

    /**
     * MP4: the atoms a file lacks are appended to the `moov/udta/meta/ilst` that holds the tags (or a new
     * udta when none does), and every box on that path grows by their size. If `moov` sits before the audio,
     * the chunk offsets in `stco`/`co64` move by the same amount.
     */
    private object Mp4 {
        private const val HEADER = 8

        /** [large] for a box with a 64-bit size field: it can be walked past but not grown. */
        private class Box(val start: Int, val size: Int, val type: String, val large: Boolean = false) {
            val end get() = start + size
        }

        /** The top-level boxes, `moov`, and the `moov/udta/meta/ilst` path to the tags when a udta holds them. */
        private class Layout(val top: List<Box>, val moov: Box, val tags: List<Box>?)

        private val ATOM_OF = mapOf(
            Field.TITLE to "\u00A9nam",
            Field.ARTIST to "\u00A9ART",
            Field.ALBUM to "\u00A9alb",
            Field.COVER to "covr",
        )

        private fun layout(bytes: ByteArray): Layout? {
            val top = children(bytes, 0, bytes.size) ?: return null
            // A box with a 64-bit size can be walked past but not grown, so none on the path may have one.
            val moov = top.firstOrNull { it.type == "moov" && !it.large } ?: return null
            val udtas = children(bytes, moov.start + HEADER, moov.end)?.filter { it.type == "udta" } ?: return null
            // Some recorders put a udta of their own (Samsung's SDLN, smrd, smta) before the one that
            // holds the tags, so the tags are looked for in each, not only the first.
            val tags = udtas.firstNotNullOfOrNull { udta ->
                val meta = children(bytes, udta.start + HEADER, udta.end)?.firstOrNull { it.type == "meta" }
                val ilst = meta?.let { children(bytes, it.start + HEADER + 4, it.end) }?.firstOrNull { it.type == "ilst" }
                ilst?.let { listOf(moov, udta, meta, it) }?.takeIf { path -> path.none { it.large } }
            }
            return Layout(top, moov, tags)
        }

        private fun atoms(bytes: ByteArray, layout: Layout): Set<String> =
            layout.tags?.last()?.let { ilst -> children(bytes, ilst.start + HEADER, ilst.end) }
                ?.mapTo(HashSet()) { it.type }.orEmpty()

        fun missing(bytes: ByteArray): Set<Field>? {
            val layout = layout(bytes) ?: return null
            val atoms = atoms(bytes, layout)
            return ATOM_OF.filterValues { it !in atoms }.keys
        }

        fun withTags(bytes: ByteArray, tags: TrackTags): ByteArray? {
            val layout = layout(bytes) ?: return null
            val lacking = ATOM_OF.filterValues { it !in atoms(bytes, layout) }.keys
            // Only what the file lacks: a blank title or artist and a null album or cover write nothing.
            val add = TrackTags(
                title = if (Field.TITLE in lacking) tags.title else "",
                artist = if (Field.ARTIST in lacking) tags.artist else "",
                album = tags.album.takeIf { Field.ALBUM in lacking },
                cover = tags.cover.takeIf { Field.COVER in lacking },
                trackId = tags.trackId,
            )
            val moov = layout.moov
            // Where the new bytes go, what they are, and which boxes enclose that point. A second udta is
            // fine where none holds the tags: the Samsung files already carry two.
            val entries = Mp4Tagger.ilstEntries(add)
            if (entries.isEmpty()) return bytes
            val (insertAt, inserted, enclosing) = when (val path = layout.tags) {
                null -> Triple(moov.end, Mp4Tagger.udta(add), listOf(moov))
                else -> Triple(path.last().end, entries, path)
            }
            val top = layout.top
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
