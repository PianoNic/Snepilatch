package ch.snepilatch.app.logic.playback.engine

import android.util.Base64
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.DrmInitData
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.drm.ExoMediaDrm
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import kotify.session.Session

/**
 * One Widevine license exchange without a player: what the web player does for every track it
 * loads. Used when a downloaded copy plays, so the server sees the same resolve and license as for
 * a stream. The request goes through KotifyClient's browser-shaped HTTP client like every other
 * Spfy call. Returns the exchange time in ms.
 *
 * Goes through the same FrameworkMediaDrm the player licenses with, so the request is built and the
 * MediaDrm freed the way media3 does it for playback, on every API level minSdk allows.
 */
object WidevineLicenser {

    @OptIn(UnstableApi::class)
    suspend fun license(session: Session, licenseUrl: String, psshBase64: String): Long {
        val t0 = System.currentTimeMillis()
        val drm = FrameworkMediaDrm.newInstance(C.WIDEVINE_UUID)
        val drmSession = drm.openSession()
        try {
            val scheme = DrmInitData.SchemeData(C.WIDEVINE_UUID, MimeTypes.AUDIO_MP4, Base64.decode(psshBase64, Base64.DEFAULT))
            val request = drm.getKeyRequest(drmSession, listOf(scheme), ExoMediaDrm.KEY_TYPE_STREAMING, null)
            val response = session.getHttpClient()
                .postBinary(licenseUrl, request.data, mapOf("Content-Type" to "application/octet-stream"))
            drm.provideKeyResponse(drmSession, response)
        } finally {
            drm.closeSession(drmSession)
            drm.release()
        }
        return System.currentTimeMillis() - t0
    }
}
