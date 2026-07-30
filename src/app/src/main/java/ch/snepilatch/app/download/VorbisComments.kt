package ch.snepilatch.app.download

import java.io.ByteArrayOutputStream

/** Metadata to embed. Everything comes from Spfy, which knows the release far better than YouTube. */
data class TrackTags(
    val title: String,
    val artist: String,
    val album: String? = null,
    val albumArtist: String? = null,
    val trackNumber: Int? = null,
    val year: String? = null,
    val cover: Cover? = null,
) {
    data class Cover(val bytes: ByteArray, val mimeType: String) {
        override fun equals(other: Any?) = other is Cover && mimeType == other.mimeType &&
            bytes.contentEquals(other.bytes)
        override fun hashCode() = 31 * mimeType.hashCode() + bytes.contentHashCode()
    }
}

/**
 * Vorbis comments, the tag format shared by Ogg Opus and FLAC. Writing it once is the reason
 * downloads rehouse Opus into Ogg rather than leaving it in WebM.
 */
internal object VorbisComments {

    private const val VENDOR = "Snepilatch"
    private val OPUS_TAGS_MAGIC = "OpusTags".toByteArray(Charsets.US_ASCII)

    /** Builds an OpusTags packet: the magic, then the comment block. */
    fun opusTags(tags: TrackTags): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(OPUS_TAGS_MAGIC)
        writeCommentBlock(out, tags)
        return out.toByteArray()
    }

    /**
     * The bare comment block for FLAC's VORBIS_COMMENT. Cover art is left out because FLAC carries
     * images in a real PICTURE block instead of the base64 comment Ogg has to use.
     */
    fun commentBlock(tags: TrackTags): ByteArray {
        val out = ByteArrayOutputStream()
        writeCommentBlock(out, tags, includeCover = false)
        return out.toByteArray()
    }

    /** Raw FLAC PICTURE block payload, the same bytes Ogg base64-encodes into a comment. */
    fun pictureBlock(cover: TrackTags.Cover): ByteArray {
        val mime = cover.mimeType.toByteArray(Charsets.US_ASCII)
        val out = ByteArrayOutputStream()
        writeBe(out, 3)
        writeBe(out, mime.size)
        out.write(mime)
        repeat(5) { writeBe(out, 0) } // empty description, then width, height, depth, colours
        writeBe(out, cover.bytes.size)
        out.write(cover.bytes)
        return out.toByteArray()
    }

    private fun writeCommentBlock(out: ByteArrayOutputStream, tags: TrackTags, includeCover: Boolean = true) {
        val vendor = VENDOR.toByteArray(Charsets.UTF_8)
        writeLe(out, vendor.size)
        out.write(vendor)

        val comments = buildList {
            add("TITLE" to tags.title)
            add("ARTIST" to tags.artist)
            tags.album?.let { add("ALBUM" to it) }
            tags.albumArtist?.let { add("ALBUMARTIST" to it) }
            tags.trackNumber?.let { add("TRACKNUMBER" to it.toString()) }
            tags.year?.let { add("DATE" to it) }
            if (includeCover) tags.cover?.let { add("METADATA_BLOCK_PICTURE" to encodePicture(it)) }
        }.filter { it.second.isNotBlank() }

        writeLe(out, comments.size)
        for ((key, value) in comments) {
            val bytes = "$key=$value".toByteArray(Charsets.UTF_8)
            writeLe(out, bytes.size)
            out.write(bytes)
        }
    }

    /**
     * Cover art rides in a base64 FLAC picture block, which is how both Opus and FLAC carry images.
     * Dimensions are declared zero: they are optional and players read them from the image itself.
     */
    // java.util.Base64 rather than android.util.Base64: identical output, available from API 26, and
    // it does not return null under unit tests, which is what hid the oversized-packet bug.
    private fun encodePicture(cover: TrackTags.Cover): String =
        java.util.Base64.getEncoder().encodeToString(pictureBlock(cover))

    private fun writeLe(out: ByteArrayOutputStream, value: Int) {
        for (i in 0 until 4) out.write((value ushr (8 * i)) and 0xFF)
    }

    private fun writeBe(out: ByteArrayOutputStream, value: Int) {
        for (i in 3 downTo 0) out.write((value ushr (8 * i)) and 0xFF)
    }
}
