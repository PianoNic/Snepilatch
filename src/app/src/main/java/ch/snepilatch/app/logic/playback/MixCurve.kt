package ch.snepilatch.app.logic.playback

/**
 * A volume curve of a transition (#1075), as the reference client's mixing bundle stores one: a cubic
 * Bezier with x and y in 0..1, x being how far through the overlap playback is. These two are its
 * "Smooth crossfade" (volume-style-out-cross-shape and volume-style-in-cross-shape), the volume of its Fade
 * preset: the old song holds, then both cross quickly past the middle.
 */
class MixCurve(private val p1: Pair<Float, Float>, private val p2: Pair<Float, Float>, private val y0: Float, private val y3: Float) {

    /** The volume at [x], solving the Bezier for x by bisection: x grows monotonically along these. */
    fun at(x: Float): Float {
        val target = x.coerceIn(0f, 1f)
        var low = 0f
        var high = 1f
        repeat(STEPS) {
            val t = (low + high) / 2
            if (cubic(t, 0f, p1.first, p2.first, 1f) < target) low = t else high = t
        }
        return cubic((low + high) / 2, y0, p1.second, p2.second, y3).coerceIn(0f, 1f)
    }

    private fun cubic(t: Float, a: Float, b: Float, c: Float, d: Float): Float {
        val u = 1 - t
        return u * u * u * a + 3 * u * u * t * b + 3 * u * t * t * c + t * t * t * d
    }

    companion object {
        private const val STEPS = 24
        val FADE_OUT = MixCurve(0.6f to 1f, 0.6f to 0f, y0 = 1f, y3 = 0f)
        val FADE_IN = MixCurve(0.4f to 0f, 0.4f to 1f, y0 = 0f, y3 = 1f)

        /** The reference client's cap on an overlap: a fifth of the track. */
        const val MAX_TRACK_SHARE = 0.2f

        /** How long the overlap of a track of [trackMs] is when the setting asks for [settingMs]. */
        fun overlapMs(settingMs: Long, trackMs: Long): Long = minOf(settingMs, (trackMs * MAX_TRACK_SHARE).toLong())
    }
}
