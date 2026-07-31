package ch.snepilatch.app.playback

import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.common.util.UnstableApi
import ch.snepilatch.app.util.LokiLogger
import java.io.File
import java.io.OutputStream

/**
 * Keeps the encoded bytes ExoPlayer already downloaded, so saving a track the user listened through
 * costs nothing: the audio is on disk and never has to be fetched a second time.
 *
 * Only non-DRM sources go through here. Spfy CDN audio is Widevine-encrypted, so cached bytes would
 * be unplayable without a fresh license, and keeping them is the Spfy ripping we do not do.
 *
 * Bounded and least-recently-used, so it behaves like a buffer rather than a second library: old
 * tracks fall out on their own and the folder never grows past [MAX_BYTES].
 */
@UnstableApi
object PlaybackCache {

    private const val TAG = "PlaybackCache"
    private const val MAX_BYTES = 512L * 1024 * 1024

    private var cache: SimpleCache? = null

    /**
     * The cache key for a track played from a given source. The source has to be part of it: the same
     * track is Opus-in-WebM from YouTube Music and FLAC from Qobuz, and one key for both meant a
     * download recorded as lossless could be written from cached YouTube bytes, or a part-cached
     * WebM entry could be topped up with FLAC bytes mid-track.
     *
     * Not the url: a googlevideo url is single-use, so it would never hit twice and would keep a
     * fresh copy per resolve.
     */
    fun keyFor(trackUri: String, source: String?): String = "${source ?: "spfy"}|$trackUri"

    fun init(context: Context) {
        if (cache != null) return
        cache = runCatching {
            SimpleCache(
                File(context.cacheDir, "playback"),
                LeastRecentlyUsedCacheEvictor(MAX_BYTES),
                StandaloneDatabaseProvider(context),
            )
        }.onFailure { LokiLogger.w(TAG, "cache unavailable: ${it.message}") }.getOrNull()
    }

    /** Wraps [upstream] so reads are served from disk when present and written to it when not. */
    fun wrap(upstream: DataSource.Factory): DataSource.Factory {
        val c = cache ?: return upstream
        return CacheDataSource.Factory()
            .setCache(c)
            .setUpstreamDataSourceFactory(upstream)
            // A partial track is still worth keeping: the next play resumes into it, and the
            // completeness check below is what decides whether it may be promoted to a download.
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    /**
     * Whether every byte of [key] is on disk. The user's rule for auto-save is that a track with a
     * hole in it must never be written out, so this is deliberately all-or-nothing: a gap anywhere,
     * or an unknown total length, means no.
     */
    fun isComplete(key: String): Boolean {
        val c = cache ?: return false
        val length = ContentMetadata.getContentLength(c.getContentMetadata(key))
        if (length <= 0) return false
        return covered(c, key) >= length
    }

    /** How many contiguous bytes from 0 are cached; stops at the first hole. */
    private fun covered(c: SimpleCache, key: String): Long {
        var position = 0L
        c.getCachedSpans(key).filter { it.isCached }.sortedBy { it.position }.forEach { span ->
            if (span.position > position) return position
            position = maxOf(position, span.position + span.length)
        }
        return position
    }

    /**
     * Copies the cached bytes to [out]. Call [isComplete] first: this writes whatever it has and
     * reports whether that was all of it, so an unchecked call can leave a truncated file behind.
     */
    fun writeTo(key: String, out: OutputStream): Boolean {
        val c = cache ?: return false
        val length = ContentMetadata.getContentLength(c.getContentMetadata(key))
        if (length <= 0) return false
        var position = 0L
        c.getCachedSpans(key).filter { it.isCached }.sortedBy { it.position }.forEach { span ->
            val file = span.file ?: return false
            if (span.position > position) return false
            val skip = position - span.position
            if (skip >= span.length) return@forEach
            file.inputStream().use { input ->
                // skip() is allowed to move less than asked. Ignoring that would copy the overlap
                // twice and shift every following byte, so the file would decode as noise from there.
                var remaining = skip
                while (remaining > 0) {
                    val moved = input.skip(remaining)
                    if (moved <= 0) return false
                    remaining -= moved
                }
                input.copyTo(out)
            }
            position = span.position + span.length
        }
        return position >= length
    }

    fun release() {
        runCatching { cache?.release() }
        cache = null
    }
}
