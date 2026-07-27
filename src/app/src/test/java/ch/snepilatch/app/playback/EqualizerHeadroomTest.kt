package ch.snepilatch.app.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max

class EqualizerHeadroomTest {

    private fun flat() = FloatArray(EqualizerHeadroom.BANDS)

    @Test
    fun `input gain cancels the largest boost`() {
        val curve = flat().also {
            it[3] = 9f
            it[7] = 4f
        }
        assertEquals(-9f, EqualizerHeadroom.inputGainDb(curve), 1e-6f)
    }

    @Test
    fun `flat and cut-only curves need no headroom`() {
        assertEquals(0f, EqualizerHeadroom.inputGainDb(flat()), 1e-6f)
        assertEquals(0f, EqualizerHeadroom.inputGainDb(FloatArray(EqualizerHeadroom.BANDS) { -6f }), 1e-6f)
    }

    @Test
    fun `never above minus the largest positive gain, for any curve`() {
        val curves = listOf(
            flat(),
            FloatArray(EqualizerHeadroom.BANDS) { -12f },
            FloatArray(EqualizerHeadroom.BANDS) { 12f },
            FloatArray(EqualizerHeadroom.BANDS) { if (it % 2 == 0) 12f else -12f },
            FloatArray(EqualizerHeadroom.BANDS) { it - 5f },
            flat().also { it[0] = 0.5f }
        )
        for (curve in curves) {
            val peak = max(0f, curve.max())
            assertTrue(
                "curve ${curve.toList()}",
                EqualizerHeadroom.inputGainDb(curve) <= -peak + 1e-6f
            )
        }
    }

    @Test
    fun `band layout matches the ten sliders`() {
        assertEquals(10, EqualizerHeadroom.BANDS)
        assertEquals(EqualizerHeadroom.BANDS, EqualizerHeadroom.FREQUENCIES.size)
    }
}
