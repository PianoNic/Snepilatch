package ch.snepilatch.app.logic.lyrics

import kotlin.math.abs

/**
 * The springs behind one word, letter or dot: scale, lift (in em of the font size), glow (0..1)
 * and the fill edge in percent. The splines in [LyricsStyle] give the goal for the current
 * progress, the springs smooth the way there, so a word overshoots into its peak and settles.
 */
class Motion(
    restScale: Float,
    restLift: Float,
    restOpacity: Float = 1f,
    scaleFrequency: Float = LyricsStyle.SCALE_FREQUENCY,
    scaleDamping: Float = LyricsStyle.SCALE_DAMPING,
    liftFrequency: Float = LyricsStyle.LIFT_FREQUENCY,
    liftDamping: Float = LyricsStyle.LIFT_DAMPING,
    glowFrequency: Float = LyricsStyle.GLOW_FREQUENCY,
    glowDamping: Float = LyricsStyle.GLOW_DAMPING,
) {
    val scale = Spring(restScale, scaleFrequency, scaleDamping)
    val lift = Spring(restLift, liftFrequency, liftDamping)
    val glow = Spring(0f, glowFrequency, glowDamping)
    val opacity = Spring(restOpacity, LyricsStyle.DOT_OPACITY_FREQUENCY, LyricsStyle.DOT_OPACITY_DAMPING)
    var fill: Float = LyricsStyle.FILL_START

    val asleep: Boolean get() = scale.asleep && lift.asleep && glow.asleep && opacity.asleep

    fun aim(scale: Float, lift: Float, glow: Float, opacity: Float = 1f) {
        this.scale.setGoal(scale)
        this.lift.setGoal(lift)
        this.glow.setGoal(glow)
        this.opacity.setGoal(opacity)
    }

    fun step(dt: Float) {
        scale.step(dt)
        lift.step(dt)
        glow.step(dt)
        opacity.step(dt)
    }

    fun settle() {
        scale.snap(scale.goal)
        lift.snap(lift.goal)
        glow.snap(glow.goal)
        opacity.snap(opacity.goal)
    }
}

/**
 * Drives every syllable of one line (and the letters of the emphasised ones, plus the glow and
 * fill of a line-synced line) from the playback clock. [step] is called once per frame with the
 * position and the real frame delta; the UI reads the [Motion]s afterwards. Pure, no Compose.
 */
class LineAnimator(private val item: LineItem) {

    val words: List<Motion> = item.line.syllables.map { Motion(LyricsStyle.REST_SCALE, LyricsStyle.REST_LIFT) }

    /** Per syllable: one motion per letter when emphasised, else null. */
    val letters: List<List<Motion>?> = item.line.syllables.mapIndexed { i, s ->
        if (item.emphasised[i]) s.text.map { Motion(LyricsStyle.REST_SCALE, LyricsStyle.REST_LIFT) } else null
    }
    private val letterWindows: List<List<LongRange>> = item.line.syllables.mapIndexed { i, s ->
        if (item.emphasised[i]) letterWindows(s.startTimeMs, s.endTimeMs, s.text.length) else emptyList()
    }

    /** The whole line's glow and fill, used when the line is synced as one piece. */
    val lineGlow = Spring(0f, LyricsStyle.LINE_GLOW_FREQUENCY, LyricsStyle.LINE_GLOW_DAMPING)
    var lineFill: Float = LyricsStyle.FILL_START
        private set

    val asleep: Boolean
        get() = lineGlow.asleep && words.all { it.asleep } && letters.all { l -> l == null || l.all { it.asleep } }

    fun step(timeMs: Long, dt: Float) {
        stepLine(timeMs)
        lineGlow.step(dt)
        for (i in words.indices) {
            val s = item.line.syllables[i]
            val state = sungStateAt(timeMs, s.startTimeMs, s.endTimeMs)
            val p = progressAt(timeMs, s.startTimeMs, s.endTimeMs)
            val at = splineInput(state, p)
            words[i].aim(LyricsStyle.wordScale.at(at), LyricsStyle.wordLift.at(at), LyricsStyle.glow.at(at))
            words[i].fill = LyricsStyle.fillPosition(state, p)
            words[i].step(dt)
            letters[i]?.let { stepLetters(it, letterWindows[i], timeMs, dt, state) }
        }
    }

    /** Put everything where it would be at [timeMs] with no motion left, for a line composed at rest. */
    fun snapTo(timeMs: Long) {
        step(timeMs, 0f)
        lineGlow.snap(lineGlow.goal)
        words.forEach { it.settle() }
        letters.forEach { l -> l?.forEach { it.settle() } }
    }

    private fun stepLine(timeMs: Long) {
        val state = sungStateAt(timeMs, item.startMs, item.endMs)
        val p = progressAt(timeMs, item.startMs, item.endMs)
        lineGlow.setGoal(if (state == SungState.ACTIVE) LyricsStyle.lineGlow.at(p) else 0f)
        lineFill = when (state) {
            SungState.NOT_SUNG -> LyricsStyle.FILL_START
            SungState.SUNG -> LyricsStyle.FILL_END
            SungState.ACTIVE -> LyricsStyle.FILL_END * p
        }
    }

    private fun stepLetters(motions: List<Motion>, windows: List<LongRange>, timeMs: Long, dt: Float, wordState: SungState) {
        val active = windows.indexOfFirst { timeMs in it }
        when {
            wordState == SungState.NOT_SUNG -> motions.forEach {
                it.rest()
                it.fill = LyricsStyle.FILL_START
            }
            wordState == SungState.SUNG -> motions.forEach { it.done(0f) }
            // The tail of the word: every letter is lit, the glow lingers.
            active < 0 -> motions.forEach { it.done(LyricsStyle.glow.at(LyricsStyle.SUNG_LETTER_GLOW)) }
            else -> {
                val ap = progressAt(timeMs, windows[active].first, windows[active].last + 1)
                val baseScale = LyricsStyle.letterScale.at(ap)
                val baseLift = LyricsStyle.letterLift.at(ap)
                val baseGlow = LyricsStyle.glow.at(ap)
                for ((k, m) in motions.withIndex()) {
                    if (k > active) {
                        m.rest()
                        m.fill = LyricsStyle.FILL_START
                        continue
                    }
                    val d = abs(k - active)
                    val f = LyricsStyle.letterFalloff(d)
                    m.aim(
                        LyricsStyle.REST_SCALE + (baseScale - LyricsStyle.REST_SCALE) * f,
                        LyricsStyle.REST_LIFT + (baseLift - LyricsStyle.REST_LIFT) * f,
                        baseGlow * LyricsStyle.letterGlowFalloff(d),
                    )
                    m.fill = if (k == active) {
                        LyricsStyle.FILL_START + (LyricsStyle.FILL_END - LyricsStyle.FILL_START) * LyricsStyle.easeSinOut(ap)
                    } else {
                        LyricsStyle.FILL_END
                    }
                }
            }
        }
        motions.forEach { it.step(dt) }
    }

    private fun Motion.rest() = aim(LyricsStyle.REST_SCALE, LyricsStyle.REST_LIFT, 0f)

    private fun Motion.done(glow: Float) {
        aim(1f, 0f, glow)
        fill = LyricsStyle.FILL_END
    }

    private fun splineInput(state: SungState, progress: Float): Float = when (state) {
        SungState.NOT_SUNG -> 0f
        SungState.SUNG -> 1f
        SungState.ACTIVE -> progress
    }
}

/** The three interlude dots, each a timed syllable of its own with the dot splines and springs. */
class DotsAnimator(private val item: DotsItem) {

    val dots: List<Motion> = item.dots.map {
        Motion(
            restScale = LyricsStyle.DOT_REST_SCALE,
            restLift = 0f,
            restOpacity = LyricsStyle.DOT_REST_OPACITY,
            scaleFrequency = LyricsStyle.DOT_SCALE_FREQUENCY,
            scaleDamping = LyricsStyle.DOT_SCALE_DAMPING,
            liftFrequency = LyricsStyle.DOT_LIFT_FREQUENCY,
            liftDamping = LyricsStyle.DOT_LIFT_DAMPING,
            glowFrequency = LyricsStyle.DOT_GLOW_FREQUENCY,
            glowDamping = LyricsStyle.DOT_GLOW_DAMPING,
        )
    }

    val asleep: Boolean get() = dots.all { it.asleep }

    fun step(timeMs: Long, dt: Float) {
        for ((i, m) in dots.withIndex()) {
            val w = item.dots[i]
            val end = w.last + 1
            val at = when (sungStateAt(timeMs, w.first, end)) {
                SungState.NOT_SUNG -> 0f
                SungState.SUNG -> 1f
                SungState.ACTIVE -> progressAt(timeMs, w.first, end)
            }
            m.aim(LyricsStyle.dotScale.at(at), LyricsStyle.dotLift.at(at), LyricsStyle.dotGlow.at(at), LyricsStyle.dotOpacity.at(at))
            m.step(dt)
        }
    }

    fun snapTo(timeMs: Long) {
        step(timeMs, 0f)
        dots.forEach { it.settle() }
    }
}
