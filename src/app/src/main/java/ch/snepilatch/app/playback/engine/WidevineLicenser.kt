package ch.snepilatch.app.playback.engine

import android.media.MediaDrm
import android.util.Base64
import androidx.media3.common.C
import kotify.session.Session

/**
 * One Widevine license exchange without a player: what the web player does for every track it
 * loads. Used when a downloaded copy plays, so the server sees the same resolve and license as for
 * a stream. The request goes through KotifyClient's browser-shaped HTTP client like every other
 * Spfy call. Returns the exchange time in ms.
 */
object WidevineLicenser {

    suspend fun license(session: Session, licenseUrl: String, psshBase64: String): Long {
        val t0 = System.currentTimeMillis()
        val drm = MediaDrm(C.WIDEVINE_UUID)
        val drmSession = drm.openSession()
        try {
            val request = drm.getKeyRequest(
                drmSession, Base64.decode(psshBase64, Base64.DEFAULT), "audio/mp4", MediaDrm.KEY_TYPE_STREAMING, null
            )
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
