package ch.snepilatch.app.logic.download

import android.content.Context
import android.net.Uri
import ch.snepilatch.app.logic.shared.LokiLogger
import ch.snepilatch.app.logic.shared.SessionHolder
import kotify.api.canvas.Canvas
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * Canvas clips kept with downloaded tracks, in app storage rather than the music folder, so a downloaded
 * song shows its canvas without fetching it again, offline included (#565).
 */
object CanvasStore {

    private const val TAG = "CanvasStore"
    private const val TRACK_PREFIX = "spotify:track:"

    private var dir: File? = null

    fun init(context: Context) {
        if (dir == null) dir = File(context.applicationContext.filesDir, "canvas")
    }

    private fun fileFor(trackUri: String): File? =
        dir?.takeIf { trackUri.startsWith(TRACK_PREFIX) }?.let { File(it, trackUri.removePrefix(TRACK_PREFIX) + ".mp4") }

    /** The kept clip as a playable uri, or null when this track has none on the phone. */
    fun localUri(trackUri: String): String? = fileFor(trackUri)?.takeIf { it.isFile }?.let { Uri.fromFile(it).toString() }

    /** Fetches and keeps [trackUri]'s canvas. A track without one, or any failure, keeps nothing. */
    suspend fun save(trackUri: String, http: OkHttpClient) {
        val target = fileFor(trackUri)?.takeUnless { it.isFile } ?: return
        val sess = SessionHolder.session ?: return
        try {
            val url = Canvas(sess).getCanvas(trackUri.removePrefix(TRACK_PREFIX))?.url ?: return
            http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                val body = response.body?.takeIf { response.isSuccessful } ?: return
                target.parentFile?.mkdirs()
                // Written aside and renamed, so a cut-off fetch never leaves a clip that plays half.
                val part = File(target.path + ".part")
                body.byteStream().use { input -> part.outputStream().use { input.copyTo(it) } }
                part.renameTo(target)
            }
            LokiLogger.i(TAG, "kept the canvas for $trackUri (${target.length() / 1024} KB)")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LokiLogger.d(TAG, "no canvas kept for $trackUri: ${e.message}")
        }
    }

    fun delete(trackUri: String) {
        fileFor(trackUri)?.delete()
    }
}
