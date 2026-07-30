package ch.snepilatch.app.download

import android.content.Context
import ch.snepilatch.app.playback.AudioSourceResolver
import ch.snepilatch.app.playback.DeezerBlockCipher
import ch.snepilatch.app.util.LokiLogger
import ch.snepilatch.app.viewmodel.AppSettings
import kotify.cdn.StreamInfo
import kotify.cdn.StreamResult
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

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    suspend fun download(request: DownloadRequest, context: Context): DownloadOutcome =
        withContext(Dispatchers.IO) {
            if (!DownloadFolder.isConfigured) return@withContext DownloadOutcome.NoFolder
            Downloads.find(request.trackUri)?.let { existing ->
                if (DownloadFolder.exists(existing.documentUri)) {
                    return@withContext DownloadOutcome.Done(existing)
                }
                Downloads.remove(request.trackUri)
            }

            val info = when (val resolved = resolve(request)) {
                is StreamResult.Success -> resolved.info
                is StreamResult.Failure -> return@withContext DownloadOutcome.Failed(resolved.message)
            }

            val temp = File.createTempFile("dl", null, context.cacheDir)
            try {
                val fetched = runCatching {
                    fetchTo(info, temp) { DownloadNotifier.progress(context, request.title, it) }
                }.getOrElse {
                    LokiLogger.e(TAG, "fetch failed for ${request.title}: ${it.message}")
                    DownloadNotifier.failed(context, request.title, it.message ?: "failed")
                    return@withContext DownloadOutcome.Failed(it.message ?: "fetch failed")
                }
                if (fetched <= 0L) {
                    DownloadNotifier.failed(context, request.title, "empty download")
                    return@withContext DownloadOutcome.Failed("empty download")
                }

                val tags = tagsFor(request)
                val stored = store(request, info, temp, tags, context)
                if (stored == null) {
                    DownloadNotifier.failed(context, request.title, "could not write to the folder")
                    DownloadOutcome.Failed("could not write into the download folder")
                } else {
                    DownloadNotifier.finished(context, request.title)
                    DownloadOutcome.Done(stored)
                }
            } finally {
                temp.delete()
            }
        }

    private suspend fun resolve(request: DownloadRequest): StreamResult = AudioSourceResolver.byQuery(
        trackUri = request.trackUri,
        searchQuery = listOf(request.artist, request.title).filter { it.isNotBlank() }.joinToString(" "),
        title = request.title,
        artist = request.artist,
        durationMs = request.durationMs,
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

        // FLAC keeps its own container, so tagging is a metadata rewrite rather than a remux. Same
        // temp-file dance: a half-written file must never reach the folder.
        val taggedTemp = if (finalExtension == "flac") {
            File.createTempFile("flac", null, context.cacheDir).takeIf { candidate ->
                payload.inputStream().use { source ->
                    candidate.outputStream().use { sink -> FlacTagger.tag(source, sink, tags) }
                }
            }
        } else {
            null
        }
        if (taggedTemp != null) payload = taggedTemp

        try {
            val name = DownloadFolder.fileName(request.title, request.artist, finalExtension)
            val target = DownloadFolder.createFile(name, mimeTypeFor(finalExtension)) ?: return null
            val sink = DownloadFolder.openOutput(target) ?: run {
                DownloadFolder.delete(target.toString())
                return null
            }
            sink.use { payload.inputStream().use { source -> source.copyTo(it, BUFFER) } }
            return record(request, info, target.toString(), finalExtension, payload.length())
        } finally {
            remuxTemp?.delete()
            taggedTemp?.delete()
        }
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
            source = AppSettings.preferredAudioSource.value.orEmpty(),
            provider = info.provider,
            mimeType = mimeTypeFor(finalExtension),
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
        val request = Request.Builder().url(info.url).apply {
            info.headers.forEach { (k, v) -> header(k, v) }
        }.build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            val body = response.body ?: error("empty body")
            val total = body.contentLength()
            target.outputStream().use { sink ->
                val key = info.decryptionKey
                if (key != null) {
                    // Deezer decrypts block by block, so progress is reported once it lands.
                    onProgress(-1)
                    DeezerBlockCipher.decryptInto(sink, body.byteStream(), DeezerBlockCipher.hexToBytes(key))
                } else {
                    body.byteStream().copyReporting(sink, total, onProgress)
                }
            }
        }
        return target.length()
    }

    /** Copies while reporting percent complete, throttled so the notification is not spammed. */
    private fun java.io.InputStream.copyReporting(
        out: java.io.OutputStream,
        total: Long,
        onProgress: (Int) -> Unit,
    ): Long {
        val buffer = ByteArray(BUFFER)
        var copied = 0L
        var lastPercent = -1
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
            copied += read
            val percent = if (total > 0) ((copied * 100) / total).toInt() else -1
            if (percent != lastPercent) {
                lastPercent = percent
                onProgress(percent)
            }
        }
        return copied
    }

    private fun tagsFor(request: DownloadRequest) = TrackTags(
        title = request.title,
        artist = request.artist,
        album = request.album,
        cover = request.coverUrl?.let { fetchCover(it) },
    )

    private fun fetchCover(url: String): TrackTags.Cover? = runCatching {
        http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body ?: return null
            val mime = response.header("Content-Type") ?: "image/jpeg"
            TrackTags.Cover(body.bytes(), mime.substringBefore(';'))
        }
    }.getOrNull()

    /** Removes the local copy and its index row. */
    fun delete(trackUri: String) {
        Downloads.find(trackUri)?.let { DownloadFolder.delete(it.documentUri) }
        Downloads.remove(trackUri)
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
