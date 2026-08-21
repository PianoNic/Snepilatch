package ch.snepilatch.app.download

import android.content.Context
import ch.snepilatch.app.playback.AudioSourceResolver
import ch.snepilatch.app.playback.DeezerBlockCipher
import ch.snepilatch.app.playback.MusicPlaybackService
import ch.snepilatch.app.playback.PlaybackCache
import ch.snepilatch.app.playback.YouTubeMusicSource
import ch.snepilatch.app.util.LokiLogger
import ch.snepilatch.app.viewmodel.AppSettings
import kotify.cdn.StreamInfo
import kotify.cdn.StreamResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/** What a caller needs to download one track. Metadata comes from Spfy, audio from the chosen source. */
data class DownloadRequest(
    val trackUri: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val coverUrl: String? = null,
    val durationMs: Long = 0L,
    /** The album or playlist this was queued from; absent for a one-off track. */
    val contextUri: String? = null,
    val contextName: String? = null,
    val contextType: String? = null,
    /**
     * Decoded audio already taken off the player, used instead of fetching anything. Set on the
     * track-change path, where the capture has to be claimed before the next track overwrites it.
     */
    val capture: MusicPlaybackService.Capture? = null,
    /**
     * Save only from audio the app already has, never from the network. What the
     * keep-what-I-listen-to setting means: hold on to the recording that was just played, rather
     * than fetch a different upload of the same song.
     */
    val localOnly: Boolean = false,
)

sealed interface DownloadOutcome {
    data class Done(val track: DownloadedTrack) : DownloadOutcome
    data class Failed(val reason: String) : DownloadOutcome
    data object NoFolder : DownloadOutcome
}

/**
 * Fetches a track's audio into the user's download folder and records it in [Downloads].
 *
 * Audio comes from whichever source is selected, so this cannot serve the Spfy CDN: that stream is
 * Widevine and the saved bytes would not be playable.
 */
object TrackDownloader {

    private const val TAG = "TrackDownloader"
    private const val BUFFER = 64 * 1024

    /** Range size for the chunked fetch; see [fetchChunked]. */
    private const val CHUNK_BYTES = 4L * 1024 * 1024

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    suspend fun download(
        request: DownloadRequest,
        context: Context,
        notify: Boolean = true,
        onProgress: ((percent: Int) -> Unit)? = null,
    ): DownloadOutcome =
        withContext(Dispatchers.IO) {
            // One fetch per track. Guarded here rather than at each button because the batch and a
            // tap can collide too: two runs would both write a file, and only the second's row
            // survives CONFLICT_REPLACE, orphaning the first file where nothing can find it again.
            if (request.trackUri in Downloads.inProgress.value) {
                LokiLogger.i(TAG, "'${request.title}' is already downloading, not starting a second")
                return@withContext Downloads.find(request.trackUri)
                    ?.let { DownloadOutcome.Done(it) }
                    ?: DownloadOutcome.Failed("already downloading")
            }
            // Anything unexpected becomes a failed notification; a download must not crash the app.
            Downloads.markStarted(request.trackUri)
            try {
                downloadInner(request, context, notify, onProgress)
            } catch (e: CancellationException) {
                // Backing out of the app cancels the scope. The file may well have landed, so this is
                // not a failure to report — but the ongoing progress bar has to go, or it sits there
                // frozen at N% claiming to still be working.
                if (notify) DownloadNotifier.clear(context)
                throw e
            } catch (e: Exception) {
                LokiLogger.e(TAG, "download crashed for ${request.title}", e)
                DownloadNotifier.failed(context, request.title, e.message ?: "unexpected error")
                DownloadOutcome.Failed(e.message ?: "unexpected error")
            } finally {
                Downloads.markFinished(request.trackUri)
            }
        }

    private suspend fun downloadInner(
        request: DownloadRequest,
        context: Context,
        notify: Boolean,
        onProgress: ((percent: Int) -> Unit)?,
    ): DownloadOutcome =
        withContext(Dispatchers.IO) {
            LokiLogger.i(
                TAG,
                "requested '${request.title}' via ${AppSettings.downloadSource.value}, " +
                    "folder=${DownloadFolder.isConfigured}"
            )
            if (!DownloadFolder.isConfigured) return@withContext DownloadOutcome.NoFolder
            // Metadata as well as uri, matching what the resolver does: the same recording sits in the
            // catalogue under more than one id, and keying on the uri alone let a relinked track fall
            // through to resolve(), which would then hand its own local document uri to OkHttp.
            val existingRow = Downloads.find(request.trackUri)
                ?: Downloads.findByMetadata(request.title, request.artist)
            existingRow?.let { existing ->
                // Only a definite "not there" re-downloads; an inconclusive check keeps the row.
                if (DownloadFolder.exists(existing.documentUri) != false) {
                    return@withContext DownloadOutcome.Done(existing)
                }
                // The row's own uri, not the requested one: on a metadata match they differ, and
                // removing the requested uri would delete nothing and leave the dead row forever.
                Downloads.remove(existing.trackUri)
            }

            val capBytes = AppSettings.downloadCapBytes()
            if (capBytes != null) {
                if (AppSettings.downloadCapPolicy.value == AppSettings.CAP_POLICY_EVICT_OLDEST) {
                    evictToFit(capBytes)
                } else if (Downloads.totalSizeBytes() >= capBytes) {
                    LokiLogger.i(TAG, "storage cap reached, refusing '${request.title}'")
                    DownloadNotifier.failed(context, request.title, "storage cap reached")
                    return@withContext DownloadOutcome.Failed("storage cap reached")
                }
            }

            val report: (Int) -> Unit = { percent ->
                onProgress?.invoke(percent)
                if (notify) DownloadNotifier.progress(context, request.title, percent)
            }
            fun finish(stored: DownloadedTrack?): DownloadOutcome = if (stored == null) {
                DownloadNotifier.failed(context, request.title, "could not write to the folder")
                DownloadOutcome.Failed("could not write into the download folder")
            } else {
                if (notify) DownloadNotifier.finished(context, request.title)
                DownloadOutcome.Done(stored)
            }

            val temp = File.createTempFile("download", null, context.cacheDir)
            try {
                // Two ways to save a track the user already played, and the order between them is a
                // quality decision rather than a coincidence of which was written first.
                //
                // The playback cache holds the encoded bytes ExoPlayer actually pulled, so it
                // remuxes out byte-identical. The capture is those same bytes decoded and re-encoded,
                // which is a generation of loss on top of an already lossy source. Whenever both
                // exist — any non-DRM stream played through — the cache is strictly better and also
                // cheaper, so it goes first. Widevine playback never fills the cache, which is
                // exactly the case the capture is there for.
                val cached = fromPlaybackCache(request, temp) ?: fromCapturedPcm(request, temp)
                if (cached != null) {
                    onProgress?.invoke(100)
                    return@withContext finish(store(request, cached, temp, tagsFor(request), context))
                }

                // Neither path could serve it. A local-only caller wanted the recording it already
                // had, so there is nothing equivalent to fall back to: fetching would hand back a
                // different upload of the song, which is not what was asked for.
                if (request.localOnly) {
                    LokiLogger.i(TAG, "nothing local to save for '${request.title}', not fetching")
                    return@withContext DownloadOutcome.Failed("the played audio was not available to save")
                }

                var info = when (val resolved = resolve(request)) {
                    is StreamResult.Success -> resolved.info
                    is StreamResult.Failure ->
                        viaYt1d(request) ?: return@withContext DownloadOutcome.Failed(resolved.message)
                }
                val attempt = runCatching { fetchWithFallbacks(request, info, temp, report) }
                    .getOrElse {
                        if (it is CancellationException) throw it
                        LokiLogger.e(TAG, "fetch failed for ${request.title}: ${it.message}")
                        DownloadNotifier.failed(context, request.title, it.message ?: "failed")
                        return@withContext DownloadOutcome.Failed(it.message ?: "fetch failed")
                    }
                info = attempt.info
                val fetched = attempt.bytes
                if (fetched <= 0L) {
                    DownloadNotifier.failed(context, request.title, "empty download")
                    return@withContext DownloadOutcome.Failed("empty download")
                }

                finish(store(request, info, temp, tagsFor(request), context))
            } finally {
                temp.delete()
            }
        }

    /**
     * The track re-encoded from the decoded PCM the audio chain captured while it played, or null
     * when this is not the captured track or it was not captured in full.
     *
     * Preferred over every other path because it is the only one that saves the recording the user
     * actually listened to. Spfy's stream is Widevine, so its encoded bytes can never be written
     * out; the decoded samples can. Everything else re-fetches a different upload of the same song.
     */
    private fun fromCapturedPcm(request: DownloadRequest, temp: File): StreamInfo? {
        val capture = request.capture
            ?: MusicPlaybackService.instance?.captureOf(request.trackUri, request.durationMs)
            ?: return null
        if (!PcmAacEncoder.encode(capture.pcm, capture.count, capture.sampleRate, capture.channels, temp)) {
            return null
        }
        LokiLogger.i(TAG, "'${request.title}' encoded from the decoded capture (${temp.length()} bytes)")
        return StreamInfo(url = "pcm-capture", provider = "Decoded audio", mimeType = "audio/mp4")
    }

    /**
     * The played-through bytes for this track, written into [temp], or null when they are not all
     * there. A track with a gap in it is never written out: a hole would produce a file that plays
     * up to the gap and then stops, which is worse than not saving it at all.
     *
     * Only non-DRM playback fills this cache, so the bytes are the same encoded stream a fresh
     * download would fetch and the result is byte-identical.
     */
    private fun fromPlaybackCache(request: DownloadRequest, temp: File): StreamInfo? {
        val key = cacheKeyFor(request)
        if (!PlaybackCache.isComplete(key)) return null
        val complete = runCatching {
            temp.outputStream().use { PlaybackCache.writeTo(key, it) }
        }.getOrDefault(false)
        if (!complete || temp.length() <= 0L) {
            temp.writeBytes(ByteArray(0))
            return null
        }
        LokiLogger.i(TAG, "'${request.title}' came from the playback cache (${temp.length()} bytes)")
        // The container is read off the bytes rather than remembered: nothing else about the stream
        // needs to survive, and it keeps the cache from having to store metadata alongside.
        return StreamInfo(
            url = "playback-cache",
            provider = "Playback cache",
            mimeType = when {
                magicIs(temp, 0x1A, 0x45, 0xDF, 0xA3) -> "audio/webm"
                magicIs(temp, 0x66, 0x4C, 0x61, 0x43) -> "audio/flac"
                else -> null
            },
        )
    }

    /**
     * Which cache entry holds this track. A local-only save wants the bytes that were just played, so
     * it looks under the source playback used; a fresh save wants the source the user picked to
     * download in, and finding nothing there is correct — it then fetches rather than writing a file
     * from a different source than the one it records.
     */
    private fun cacheKeyFor(request: DownloadRequest): String = PlaybackCache.keyFor(
        request.trackUri,
        if (request.localOnly) AppSettings.preferredAudioSource.value else AppSettings.downloadSource.value,
    )

    /** Container magic: EBML for WebM/Matroska, "fLaC" for FLAC. */
    private fun magicIs(file: File, vararg bytes: Int): Boolean = runCatching {
        file.inputStream().use { input ->
            val head = ByteArray(bytes.size)
            input.read(head) == bytes.size && bytes.withIndex().all { (i, b) -> head[i] == b.toByte() }
        }
    }.getOrDefault(false)

    /**
     * The last rung. googlevideo refused us, so ask yt1d for the same recording. Only for a YouTube
     * download: a Qobuz or Deezer request wants that source's master, not a YouTube upload of it.
     */
    private class Attempt(val bytes: Long, val info: StreamInfo)

    /**
     * Fetches [initial], and when the media url is refused works down the rungs that remain: a fresh
     * visitor id first, then the fallback provider. Throws once none are left.
     */
    private suspend fun fetchWithFallbacks(
        request: DownloadRequest,
        initial: StreamInfo,
        temp: File,
        report: (Int) -> Unit,
    ): Attempt {
        var info = initial
        val bytes = runCatching { fetchTo(info, temp, report) }
            .recoverCatching { failure ->
                info = freshVisitorInfo(request, failure) ?: throw failure
                fetchTo(info, temp, report)
            }
            .recoverCatching { failure ->
                info = fallbackInfo(request, failure) ?: throw failure
                fetchTo(info, temp, report)
            }
            .getOrThrow()
        return Attempt(bytes, info)
    }

    /**
     * A re-resolve behind a fresh visitor id, or null when this failure is not one that stands to
     * benefit — a refused media url usually means the cached id went stale.
     */
    private suspend fun freshVisitorInfo(request: DownloadRequest, failure: Throwable): StreamInfo? {
        if (failure is CancellationException) throw failure
        if (failure.message?.contains("HTTP 403") != true) return null
        LokiLogger.w(TAG, "403 for '${request.title}', retrying with a fresh visitor id")
        YouTubeMusicSource.invalidateVisitorData()
        return (resolve(request) as? StreamResult.Success)?.info
    }

    /** The last rung, once the direct route is out of moves. */
    private suspend fun fallbackInfo(request: DownloadRequest, failure: Throwable): StreamInfo? {
        if (failure is CancellationException) throw failure
        return viaYt1d(request)
    }

    private suspend fun viaYt1d(request: DownloadRequest): StreamInfo? {
        if (AppSettings.downloadSource.value != AppSettings.SOURCE_YTM) return null
        val videoId = YouTubeMusicSource.findVideoId(
            title = request.title,
            artist = request.artist,
            region = AppSettings.effectiveRegion(),
            durationMs = request.durationMs,
        ) ?: return null
        val url = Yt1dSource.audioUrl(videoId) ?: return null
        LokiLogger.i(TAG, "yt1d fallback for '${request.title}' ($videoId)")
        return StreamInfo(url = url, provider = "yt1d", mimeType = "audio/mp4", headers = emptyMap())
    }

    private suspend fun resolve(request: DownloadRequest): StreamResult = AudioSourceResolver.byQuery(
        trackUri = request.trackUri,
        searchQuery = listOf(request.artist, request.title).filter { it.isNotBlank() }.joinToString(" "),
        title = request.title,
        artist = request.artist,
        durationMs = request.durationMs,
        source = AppSettings.downloadSource.value,
    )

    /**
     * Writes the fetched audio into the download folder, rehousing Opus from WebM into Ogg on the
     * way so the file is taggable and widely playable. The packets are copied, never re-encoded.
     */
    private fun store(
        request: DownloadRequest,
        info: StreamInfo,
        temp: File,
        tags: TrackTags,
        context: Context,
    ): DownloadedTrack? {
        val isOpusWebm = info.mimeType?.let { it.contains("webm") || it.contains("opus") } == true

        // Remux into its own file first: a failure partway through would otherwise leave a truncated
        // Ogg that the fallback copy would then append to.
        val remuxTemp = if (isOpusWebm) File.createTempFile("ogg", null, context.cacheDir) else null
        val remuxed = remuxTemp != null && temp.inputStream().use { source ->
            remuxTemp.outputStream().use { sink ->
                OpusRemuxer.remux(source, sink, tags, serial = request.trackUri.hashCode())
            }
        }
        if (isOpusWebm && !remuxed) {
            LokiLogger.w(TAG, "remux unavailable, keeping the original container for '${request.title}'")
        }

        var payload = if (remuxed) remuxTemp!! else temp
        val finalExtension = when {
            remuxed -> "opus"
            isOpusWebm -> "webm"
            else -> extensionFor(info.mimeType)
        }

        // FLAC and MP4 keep their own containers, so tagging is a metadata rewrite rather than a
        // remux. Same temp-file dance: a half-written file must never reach the folder, and a
        // tagger that reports failure leaves the untagged payload in place.
        val taggedTemp = when (finalExtension) {
            "flac" -> tagInto("flac", context, payload) { source, sink -> FlacTagger.tag(source, sink, tags) }
            "m4a" -> tagInto("m4a", context, payload) { source, sink -> Mp4Tagger.tag(source, sink, tags) }
            else -> null
        }
        if (taggedTemp != null) payload = taggedTemp

        try {
            val name = DownloadFolder.fileName(request.title, request.artist, finalExtension)
            val target = DownloadFolder.createFile(name, mimeTypeFor(finalExtension)) ?: return null
            val sink = DownloadFolder.openOutput(target) ?: run {
                LokiLogger.e(TAG, "could not open $target for writing")
                DownloadFolder.delete(target.toString())
                return null
            }
            // A copy that dies partway leaves a truncated file in the user's folder with no index row,
            // which nothing can then find or clean up: prune only drops rows whose file is gone, never
            // files with no row. Delete it and report the same "could not write" outcome.
            runCatching {
                sink.use { payload.inputStream().use { source -> source.copyTo(it, BUFFER) } }
            }.getOrElse {
                LokiLogger.e(TAG, "writing '${request.title}' into the folder failed: ${it.message}")
                DownloadFolder.delete(target.toString())
                if (it is CancellationException) throw it
                return null
            }
            return record(request, info, target.toString(), finalExtension, payload.length())
        } finally {
            remuxTemp?.delete()
            taggedTemp?.delete()
        }
    }

    /** Runs a tagger into its own temp file, or null when it declines the input. */
    private fun tagInto(
        prefix: String,
        context: Context,
        payload: File,
        write: (java.io.InputStream, java.io.OutputStream) -> Boolean,
    ): File? {
        val candidate = File.createTempFile(prefix, null, context.cacheDir)
        val tagged = runCatching {
            payload.inputStream().use { source ->
                candidate.outputStream().use { sink -> write(source, sink) }
            }
        }.getOrDefault(false)
        // Deleted here rather than by the caller's finally: that only ever sees a non-null result, so
        // a declining tagger (Mp4Tagger turns down every faststart mp4) used to leak one temp per go.
        if (!tagged) candidate.delete()
        return candidate.takeIf { tagged }
    }

    private fun record(
        request: DownloadRequest,
        info: StreamInfo,
        documentUri: String,
        finalExtension: String,
        written: Long,
    ): DownloadedTrack {
        val record = DownloadedTrack(
            trackUri = request.trackUri,
            documentUri = documentUri,
            source = AppSettings.downloadSource.value,
            provider = info.provider,
            mimeType = mimeTypeFor(finalExtension),
            coverUrl = request.coverUrl,
            contextUri = request.contextUri,
            contextName = request.contextName,
            contextType = request.contextType,
            sizeBytes = written,
            title = request.title,
            artist = request.artist,
            downloadedAt = System.currentTimeMillis(),
        )
        Downloads.put(record)
        LokiLogger.i(TAG, "downloaded '${request.title}' as .$finalExtension from ${info.provider}")
        return record
    }

    private fun fetchTo(info: StreamInfo, target: File, onProgress: (Int) -> Unit): Long {
        val key = info.decryptionKey
        if (key != null) {
            // Deezer's cipher needs one contiguous stream from block zero, and its relay does not
            // throttle, so it keeps the single request.
            onProgress(-1)
            open(info, range = null).use { body ->
                target.outputStream().use { sink ->
                    DeezerBlockCipher.decryptInto(sink, body.byteStream(), DeezerBlockCipher.hexToBytes(key))
                }
            }
            return target.length()
        }
        fetchChunked(info, target, onProgress)
        return target.length()
    }

    /**
     * Downloads in [CHUNK_BYTES] ranges rather than one open-ended request.
     *
     * googlevideo throttles a whole-file GET to roughly playback speed, so a three minute track took
     * about three minutes. Asking for explicit ranges sidesteps that and the same track lands in
     * seconds. Ranges are sequential, so the file is written in order and nothing needs reassembling.
     */
    private fun fetchChunked(info: StreamInfo, target: File, onProgress: (Int) -> Unit) {
        var position = 0L
        var total = -1L
        var lastPercent = -1
        val buffer = ByteArray(BUFFER)
        target.outputStream().use { sink ->
            while (total < 0 || position < total) {
                val end = position + CHUNK_BYTES - 1
                var chunkBytes = 0L
                open(info, range = "bytes=$position-$end").use { body ->
                    if (total < 0) total = contentRangeTotal(body.contentRange) ?: body.contentLength()
                    val stream = body.byteStream()
                    // Reported per buffer rather than per chunk, or a six megabyte track would move
                    // the bar exactly twice.
                    while (true) {
                        val read = stream.read(buffer)
                        if (read < 0) break
                        sink.write(buffer, 0, read)
                        position += read
                        chunkBytes += read
                        val percent = if (total > 0) ((position * 100) / total).toInt() else -1
                        if (percent != lastPercent) {
                            lastPercent = percent
                            onProgress(percent.coerceAtMost(100))
                        }
                    }
                }
                // The loop normally ends because position reached total. An empty chunk ends it too:
                // position would not have moved, so re-asking for the same range is a spin with no
                // progress and nothing logged. A merely short chunk is fine and keeps going — CDNs cap
                // how much of a range they serve at once.
                if (total < 0 || chunkBytes == 0L) break
            }
        }
    }

    private class Body(private val response: okhttp3.Response) : AutoCloseable {
        val contentRange: String? = response.header("Content-Range")
        fun contentLength(): Long = response.body?.contentLength() ?: -1
        fun byteStream(): java.io.InputStream = response.body?.byteStream() ?: error("empty body")
        override fun close() = response.close()
    }

    private fun open(info: StreamInfo, range: String?): Body {
        val request = Request.Builder().url(info.url).apply {
            info.headers.forEach { (k, v) -> header(k, v) }
            if (range != null) header("Range", range)
        }.build()
        val response = http.newCall(request).execute()
        if (!response.isSuccessful) {
            val code = response.code
            response.close()
            error("HTTP $code from ${request.url.host} range=${range ?: "none"}")
        }
        return Body(response)
    }

    /** "bytes 0-1048575/3277373" -> 3277373. */
    private fun contentRangeTotal(header: String?): Long? =
        header?.substringAfter('/', "")?.toLongOrNull()

    private fun tagsFor(request: DownloadRequest) = TrackTags(
        title = request.title,
        artist = request.artist,
        album = request.album,
        cover = request.coverUrl?.let { fetchCover(it) },
    )

    /**
     * Album art is the same image for every track on a release, so an album would otherwise fetch it
     * once per track. Bounded because the only thing worth remembering is the release being
     * downloaded right now.
     */
    private val coverCache = object : LinkedHashMap<String, TrackTags.Cover>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TrackTags.Cover>) = size > 4
    }

    private fun fetchCover(url: String): TrackTags.Cover? {
        synchronized(coverCache) { coverCache[url] }?.let { return it }
        val fetched = downloadCover(url) ?: return null
        synchronized(coverCache) { coverCache[url] = fetched }
        return fetched
    }

    private fun downloadCover(url: String): TrackTags.Cover? = runCatching {
        http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body ?: return null
            val mime = response.header("Content-Type") ?: "image/jpeg"
            TrackTags.Cover(body.bytes(), mime.substringBefore(';'))
        }
    }.getOrNull()

    /**
     * Whether saving [trackUri] would need the decoded capture, i.e. the playback cache cannot
     * already serve it. Callers ask before claiming a capture, so a track the cache covers does not
     * cost a buffer hand-over for audio that would lose to it anyway — see the ordering in
     * [downloadInner], which this must agree with.
     */
    fun needsCapture(trackUri: String): Boolean =
        !PlaybackCache.isComplete(
            PlaybackCache.keyFor(trackUri, AppSettings.preferredAudioSource.value)
        )

    /** Removes the local copy and its index row. */
    fun delete(trackUri: String) {
        Downloads.find(trackUri)?.let { DownloadFolder.delete(it.documentUri) }
        Downloads.remove(trackUri)
    }

    /**
     * Deletes oldest-added downloads until the total is back under [capBytes]. Run before a new
     * download starts, not after: the new file's own size isn't known until it's fetched, so this
     * only guarantees room for what's already there — a download that itself exceeds the cap pushes
     * back over it, which the next call corrects.
     */
    private fun evictToFit(capBytes: Long) {
        var total = Downloads.totalSizeBytes()
        if (total <= capBytes) return
        for (track in Downloads.rows.value.sortedBy { it.downloadedAt }) {
            if (total <= capBytes) break
            delete(track.trackUri)
            total -= track.sizeBytes
            LokiLogger.i(TAG, "evicted '${track.title}' to stay under the storage cap")
        }
    }

    private fun extensionFor(mimeType: String?): String = when {
        mimeType == null -> "m4a"
        mimeType.contains("flac") -> "flac"
        mimeType.contains("mpeg") -> "mp3"
        mimeType.contains("mp4") || mimeType.contains("m4a") || mimeType.contains("aac") -> "m4a"
        else -> "m4a"
    }

    private fun mimeTypeFor(extension: String): String = when (extension) {
        "flac" -> "audio/flac"
        "mp3" -> "audio/mpeg"
        "opus" -> "audio/ogg"
        "webm" -> "audio/webm"
        else -> "audio/mp4"
    }
}
