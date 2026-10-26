package ch.snepilatch.app.logic.playback.engine

import android.util.Base64
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.DrmInitData
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.drm.ExoMediaDrm
import kotify.cdn.SpfyPlayback

/**
 * One Widevine license exchange without a player: what the web player does for every track it
 * loads. Used when a downloaded copy plays, so the server sees the same resolve and license as for
 * a stream. The certificate and license go through Kotify like the player's own. Returns the
 * exchange time in ms.
 *
 * Goes through the same FrameworkMediaDrm the player licenses with, so the request is built and the
 * MediaDrm freed the way media3 does it for playback, on every API level minSdk allows.
 */
object WidevineLicenser {

    @OptIn(UnstableApi::class)
    suspend fun license(playback: SpfyPlayback, psshBase64: String): Long {
        val t0 = System.currentTimeMillis()
        val certificate = runCatching { playback.getWidevineCertificate() }.getOrNull()
        val drm = widevineWithCertificate(C.WIDEVINE_UUID, certificate)
        val drmSession = drm.openSession()
        try {
            val scheme = DrmInitData.SchemeData(C.WIDEVINE_UUID, MimeTypes.AUDIO_MP4, Base64.decode(psshBase64, Base64.DEFAULT))
            val request = drm.getKeyRequest(drmSession, listOf(scheme), ExoMediaDrm.KEY_TYPE_STREAMING, null)
            drm.provideKeyResponse(drmSession, playback.getWidevineLicense(request.data))
        } finally {
            drm.closeSession(drmSession)
            drm.release()
        }
        return System.currentTimeMillis() - t0
    }
}
