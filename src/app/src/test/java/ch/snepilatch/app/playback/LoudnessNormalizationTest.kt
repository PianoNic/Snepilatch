package ch.snepilatch.app.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class LoudnessNormalizationTest {

    @Test
    fun `dB to linear`() {
        assertEquals(1f, LoudnessNormalization.dbToLinear(0.0), 1e-6f)
        assertEquals(0.5012f, LoudnessNormalization.dbToLinear(-6.0), 1e-4f)
        assertEquals(0.1f, LoudnessNormalization.dbToLinear(-20.0), 1e-6f)
    }

    @Test
    fun `louder than target is attenuated to the target`() {
        // -8 dB track, -14 target => -6 dB of attenuation.
        assertEquals(LoudnessNormalization.dbToLinear(-6.0), LoudnessNormalization.gainFor(-8.0), 1e-6f)
    }

    @Test
    fun `quieter than target is never boosted`() {
        assertEquals(1f, LoudnessNormalization.gainFor(-20.0), 1e-6f)
        assertEquals(1f, LoudnessNormalization.gainFor(-14.0), 1e-6f)
    }

    @Test
    fun `missing loudness falls back to the configured attenuation`() {
        assertEquals(LoudnessNormalization.dbToLinear(-6.0), LoudnessNormalization.gainFor(null), 1e-6f)
        assertEquals(LoudnessNormalization.dbToLinear(-12.0), LoudnessNormalization.gainFor(null, -12.0), 1e-6f)
        // A positive fallback would boost — clamped away.
        assertEquals(1f, LoudnessNormalization.gainFor(null, 3.0), 1e-6f)
    }
}
