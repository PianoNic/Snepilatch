package ch.snepilatch.app.util

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class UpdateInfo(
    val currentVersion: String,
    val latestVersion: String,
    val downloadUrl: String,
    val releaseNotes: String,
    val htmlUrl: String,
    val isPrerelease: Boolean
)

/**
 * [STABLE] only surfaces full GitHub releases. [NIGHTLY] also surfaces prereleases (nightly
 * builds tagged `vX.Y.Z-nightly.N`), which are unreviewed and opt-in at the user's own risk —
 * see [ch.snepilatch.app.viewmodel.AppSettings.updateChannel].
 */
enum class UpdateChannel { STABLE, NIGHTLY }

object UpdateService {

    private const val TAG = "UpdateService"
    // The list endpoint (not /releases/latest, which never returns a prerelease) so the
    // nightly channel can see prereleases too. GitHub returns it newest-first.
    private const val GITHUB_API_URL =
        "https://api.github.com/repos/PianoNic/Snepilatch/releases"
    private const val DISMISSED_KEY = "dismissed_update_version"

    internal val client = OkHttpClient()

    suspend fun checkForUpdates(
        context: Context,
        channel: UpdateChannel = UpdateChannel.STABLE
    ): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val currentVersion = context.packageManager
                .getPackageInfo(context.packageName, 0).versionName ?: return@withContext null

            val request = Request.Builder()
                .url(GITHUB_API_URL)
                .header("Accept", "application/vnd.github+json")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext null

            val releases = JSONArray(response.body?.string() ?: return@withContext null)
            var json: JSONObject? = null
            for (i in 0 until releases.length()) {
                val release = releases.getJSONObject(i)
                if (release.optBoolean("draft", false)) continue
                if (channel == UpdateChannel.STABLE && release.optBoolean("prerelease", false)) continue
                json = release
                break
            }
            if (json == null) return@withContext null

            val latestVersion = json.optString("tag_name", "").removePrefix("v")

            if (!isNewerVersion(currentVersion, latestVersion)) return@withContext null

            // Find APK asset
            val assets = json.optJSONArray("assets") ?: return@withContext null
            var downloadUrl: String? = null
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                if (asset.getString("name").endsWith(".apk")) {
                    downloadUrl = asset.getString("browser_download_url")
                    break
                }
            }

            if (downloadUrl == null) return@withContext null

            UpdateInfo(
                currentVersion = currentVersion,
                latestVersion = latestVersion,
                downloadUrl = downloadUrl,
                releaseNotes = json.optString("body", ""),
                htmlUrl = json.optString("html_url", ""),
                isPrerelease = json.optBoolean("prerelease", false)
            )
        } catch (e: Exception) {
            Log.w(TAG, "Update check failed: ${e.message}")
            null
        }
    }

    /** A version's dot-separated numeric core, plus its nightly ordinal if it's a `-nightly.N` build. */
    private data class ParsedVersion(val core: List<Int>, val nightly: Int?)

    private fun parseVersion(version: String): ParsedVersion {
        val core = version.substringBefore("-")
            .replace(Regex("[^0-9.]"), "")
            .split(".")
            .map { it.toIntOrNull() ?: 0 }
        val nightly = if (version.contains("-nightly.")) {
            version.substringAfterLast("-nightly.").toIntOrNull()
        } else {
            null
        }
        return ParsedVersion(core, nightly)
    }

    /**
     * Semver-correct ordering: a nightly build ranks below the full release of the same core
     * version (e.g. 2.9.80-nightly.3 < 2.9.80), and among nightlies of the same core version the
     * higher ordinal wins. A naive positional-parts comparison would get this backwards, since a
     * longer dot-list otherwise reads as "more precise" rather than "prerelease of".
     */
    private fun isNewerVersion(current: String, latest: String): Boolean {
        try {
            val c = parseVersion(current)
            val l = parseVersion(latest)

            for (i in 0 until maxOf(c.core.size, l.core.size)) {
                val cv = c.core.getOrElse(i) { 0 }
                val lv = l.core.getOrElse(i) { 0 }
                if (lv != cv) return lv > cv
            }

            return when {
                c.nightly == null -> false
                l.nightly == null -> true
                else -> l.nightly > c.nightly
            }
        } catch (e: Exception) {
            Log.w(TAG, "Version comparison failed: $e")
        }
        return false
    }

    suspend fun downloadApk(
        context: Context,
        url: String,
        onProgress: (Float) -> Unit
    ): File? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/octet-stream")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext null

            val body = response.body ?: return@withContext null
            val totalBytes = body.contentLength()
            val file = File(context.cacheDir, "snepilatch-update.apk")

            file.outputStream().use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Long = 0
                    var read: Int
                    var lastPct = -1
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        bytesRead += read
                        if (totalBytes > 0) {
                            // Emit only when the whole-percent changes, collapsing thousands of
                            // per-8KB progress writes/recompositions to ~100. The final iteration
                            // (bytesRead == totalBytes -> pct 100) still fires the terminal 1.0.
                            val pct = (bytesRead * 100 / totalBytes).toInt()
                            if (pct != lastPct) {
                                lastPct = pct
                                onProgress(bytesRead.toFloat() / totalBytes)
                            }
                        }
                    }
                }
            }
            file
        } catch (e: Exception) {
            Log.e(TAG, "Download failed: ${e.message}")
            null
        }
    }

    fun installApk(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        context.startActivity(intent)
    }

    fun getDismissedVersion(context: Context): String? {
        return context.getSharedPreferences("updates", Context.MODE_PRIVATE)
            .getString(DISMISSED_KEY, null)
    }

    fun dismissVersion(context: Context, version: String) {
        context.getSharedPreferences("updates", Context.MODE_PRIVATE)
            .edit().putString(DISMISSED_KEY, version).apply()
    }
}
