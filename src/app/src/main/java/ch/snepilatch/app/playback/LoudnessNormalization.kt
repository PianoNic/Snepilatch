package ch.snepilatch.app.playback

import kotlin.math.pow

/**
 * Turns a track's loudness into the linear gain [GainAudioProcessor] applies. Attenuation only: a
 * quieter-than-target track is left alone rather than boosted, since boosting is what clips.
 *
 * Spfy's media manifest carries a per-file `gain_db`, but it is null in every response we have
 * captured, and the third-party FLAC path has no Spfy metadata at all — so in practice every track
 * takes the fallback branch (see [gainFor]).
 */
object LoudnessNormalization {

    const val TARGET_LUFS = -14.0
    const val DEFAULT_FALLBACK_DB = -6.0

    /** Linear gain for [trackLoudnessDb], or a flat [fallbackDb] attenuation when loudness is unknown. */
    fun gainFor(trackLoudnessDb: Double?, fallbackDb: Double = DEFAULT_FALLBACK_DB): Float {
        val db = if (trackLoudnessDb != null) TARGET_LUFS - trackLoudnessDb else fallbackDb
        return dbToLinear(db.coerceAtMost(0.0))
    }

    fun dbToLinear(db: Double): Float = 10.0.pow(db / 20.0).toFloat()
}
