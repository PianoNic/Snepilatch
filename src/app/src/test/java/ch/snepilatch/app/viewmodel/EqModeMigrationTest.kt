package ch.snepilatch.app.viewmodel

import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The equalizer used to persist two independent booleans that could both be set, which is exactly the
 * state the single [AppSettings.eqMode] replaces. Anyone upgrading must land on the mode that matches
 * what they were hearing, so this pins the one-time carry-over.
 */
class EqModeMigrationTest {

    private fun prefs(inApp: Boolean, headroom: Boolean): SharedPreferences = mockk {
        every { getBoolean("eq_enabled", false) } returns inApp
        every { getBoolean("eq_headroom_enabled", false) } returns headroom
    }

    @Test
    fun inAppEqualizerBecomesInApp() {
        assertEquals(AppSettings.EQ_IN_APP, AppSettings.migratedEqMode(prefs(inApp = true, headroom = false)))
    }

    @Test
    fun headroomAloneBecomesExternal() {
        assertEquals(AppSettings.EQ_EXTERNAL, AppSettings.migratedEqMode(prefs(inApp = false, headroom = true)))
    }

    @Test
    fun neitherBecomesOff() {
        assertEquals(AppSettings.EQ_OFF, AppSettings.migratedEqMode(prefs(inApp = false, headroom = false)))
    }

    @Test
    fun theImpossibleBothOnStateFavoursTheInAppEqualizer() {
        // The old UI allowed this and showed "handled by the equalizer", so the in-app EQ was what
        // actually ran. Resolving it the other way would silently attenuate and drop their curve.
        assertEquals(AppSettings.EQ_IN_APP, AppSettings.migratedEqMode(prefs(inApp = true, headroom = true)))
    }
}
