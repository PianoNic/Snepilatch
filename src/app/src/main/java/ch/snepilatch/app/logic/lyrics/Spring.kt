package ch.snepilatch.app.logic.lyrics

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A damped harmonic oscillator that follows a moving goal, solved analytically per step so the
 * result does not depend on the frame rate. [frequency] is in hertz, [dampingRatio] below 1
 * overshoots, 1 settles as fast as possible without overshoot, above 1 creeps in. This is the
 * spring the Beautiful Lyrics family of players animates every word with (a port of Fraktality's
 * spr.lua); the constants that go with it live in [LyricsStyle].
 */
class Spring(position: Float, private val frequency: Float, private val dampingRatio: Float) {

    var position: Float = position
        private set
    var velocity: Float = 0f
        private set
    var goal: Float = position
        private set

    /** Retarget without touching the current position, so the motion stays continuous. */
    fun setGoal(value: Float) {
        goal = value
    }

    /** Jump straight to [value] and rest there. */
    fun snap(value: Float) {
        position = value
        goal = value
        velocity = 0f
    }

    /** Advance by [dt] seconds. */
    fun step(dt: Float) {
        val d = dampingRatio
        val f = frequency * TWO_PI
        val o = position - goal
        when {
            d == 1f -> {
                val q = exp(-f * dt)
                val w = dt * q
                position = o * (q + w * f) + velocity * w + goal
                velocity = velocity * (q - w * f) - o * (w * f * f)
            }
            d < 1f -> {
                val q = exp(-d * f * dt)
                val c = sqrt(1f - d * d)
                val i = cos(dt * f * c)
                val j = sin(dt * f * c)
                val z = if (c > EPS) j / c else dt * f
                val y = if (f * c > EPS) j / (f * c) else dt
                position = (o * (i + z * d) + velocity * y) * q + goal
                velocity = (velocity * (i - z * d) - o * (z * f)) * q
            }
            else -> {
                val c = sqrt(d * d - 1f)
                val r1 = -f * (d + c)
                val r2 = -f * (d - c)
                val co2 = (velocity - o * r1) / (2f * f * c)
                val co1 = exp(r1 * dt) * (o - co2)
                val e2 = exp(r2 * dt)
                position = co1 + co2 * e2 + goal
                velocity = co1 * r1 + co2 * e2 * r2
            }
        }
    }

    /** True once the spring sits still on its goal, so the caller can stop stepping it. */
    val asleep: Boolean
        get() = abs(velocity) <= SLEEP_VELOCITY && abs(position - goal) <= SLEEP_OFFSET

    private companion object {
        const val TWO_PI = (2.0 * Math.PI).toFloat()
        const val EPS = 1e-5f
        const val SLEEP_OFFSET = 1f / 3840f
        const val SLEEP_VELOCITY = 1e-2f
    }
}
