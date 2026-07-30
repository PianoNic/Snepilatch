package ch.snepilatch.app.download

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import ch.snepilatch.app.util.LokiLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.OutputStream

/**
 * The folder the user picked for downloads, and file creation inside it.
 *
 * A SAF tree rather than app storage, because the files are meant to be the user's: they can browse
 * them, copy them off, and delete them. Deleting from underneath us is expected, which is what
 * [Downloads.prune] is for.
 */
object DownloadFolder {

    private const val TAG = "DownloadFolder"
    private const val PREF_KEY = "download_folder_uri"

    private var appContext: Context? = null

    private val _folder = MutableStateFlow<Uri?>(null)
    val folder: StateFlow<Uri?> = _folder.asStateFlow()

    /** True once a folder is picked; downloading is blocked until then. */
    val isConfigured: Boolean get() = _folder.value != null

    fun load(context: Context) {
        appContext = context.applicationContext
        val saved = prefs(context).getString(PREF_KEY, null) ?: return
        val uri = Uri.parse(saved)
        // The grant is lost if the user clears it in system settings or the volume is gone.
        val held = context.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isWritePermission
        }
        _folder.value = if (held) uri else null
        if (!held) LokiLogger.w(TAG, "lost permission for $uri, folder needs picking again")
    }

    /** Persists the tree the user picked and takes a lasting grant on it. */
    fun setFolder(uri: Uri, context: Context) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { context.contentResolver.takePersistableUriPermission(uri, flags) }
            .onFailure { LokiLogger.e(TAG, "could not persist permission: ${it.message}") }
        prefs(context).edit().putString(PREF_KEY, uri.toString()).apply()
        _folder.value = uri
        LokiLogger.i(TAG, "download folder set")
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(PREF_KEY).apply()
        _folder.value = null
    }

    /** Creates a file in the chosen folder, returning its document uri. */
    fun createFile(name: String, mimeType: String): Uri? {
        val ctx = appContext ?: return null
        val tree = _folder.value ?: return null
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        return runCatching {
            DocumentsContract.createDocument(ctx.contentResolver, parent, mimeType, name)
        }.getOrElse {
            LokiLogger.e(TAG, "createDocument failed for $name: ${it.message}")
            null
        }
    }

    fun openOutput(documentUri: Uri): OutputStream? {
        val ctx = appContext ?: return null
        return runCatching { ctx.contentResolver.openOutputStream(documentUri) }.getOrNull()
    }

    fun exists(documentUri: String): Boolean {
        val ctx = appContext ?: return false
        return runCatching {
            ctx.contentResolver.query(Uri.parse(documentUri), arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)
                .use { it != null && it.moveToFirst() }
        }.getOrDefault(false)
    }

    fun delete(documentUri: String): Boolean {
        val ctx = appContext ?: return false
        return runCatching {
            DocumentsContract.deleteDocument(ctx.contentResolver, Uri.parse(documentUri))
        }.getOrDefault(false)
    }

    /** Filesystem-safe "Artist - Title", since the name lands in a folder the user browses. */
    fun fileName(title: String, artist: String, extension: String): String {
        val base = listOf(artist, title).filter { it.isNotBlank() }.joinToString(" - ")
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .trim()
            .take(120)
        return if (base.isBlank()) "track.$extension" else "$base.$extension"
    }

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(ch.snepilatch.app.viewmodel.AppSettings.PREFS, Context.MODE_PRIVATE)
}
