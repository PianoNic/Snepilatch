package ch.snepilatch.app.viewmodel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import ch.snepilatch.app.logic.shared.SessionHolder
import ch.snepilatch.app.logic.shared.SessionViewModel
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotify.api.user.User

/**
 * Edits the signed-in profile (#840): the picture is read from the picker's uri, brought down to
 * [MAX_EDGE_PX] on its longer side and re-encoded as JPEG, then uploaded through Kotify the way
 * the web player does; removing it leaves the default avatar. [busy] is true while a call runs.
 * [onDone] runs on the main thread after a successful call, for the account to be re-read.
 */
class ProfileViewModel : SessionViewModel("Profile") {

    val busy = MutableStateFlow(false)

    fun setPicture(context: Context, uri: Uri, onDone: () -> Unit) = launchWithSessionLoading("setPicture", busy) { sess ->
        val jpeg = readAsJpeg(context, uri)
        User(sess).setProfileImage(SessionHolder.username, jpeg, "image/jpeg")
        withContext(Dispatchers.Main) { onDone() }
    }

    fun removePicture(onDone: () -> Unit) = launchWithSessionLoading("removePicture", busy) { sess ->
        User(sess).deleteProfileImage(SessionHolder.username)
        withContext(Dispatchers.Main) { onDone() }
    }

    /** The picked image as a JPEG no larger than [MAX_EDGE_PX] on a side; the upload wants at least 300 and at most 10 MB. */
    private fun readAsJpeg(context: Context, uri: Uri): ByteArray {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_EDGE_PX) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: error("Could not decode the picked image")
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }.toByteArray()
    }

    private companion object {
        const val MAX_EDGE_PX = 1024
        const val JPEG_QUALITY = 90
    }
}
