package ch.snepilatch.app.logic.lyrics

import kotify.api.lyrics.Syllable
import kotify.api.lyrics.SyncedLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpringTest {

    @Test fun anUnderdampedSpring_overshootsItsGoalThenSettlesOnIt() {
        val s = Spring(0f, LyricsStyle.SCALE_FREQUENCY, LyricsStyle.SCALE_DAMPING)
        s.setGoal(1f)
        var peak = 0f
        repeat(600) {
            s.step(1f / 120f)
            peak = maxOf(peak, s.position)
        }
        assertTrue("peak $peak", peak > 1.05f)
        assertEquals(1f, s.position, 0.002f)
        assertTrue(s.asleep)
    }

    @Test fun theStepIsFrameRateIndependent() {
        val fast = Spring(0f, 1.2f, 0.5f).apply { setGoal(1f) }
        val slow = Spring(0f, 1.2f, 0.5f).apply { setGoal(1f) }
        repeat(60) { fast.step(1f / 60f) }
        repeat(30) { slow.step(1f / 30f) }
        assertEquals(fast.position, slow.position, 0.002f)
    }

    @Test fun criticalAndOverdamped_neverOvershoot() {
        for (damping in listOf(1f, 1.5f)) {
            val s = Spring(0f, 2f, damping).apply { setGoal(1f) }
            repeat(400) {
                s.step(1f / 60f)
                assertTrue("damping $damping", s.position <= 1.0001f)
            }
            assertEquals(1f, s.position, 0.01f)
        }
    }

    @Test fun snap_putsItAtRestOnTheValue() {
        val s = Spring(0f, 1f, 0.5f).apply {
            setGoal(1f)
            step(0.1f)
        }
        s.snap(0.3f)
        assertEquals(0.3f, s.position, 0f)
        assertEquals(0f, s.velocity, 0f)
        assertTrue(s.asleep)
    }
}

class CubicSplineTest {

    @Test fun itPassesThroughItsPointsAndClampsOutside() {
        val spline = LyricsStyle.wordScale
        assertEquals(0.95f, spline.at(0f), 1e-5f)
        assertEquals(1.0505f, spline.at(0.7f), 1e-4f)
        assertEquals(1f, spline.at(1f), 1e-5f)
        assertEquals(0.95f, spline.at(-1f), 0f)
        assertEquals(1f, spline.at(2f), 0f)
    }

    @Test fun theGlowPlateauStaysNearOneBetweenItsMiddleKnots() {
        assertTrue(LyricsStyle.glow.at(0.15f) > 0.99f)
        assertTrue(LyricsStyle.glow.at(0.4f) > 0.9f)
        assertTrue(LyricsStyle.glow.at(0.05f) < 0.5f)
        assertEquals(0f, LyricsStyle.glow.at(1f), 1e-5f)
    }

    @Test fun twoPoints_isAStraightLine() {
        val s = CubicSpline(0f to 0f, 2f to 4f)
        assertEquals(2f, s.at(1f), 1e-5f)
    }
}

class LyricItemsTest {

    private fun line(start: Long, end: Long, text: String = "la", syllables: List<Syllable> = emptyList()) =
        SyncedLine(start, end, text, syllables)

    @Test fun aLongGapGetsDots_aShortOneDoesNot() {
        val items = lyricItems(listOf(line(5000, 7000), line(8000, 9000), line(20_000, 21_000)))
        assertEquals(listOf("D", "L", "L", "D", "L"), items.map { if (it is DotsItem) "D" else "L" })
        val lead = items[0] as DotsItem
        assertEquals(0L, lead.startMs)
        assertEquals(5000L, lead.endMs)
        // Three equal thirds, each ending 183 ms early, the last 550 ms before the vocals.
        assertEquals(listOf(0L, 1483L, 2966L), lead.dots.map { it.first })
        assertEquals(4450L, lead.dots.last().last + 1)
    }

    @Test fun noteLinesAreDroppedAndBecomeTheGap() {
        val items = lyricItems(listOf(line(1000, 2000), line(2000, 9000, "♪"), line(9000, 10_000)))
        assertEquals(3, items.size)
        assertTrue(items[1] is DotsItem)
        assertEquals(2000L, items[1].startMs)
        assertEquals(9000L, items[1].endMs)
    }

    @Test fun aLineWithoutAnEnd_endsWhereTheNextStarts() {
        val items = lyricItems(listOf(line(1000, 1000), line(3000, 3000)))
        assertEquals(3000L, items[0].endMs)
        assertEquals(3001L, items[1].endMs)
    }

    @Test fun wordsAndEmphasis_comeFromTheSyllables() {
        val item = LineItem(
            line(
                0, 3000, "Won der ful",
                listOf(Syllable(0, 500, "Won"), Syllable(500, 800, "der", isPartOfWord = true), Syllable(800, 3000, "ful"))
            ),
            0, 3000,
        )
        assertEquals(listOf(0..1, 2..2), item.words)
        assertEquals(listOf(false, false, true), item.emphasised)
    }

    @Test fun letterWindows_splitTheWordMinusItsTailEvenly() {
        val windows = letterWindows(1000, 2250, 4)
        assertEquals(listOf(1000L, 1250L, 1500L, 1750L), windows.map { it.first })
        assertEquals(2000L, windows.last().last + 1)
        assertTrue(letterWindows(0, 100, 0).isEmpty())
    }
}

class LineAnimatorTest {

    private val item = LineItem(
        SyncedLine(
            1000, 4000, "Hey ooooh",
            listOf(Syllable(1000, 1500, "Hey"), Syllable(1500, 4000, "ooooh")),
        ),
        1000, 4000,
    )

    private fun LineAnimator.run(fromMs: Long, toMs: Long) {
        var t = fromMs
        while (t < toMs) {
            step(t, 1f / 60f)
            t += 16
        }
    }

    @Test fun aWord_growsPastOneWhileSungAndSettlesAtOne() {
        val a = LineAnimator(item)
        a.snapTo(0)
        assertEquals(LyricsStyle.REST_SCALE, a.words[0].scale.position, 0f)
        assertEquals(LyricsStyle.FILL_START, a.words[0].fill, 0f)
        var peak = 0f
        var t = 1000L
        while (t < 1500) {
            a.step(t, 1f / 60f)
            peak = maxOf(peak, a.words[0].scale.position)
            t += 16
        }
        assertTrue("fill ${a.words[0].fill}", a.words[0].fill > 90f)
        a.run(1500, 4000)
        assertTrue("peak $peak", peak > 1f)
        assertEquals(1f, a.words[0].scale.position, 0.01f)
        assertEquals(LyricsStyle.FILL_END, a.words[0].fill, 0f)
        assertEquals(0f, a.words[0].glow.position, 0.02f)
    }

    @Test fun theLongWord_isLitLetterByLetterAndNeighboursShareTheLift() {
        val a = LineAnimator(item)
        assertEquals(5, a.letters[1]!!.size)
        // Letters share 2250 ms (2500 minus the 250 ms tail): 450 ms each. At 2200 ms the second is lit.
        a.run(1000, 2200)
        val letters = a.letters[1]!!
        assertEquals(LyricsStyle.FILL_END, letters[0].fill, 0f)
        assertTrue(letters[1].fill > LyricsStyle.FILL_START && letters[1].fill < LyricsStyle.FILL_END)
        assertEquals(LyricsStyle.FILL_START, letters[2].fill, 0f)
        assertTrue("active letter lifts", letters[1].scale.goal > letters[0].scale.goal)
        assertTrue("the letter behind shares half", letters[0].scale.goal > LyricsStyle.REST_SCALE)
        assertEquals("letters ahead rest", LyricsStyle.REST_SCALE, letters[2].scale.goal, 0f)
        // In the tail every letter is lit and glows.
        a.run(2200, 3900)
        assertTrue(letters.all { it.fill == LyricsStyle.FILL_END })
        assertTrue(letters[4].glow.goal > 0.9f)
        // Once the word is sung the glow goes and the scale rests at one.
        a.run(3900, 6000)
        assertEquals(0f, letters[4].glow.goal, 0f)
        assertEquals(1f, letters[4].scale.position, 0.01f)
        assertTrue(a.asleep)
    }

    @Test fun theLineGlowPeaksHalfwayAndTheFillRunsZeroToHundred() {
        val a = LineAnimator(item)
        a.step(2500, 0f)
        assertEquals(1f, a.lineGlow.goal, 1e-4f)
        assertEquals(50f, a.lineFill, 0.1f)
        a.step(5000, 0f)
        assertEquals(0f, a.lineGlow.goal, 0f)
        assertEquals(LyricsStyle.FILL_END, a.lineFill, 0f)
    }

    @Test fun dots_restDimAndSmallThenLightUpAndStay() {
        val dots = DotsAnimator(DotsItem(0, 3000, listOf(0L until 1000L, 1000L until 2000L, 2000L until 2450L)))
        dots.snapTo(0)
        assertEquals(LyricsStyle.DOT_REST_OPACITY, dots.dots[2].opacity.position, 0f)
        assertEquals(LyricsStyle.DOT_REST_SCALE, dots.dots[2].scale.position, 0f)
        var t = 0L
        while (t < 2900) {
            dots.step(t, 1f / 60f)
            t += 16
        }
        assertEquals(1f, dots.dots[0].opacity.goal, 0f)
        assertEquals(1f, dots.dots[2].opacity.goal, 0f)
        assertFalse(dots.dots[0].lift.position > 0.01f)
    }
}
