package ch.snepilatch.app.playback

import java.io.InputStream
import java.io.OutputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Deezer's block scheme: the stream is 2048-byte blocks, every third one Blowfish/CBC encrypted
 * against a fixed IV with no chaining between blocks. Shared by [DeezerDecryptProxy], which serves
 * ranges to the player, and by the downloader, which writes whole files.
 */
internal object DeezerBlockCipher {

    const val BLOCK = 2048
    private val IV = byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7)

    /**
     * Decrypts [upstream] into [out], starting at [startBlock] and dropping [skip] leading bytes.
     *
     * One Cipher per stream rather than per block: this runs on a single thread and never escapes
     * the call, and doFinal resets a CBC cipher back to its post-init state, so every block still
     * decrypts against the same IV the block-positional scheme needs. That turns ~5000 Blowfish key
     * expansions per track into one.
     */
    fun decryptInto(
        out: OutputStream,
        upstream: InputStream,
        key: ByteArray,
        startBlock: Int = 0,
        skip: Long = 0L,
    ): Long {
        val cipher = Cipher.getInstance("Blowfish/CBC/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "Blowfish"), IvParameterSpec(IV))
        }
        var blockIndex = startBlock
        var toSkip = skip
        var written = 0L
        val buf = ByteArray(BLOCK)
        while (true) {
            val n = readBlock(upstream, buf)
            if (n <= 0) break
            val decoded = if (blockIndex % 3 == 0 && n == BLOCK) cipher.doFinal(buf) else buf.copyOf(n)
            val off = if (toSkip > 0) minOf(toSkip, decoded.size.toLong()).toInt() else 0
            toSkip -= off
            if (off < decoded.size) {
                out.write(decoded, off, decoded.size - off)
                written += decoded.size - off
            }
            blockIndex++
        }
        return written
    }

    /** Reads exactly [buf].size bytes unless EOF; returns bytes read. */
    fun readBlock(input: InputStream, buf: ByteArray): Int {
        var read = 0
        while (read < buf.size) {
            val n = input.read(buf, read, buf.size - read)
            if (n == -1) break
            read += n
        }
        return read
    }

    /** Relays send the key as hex, but not always; a non-hex key is used as its raw UTF-8 bytes. */
    fun hexToBytes(s: String): ByteArray {
        val clean = s.trim()
        val isHex = clean.length % 2 == 0 && clean.isNotEmpty() &&
            clean.all { it in "0123456789abcdefABCDEF" }
        return if (isHex) {
            ByteArray(clean.length / 2) {
                ((Character.digit(clean[it * 2], 16) shl 4) + Character.digit(clean[it * 2 + 1], 16)).toByte()
            }
        } else {
            clean.toByteArray(Charsets.UTF_8)
        }
    }
}
