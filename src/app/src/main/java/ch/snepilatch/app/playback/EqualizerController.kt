package ch.snepilatch.app.playback

import android.media.audiofx.DynamicsProcessing
import android.os.Build
import ch.snepilatch.app.util.LokiLogger

/**
 * Band layout and the input-gain math for the in-app EQ. Pure Kotlin so it unit-tests without a
 * device — [EqualizerController] holds everything that needs Android.
 */
object EqualizerHeadroom {

    val FREQUENCIES = floatArrayOf(31f, 62f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)
    val BANDS = FREQUENCIES.size
    const val MAX_GAIN_DB = 12f

    /**
     * The input gain that keeps the loudest boosted band at unity: `-(largest positive band gain)`.
     * A curve peaking at +9 dB gets −9 dB in, so the boost fills headroom instead of clipping. Curves
     * that only cut get 0 — cutting needs no headroom, and attenuating further would just lose level.
     */
    fun inputGainDb(bands: FloatArray): Float {
        val peak = (bands.maxOrNull() ?: 0f).coerceAtLeast(0f)
        // Guard the negation: -0f would render as "-0.0 dB" in the preamp readout.
        return if (peak == 0f) 0f else -peak
    }
}

/**
 * 10-band EQ on the player's audio session, via [DynamicsProcessing] (API 28+). Unlike an external EQ
 * it does its own gain staging — see [EqualizerHeadroom.inputGainDb] — with the limiter left on purely
 * as a safety net for inter-band overshoot.
 *
 * On API 26–27 this is unsupported and stays inert: `audiofx.Equalizer` has no input-gain control, so
 * it could only ship the boost without the headroom that makes it sound clean. The EQ screen says so,
 * and the "EQ headroom" setting still covers external EQs there.
 */
class EqualizerController {

    private var dynamics: DynamicsProcessing? = null
    private var sessionId = 0

    /**
     * Debug builds only: attach at priority 0 instead of [PRIORITY], which reproduces the
     * non-controlling-client state (every write comes back as `INVALID_OPERATION`) whenever another
     * effect app holds the session. The service sets it from a marker file; see its `lowPriorityDebug`.
     */
    @Volatile var debugLowPriority: Boolean = false

    val supported: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P

    val attached: Boolean get() = dynamics != null

    /**
     * (Re)create the effect on [sessionId] with [bands] baked into its config. Always releases the
     * previous instance first.
     *
     * The whole curve goes in through [DynamicsProcessing.Config] rather than through the per-band
     * setters, because on a Galaxy (Android 16) every live setter — `setPreEqBandAllChannelsTo`,
     * `setInputGainAllChannelsTo` — throws `UnsupportedOperationException: invalid parameter
     * operation` once audio is running on the session. Construction with a full config is the only
     * write path that device honours, and there is no `setConfig`, so a curve change means a rebuild.
     */
    fun attach(sessionId: Int, bands: FloatArray) {
        release()
        if (!supported || sessionId == 0) return
        val inputGain = EqualizerHeadroom.inputGainDb(bands)
        try {
            val eq = DynamicsProcessing.Eq(true, true, EqualizerHeadroom.BANDS)
            for (b in 0 until EqualizerHeadroom.BANDS) {
                eq.setBand(
                    b,
                    DynamicsProcessing.EqBand(true, EqualizerHeadroom.FREQUENCIES[b], bands.getOrElse(b) { 0f })
                )
            }
            // inputGain, preEq in use with our bands, no MBC, no post-EQ, limiter in use.
            val channel = DynamicsProcessing.Channel(
                inputGain,
                true, EqualizerHeadroom.BANDS,
                false, 0,
                false, 0,
                true
            )
            channel.setPreEq(eq)
            // inUse, enabled, linkGroup, attack ms, release ms, ratio, threshold dB, post gain dB.
            channel.setLimiter(DynamicsProcessing.Limiter(true, true, 0, 1f, 60f, 10f, -1f, 0f))

            val config = DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                CHANNELS,
                true, EqualizerHeadroom.BANDS,
                false, 0,
                false, 0,
                true
            ).setAllChannelsTo(channel).build()

            val priority = if (debugLowPriority) 0 else PRIORITY
            dynamics = DynamicsProcessing(priority, sessionId, config).apply { setEnabled(true) }
            this.sessionId = sessionId
            LokiLogger.i(TAG, "EQ attached to session $sessionId: ${bands.joinToString()} inputGain=${inputGain}dB")
        } catch (e: RuntimeException) {
            // Device without the effect, or a session that went away between the callback and here.
            LokiLogger.e(TAG, "EQ attach failed for session $sessionId", e)
            dynamics = null
        }
    }

    /**
     * Push a new curve onto the live effect. Cheap — no detach/attach, so no risk of a click mid-song.
     *
     * These setters only work while we hold effect CONTROL (see [PRIORITY]); a non-controlling client
     * gets `INVALID_OPERATION` on every write. If that happens we lost control to another effect app,
     * and rebuilding at our priority is the way to take it back.
     */
    fun applyCurve(bands: FloatArray): Boolean {
        val dp = dynamics ?: return true // nothing attached, so nothing to fail
        val inputGain = EqualizerHeadroom.inputGainDb(bands)
        try {
            for (b in 0 until EqualizerHeadroom.BANDS) {
                dp.setPreEqBandAllChannelsTo(
                    b,
                    DynamicsProcessing.EqBand(true, EqualizerHeadroom.FREQUENCIES[b], bands.getOrElse(b) { 0f })
                )
            }
            dp.setInputGainAllChannelsTo(inputGain)
            LokiLogger.i(TAG, "EQ curve ${bands.joinToString()} inputGain=${inputGain}dB")
            return true
        } catch (e: RuntimeException) {
            LokiLogger.e(TAG, "EQ curve write rejected, rebuilding to reclaim control", e)
            // Exactly one rebuild, and no write after it — [attach] bakes the curve into the config.
            // If that fails too the caller disables the feature; a retry loop here would be a loop
            // with an audio effect in it.
            attach(sessionId, bands)
            return attached
        }
    }

    /** Release the effect. Safe to call repeatedly — leaking one across session changes is the bug. */
    fun release() {
        dynamics?.let {
            try {
                it.release()
                LokiLogger.i(TAG, "EQ released")
            } catch (e: RuntimeException) {
                LokiLogger.e(TAG, "EQ release failed", e)
            }
        }
        dynamics = null
    }

    private companion object {
        const val TAG = "Equalizer"
        const val CHANNELS = 2

        // Ask for the top priority band. AudioFlinger gives effect CONTROL to the highest-priority
        // client on the session; every other client's writes come back as INVALID_OPERATION
        // ("invalid parameter operation"). Wavelet attaches with Integer.MAX_VALUE, so at priority 0
        // we were a spectator on our own effect — nothing we set ever reached the chain. Control is
        // only handed over on a strictly higher priority, so we also attach as early as possible
        // (on the session id, before playback), which is when nothing else has claimed it yet.
        const val PRIORITY = Int.MAX_VALUE
    }
}
