package ch.snepilatch.app.playback.engine

import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.DrmInitData
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.drm.DrmSession
import androidx.media3.exoplayer.drm.DrmSessionEventListener
import androidx.media3.exoplayer.drm.DrmSessionManager

/**
 * Many Spotify audio files are cenc-encrypted but carry **no Widevine `pssh`** box (their in-file
 * pssh is for a different DRM system). ExoPlayer then throws `MissingSchemeDataException` and the
 * track plays silent. The web player handles this by fetching the Widevine PSSH from the seektable
 * and feeding it to EME. We do the same: this wrapper rewrites every [Format]'s [DrmInitData] to the
 * seektable Widevine PSSH before delegating to a real [DefaultDrmSessionManager], so a license is
 * always requested with the correct init data regardless of what the file embeds.
 */
@UnstableApi
class PsshInjectingDrmSessionManager(
    private val delegate: DrmSessionManager,
    psshBox: ByteArray
) : DrmSessionManager {

    private val widevineInitData = DrmInitData(
        DrmInitData.SchemeData(C.WIDEVINE_UUID, "video/mp4", psshBox)
    )

    private fun inject(format: Format): Format =
        format.buildUpon().setDrmInitData(widevineInitData).build()

    override fun setPlayer(playbackLooper: Looper, playerId: PlayerId) =
        delegate.setPlayer(playbackLooper, playerId)

    override fun prepare() = delegate.prepare()

    override fun release() = delegate.release()

    override fun acquireSession(
        eventDispatcher: DrmSessionEventListener.EventDispatcher?,
        format: Format
    ): DrmSession? = delegate.acquireSession(eventDispatcher, inject(format))

    override fun preacquireSession(
        eventDispatcher: DrmSessionEventListener.EventDispatcher?,
        format: Format
    ): DrmSessionManager.DrmSessionReference =
        delegate.preacquireSession(eventDispatcher, inject(format))

    override fun getCryptoType(format: Format): Int = delegate.getCryptoType(inject(format))
}
