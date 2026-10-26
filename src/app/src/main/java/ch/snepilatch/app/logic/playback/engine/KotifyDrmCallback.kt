package ch.snepilatch.app.logic.playback.engine

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.drm.ExoMediaDrm
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.HttpMediaDrmCallback
import androidx.media3.exoplayer.drm.MediaDrmCallback
import ch.snepilatch.app.logic.shared.LokiLogger
import kotify.cdn.SpfyPlayback
import kotlinx.coroutines.runBlocking
import java.util.UUID

/**
 * ExoPlayer's license request sent through Kotify, so it leaves with the same browser-shaped client,
 * headers and retry rules as the web player's, instead of ExoPlayer's own HTTP stack. Provisioning
 * still goes to Google's server, as for any Android CDM.
 */
@OptIn(UnstableApi::class)
class KotifyDrmCallback(private val playback: SpfyPlayback) : MediaDrmCallback {
    private val provisioning = HttpMediaDrmCallback(null, DefaultHttpDataSource.Factory())

    override fun executeProvisionRequest(uuid: UUID, request: ExoMediaDrm.ProvisionRequest): MediaDrmCallback.Response =
        provisioning.executeProvisionRequest(uuid, request)

    // ExoPlayer calls this on its own DRM thread and waits for the answer, as its HTTP callback does.
    override fun executeKeyRequest(uuid: UUID, request: ExoMediaDrm.KeyRequest): MediaDrmCallback.Response =
        MediaDrmCallback.Response(runBlocking { playback.getWidevineLicense(request.data) })
}

/**
 * A Widevine CDM with the service certificate set, which the web player sets before every license.
 * Set the way Chrome's Android MediaDrmBridge sets it: privacy mode on, or the plugin ignores the
 * certificate and the challenge carries the client id in the clear.
 */
@OptIn(UnstableApi::class)
fun widevineWithCertificate(uuid: UUID, certificate: ByteArray?): FrameworkMediaDrm =
    FrameworkMediaDrm.newInstance(uuid).also { drm ->
        if (certificate == null) return@also
        // Not fatal, as for the web player, which licenses without it when setting it fails.
        runCatching {
            drm.setPropertyString("privacyMode", "enable")
            drm.setPropertyByteArray("serviceCertificate", certificate)
        }
            .onSuccess { LokiLogger.i("KotifyDrm", "Service certificate set (${certificate.size} bytes)") }
            .onFailure { LokiLogger.w("KotifyDrm", "Service certificate not set: ${it.message}") }
    }
