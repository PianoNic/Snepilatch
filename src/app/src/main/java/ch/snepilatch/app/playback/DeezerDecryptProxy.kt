package ch.snepilatch.app.playback

import ch.snepilatch.app.util.LokiLogger
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Local loopback HTTP proxy that lets ExoPlayer stream Blowfish-encrypted Deezer
 * audio with no custom DataSource and no ExoPlayer modification.
 *
 * Register an encrypted stream via [register] to get a `http://127.0.0.1:<port>/<id>`
 * URL that plays like any other progressive source. On each request the proxy
 * fetches the upstream encrypted bytes (forwarding the relay's `X-API-Key`),
 * decrypts on the fly — Blowfish/CBC, a fresh IV per 2048-byte block, only every
 * 3rd block (the Deezer scheme) — and serves cleartext FLAC.
 *
 * Decryption is block-positional (no cross-block chaining), so a byte offset maps
 * straight to a block index and HTTP Range requests (seeking) work: we align the
 * upstream fetch to the enclosing 2048-byte block and skip the remainder.
 */
class DeezerDecryptProxy {

    companion object {
        private const val TAG = "DeezerProxy"
        private const val BLOCK = 2048
        private val IV = byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7)
    }

    private class Entry(val url: String, val key: ByteArray, val headers: Map<String, String>)

    private val registry = ConcurrentHashMap<String, Entry>()
    private val ids = AtomicInteger(0)
    private val pool = Executors.newCachedThreadPool { r ->
        Thread(r, "deezer-proxy").apply { isDaemon = true }
    }

    @Volatile
    private var server: ServerSocket? = null

    @Volatile
    private var port = 0

    fun start() {
        if (server != null) return
        val s = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        server = s
        port = s.localPort
        pool.execute {
            while (!s.isClosed) {
                val client = try { s.accept() } catch (_: Exception) { break }
                pool.execute { handle(client) }
            }
        }
        LokiLogger.i(TAG, "started on 127.0.0.1:$port")
    }

    fun stop() {
        registry.clear()
        try { server?.close() } catch (_: Exception) {}
        server = null
    }

    /**
     * Register an encrypted Deezer stream and return a local URL ExoPlayer can
     * play. [keyHex] is the per-track Blowfish key from the relay's track info.
     */
    fun register(url: String, keyHex: String, headers: Map<String, String>): String {
        if (server == null) start()
        val id = ids.incrementAndGet().toString()
        registry[id] = Entry(url, hexToBytes(keyHex), headers)
        return "http://127.0.0.1:$port/$id"
    }

    private fun handle(client: Socket) {
        client.use { sock ->
            try {
                val (path, rangeStart) = readRequest(BufferedInputStream(sock.getInputStream())) ?: return
                val entry = registry[path.trimStart('/')]
                if (entry == null) {
                    writeStatus(sock.getOutputStream(), 404, "Not Found")
                } else {
                    stream(entry, rangeStart, sock.getOutputStream())
                }
            } catch (e: Exception) {
                LokiLogger.w(TAG, "handle error: ${e.message}")
            }
        }
    }

    /** Parse the request line + optional Range header. Returns (path, rangeStart). */
    private fun readRequest(input: InputStream): Pair<String, Long>? {
        val lines = mutableListOf<String>()
        val sb = StringBuilder()
        var b = input.read()
        while (b != -1) {
            if (b == '\n'.code) {
                val line = sb.toString().trim()
                sb.setLength(0)
                if (line.isEmpty()) break
                lines.add(line)
            } else if (b != '\r'.code) {
                sb.append(b.toChar())
            }
            b = input.read()
        }
        val path = lines.firstOrNull()?.split(" ")?.getOrNull(1) ?: return null
        val rangeStart = lines.firstOrNull { it.startsWith("Range:", ignoreCase = true) }
            ?.substringAfter("=", "")?.substringBefore("-")?.trim()?.toLongOrNull() ?: 0L
        return path to rangeStart
    }

    private fun stream(entry: Entry, rangeStart: Long, rawOut: OutputStream) {
        val alignedStart = (rangeStart / BLOCK) * BLOCK
        val conn = openUpstream(entry, alignedStart)
        conn.connect()
        val code = conn.responseCode
        val upstream = BufferedInputStream(conn.inputStream)
        val total = computeTotal(conn, code, alignedStart)

        // Upstream ignored our Range (returned 200) but we need an offset.
        if (code != 206 && alignedStart > 0) skipBytes(upstream, alignedStart)

        val out = BufferedOutputStream(rawOut)
        writeResponseHeader(out, rangeStart, total)
        decryptInto(out, upstream, entry.key, (alignedStart / BLOCK).toInt(), rangeStart - alignedStart)
        out.flush()
        try { conn.disconnect() } catch (_: Exception) {}
    }

    private fun openUpstream(entry: Entry, alignedStart: Long): HttpURLConnection =
        (URL(entry.url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 30_000
            entry.headers.forEach { (k, v) -> setRequestProperty(k, v) }
            if (alignedStart > 0) setRequestProperty("Range", "bytes=$alignedStart-")
        }

    /** Total decrypted size == encrypted size (decryption is length-preserving). */
    private fun computeTotal(conn: HttpURLConnection, code: Int, alignedStart: Long): Long {
        val fromRange = conn.getHeaderField("Content-Range")?.substringAfter("/", "")?.toLongOrNull()
        if (fromRange != null) return fromRange
        val cl = conn.getHeaderField("Content-Length")?.toLongOrNull() ?: return -1L
        return if (code == 206) cl + alignedStart else cl
    }

    private fun writeResponseHeader(out: OutputStream, rangeStart: Long, total: Long) {
        val sb = StringBuilder()
        if (rangeStart > 0 && total > 0) {
            sb.append("HTTP/1.1 206 Partial Content\r\n")
            sb.append("Content-Range: bytes $rangeStart-${total - 1}/$total\r\n")
            sb.append("Content-Length: ${total - rangeStart}\r\n")
        } else {
            sb.append("HTTP/1.1 200 OK\r\n")
            if (total > 0) sb.append("Content-Length: $total\r\n")
        }
        sb.append("Accept-Ranges: bytes\r\n")
        sb.append("Content-Type: audio/flac\r\n")
        sb.append("Connection: close\r\n\r\n")
        out.write(sb.toString().toByteArray(Charsets.US_ASCII))
    }

    /** Decrypt the upstream stream block-by-block, skipping [skip] leading bytes. */
    private fun decryptInto(out: OutputStream, upstream: InputStream, key: ByteArray, startBlock: Int, skip: Long) {
        // One Cipher per stream instead of one per decrypted block. decryptInto runs on a single
        // per-request proxy thread (Cipher is not thread-safe, but it never leaves this call), and
        // doFinal resets a CBC cipher to its post-init state — i.e. back to the fixed IV — so every
        // block still decrypts against the same IV the block-positional (no-chaining) scheme needs.
        // This collapses ~5000 Blowfish key expansions per track down to one.
        val cipher = Cipher.getInstance("Blowfish/CBC/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "Blowfish"), IvParameterSpec(IV))
        }
        var blockIndex = startBlock
        var toSkip = skip
        val buf = ByteArray(BLOCK)
        while (true) {
            val n = readBlock(upstream, buf)
            if (n <= 0) break
            val decoded = if (blockIndex % 3 == 0 && n == BLOCK) cipher.doFinal(buf) else buf.copyOf(n)
            val off = if (toSkip > 0) minOf(toSkip, decoded.size.toLong()).toInt() else 0
            toSkip -= off
            if (off < decoded.size) out.write(decoded, off, decoded.size - off)
            blockIndex++
        }
    }

    private fun skipBytes(input: InputStream, count: Long) {
        var remaining = count
        val buf = ByteArray(8192)
        while (remaining > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
            if (n <= 0) break
            remaining -= n
        }
    }

    /** Read exactly [buf].size bytes unless EOF; returns bytes read. */
    private fun readBlock(input: InputStream, buf: ByteArray): Int {
        var read = 0
        while (read < buf.size) {
            val n = input.read(buf, read, buf.size - read)
            if (n == -1) break
            read += n
        }
        return read
    }

    private fun writeStatus(out: OutputStream, code: Int, msg: String) {
        out.write("HTTP/1.1 $code $msg\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
        out.flush()
    }

    private fun hexToBytes(s: String): ByteArray {
        val clean = s.trim()
        val isHex = clean.length % 2 == 0 && clean.isNotEmpty() && clean.all { it in "0123456789abcdefABCDEF" }
        return if (isHex) {
            ByteArray(clean.length / 2) {
                ((Character.digit(clean[it * 2], 16) shl 4) + Character.digit(clean[it * 2 + 1], 16)).toByte()
            }
        } else {
            clean.toByteArray(Charsets.UTF_8)
        }
    }
}
