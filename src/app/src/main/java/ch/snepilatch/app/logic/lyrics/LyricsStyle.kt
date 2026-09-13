package ch.snepilatch.app.logic.lyrics

import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * The numbers behind the Beautiful Lyrics / Spicy Lyrics look, as read from spicy-lyrics
 * (`LyricsAnimator.ts`, `Mixed.css`, `Spring.ts`): what a word does over its own progress, how a
 * line dims and blurs with its distance from the sung one, how the interlude dots breathe. Lengths
 * are in em of the lyrics font size unless the name says px, which maps to dp on the phone.
 */
object LyricsStyle {

    /** The web player runs its clock 100 ms ahead of the reported position. */
    const val LEAD_MS = 100L

    /** Line opacity per state. */
    const val ACTIVE_OPACITY = 1f
    const val NOT_SUNG_OPACITY = 0.51f
    const val SUNG_OPACITY = 0.497f

    /** Glyph alpha at the two ends of the fill: sung side, unsung side; a whole line uses 0.35. */
    const val FILL_ALPHA = 0.85f
    const val FILL_ALPHA_END = 0.5f
    const val LINE_FILL_ALPHA_END = 0.35f

    /** The fill band is this wide, in percent of the element; the leading edge runs -20 % to 100 %. */
    const val FILL_BAND = 20f
    const val FILL_START = -20f
    const val FILL_END = 100f

    /** A line box scales up this much while sung (line-synced type only). */
    const val LINE_ACTIVE_SCALE = 1.05f

    /** Line opacity and scale transitions. */
    const val LINE_TRANSITION_MS = 200

    /** Blur of a line at a distance from the active one, in px. */
    const val BLUR_PER_LINE_PX = 1.25f
    const val MAX_BLUR_PX = BLUR_PER_LINE_PX * 5 + BLUR_PER_LINE_PX * 0.465f

    /** A syllable this long is lit letter by letter, with its window ending this much earlier. */
    const val EMPHASIS_MIN_MS = 1000L
    const val EMPHASIS_TAIL_MS = 250L
    const val SUNG_LETTER_GLOW = 0.2f

    /** A gap between lines at least this long gets the dots, which leave this early before the next line. */
    const val DOTS_MIN_GAP_MS = 3000L
    const val DOTS_PRE_HIDDEN_MS = 500L
    const val DOTS_END_PADDING_MS = -550L

    /** Interlude dots pop in and out over these times; the row collapses over the third. */
    const val DOTS_IN_MS = 300
    const val DOTS_OUT_MS = 400
    const val DOTS_LINE_MS = 140
    const val DOT_REST_SCALE = 0.75f
    const val DOT_REST_OPACITY = 0.35f

    /** Idle word scale and lift, i.e. every spline's first point. */
    const val REST_SCALE = 0.95f
    const val REST_LIFT = 0.01f

    /** Spring frequencies (Hz) and damping ratios. */
    const val SCALE_FREQUENCY = 0.88f
    const val SCALE_DAMPING = 0.64f
    const val LIFT_FREQUENCY = 1.45f
    const val LIFT_DAMPING = 0.4f
    const val GLOW_FREQUENCY = 1.18f
    const val GLOW_DAMPING = 0.56f
    const val LINE_GLOW_FREQUENCY = 1f
    const val LINE_GLOW_DAMPING = 0.5f
    const val DOT_SCALE_FREQUENCY = 0.7f
    const val DOT_SCALE_DAMPING = 0.6f
    const val DOT_LIFT_FREQUENCY = 1.25f
    const val DOT_LIFT_DAMPING = 0.4f
    const val DOT_GLOW_FREQUENCY = 1f
    const val DOT_GLOW_DAMPING = 0.5f
    const val DOT_OPACITY_FREQUENCY = 1f
    const val DOT_OPACITY_DAMPING = 0.5f

    /** Glow: shadow blur in px = base + factor * glow; shadow opacity = factor * glow. */
    const val GLOW_BLUR_BASE_PX = 4f
    const val WORD_GLOW_BLUR_PX = 2f
    const val WORD_GLOW_OPACITY = 0.35f
    const val LETTER_GLOW_BLUR_PX = 12f
    const val LETTER_GLOW_OPACITY = 1.85f
    const val LINE_GLOW_BLUR_PX = 8f
    const val LINE_GLOW_OPACITY = 0.5f
    const val DOT_GLOW_BLUR_PX = 6f
    const val DOT_GLOW_OPACITY = 0.9f

    /** A letter's lift is doubled compared to a word's. */
    const val LETTER_LIFT_FACTOR = 2f

    /** Word spacing in ch, line height in em, dot size in em, scroll anchor, scroll lockout. */
    const val WORD_GAP_CH = 0.32f
    const val LINE_HEIGHT_EM = 1.1818f
    const val DOT_SIZE_EM = 1.3f
    const val SCROLL_ABOVE_CENTER_PX = 30f
    const val USER_SCROLL_COOLDOWN_MS = 750L

    val wordScale = CubicSpline(0f to REST_SCALE, 0.7f to 1.0505f, 1f to 1f)
    val letterScale = CubicSpline(0f to REST_SCALE, 0.7f to 1.175f, 1f to 1f)
    val wordLift = CubicSpline(0f to REST_LIFT, 0.9f to -(1f / 60f), 1f to 0f)
    val letterLift = CubicSpline(0f to REST_LIFT, 0.9f to -(1f / 56f), 1f to 0f)
    val glow = CubicSpline(0f to 0f, 0.15f to 1f, 0.6f to 1f, 1f to 0f)
    val lineGlow = CubicSpline(0f to 0f, 0.5f to 1f, 1f to 0f)
    val dotScale = CubicSpline(0f to DOT_REST_SCALE, 0.7f to 1.05f, 1f to 1f)
    val dotLift = CubicSpline(0f to 0f, 0.9f to -0.12f, 1f to 0f)
    val dotGlow = CubicSpline(0f to 0f, 0.6f to 1f, 1f to 1f)
    val dotOpacity = CubicSpline(0f to DOT_REST_OPACITY, 0.6f to 1f, 1f to 1f)

    /** How blurred a line at [distance] lines from the active one is, in px. */
    fun blurPx(distance: Int): Float =
        if (distance <= 0) 0f else min(BLUR_PER_LINE_PX * distance, MAX_BLUR_PX)

    /** How much of the active letter's motion a letter [distance] letters away shares. */
    fun letterFalloff(distance: Int): Float = 1f / (1f + distance.toFloat().pow(2.8f))

    /** Same for the glow, which spreads wider. */
    fun letterGlowFalloff(distance: Int): Float = 1f / (1f + distance * 0.9f)

    /** d3's easeSinOut, the one eased fill: the active letter's. */
    fun easeSinOut(p: Float): Float = sin(p * (Math.PI / 2).toFloat())

    /** Fill position in percent for a word or letter at [state] with [progress] through it. */
    fun fillPosition(state: SungState, progress: Float): Float = when (state) {
        SungState.NOT_SUNG -> FILL_START
        SungState.SUNG -> FILL_END
        SungState.ACTIVE -> FILL_START + (FILL_END - FILL_START) * progress
    }
}

enum class SungState { NOT_SUNG, ACTIVE, SUNG }

/** Where [timeMs] falls against a window: before it, inside it, or past it. */
fun sungStateAt(timeMs: Long, startMs: Long, endMs: Long): SungState = when {
    timeMs < startMs -> SungState.NOT_SUNG
    timeMs >= endMs -> SungState.SUNG
    else -> SungState.ACTIVE
}

/** 0 at [startMs], 1 at [endMs], clamped. */
fun progressAt(timeMs: Long, startMs: Long, endMs: Long): Float =
    ((timeMs - startMs).toFloat() / (endMs - startMs).coerceAtLeast(1L)).coerceIn(0f, 1f)
