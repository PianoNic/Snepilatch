package ch.snepilatch.app.viewmodel

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * A default has to be written twice — in the flow initialiser and in [AppSettings.load]'s fallback —
 * and changing one without the other only shows up after a process restart. These pin both.
 */
class AppSettingsDefaultsTest {

    /** Fresh install: every read returns whatever default the caller passed. */
    private fun emptyPrefs(): SharedPreferences = mockk {
        every { getString(any(), any()) } answers { secondArg() }
        every { getBoolean(any(), any()) } answers { secondArg() }
        every { getFloat(any(), any()) } answers { secondArg() }
    }

    private fun contextWith(prefs: SharedPreferences): Context = mockk {
        every { applicationContext } returns this@mockk
        every { getSharedPreferences(AppSettings.PREFS, any()) } returns prefs
        every { resources } returns mockk(relaxed = true)
    }

    @Test
    fun freshInstallStartsWithTheInAppEqualizer() {
        assertEquals(AppSettings.EQ_IN_APP, AppSettings.eqMode.value)
        AppSettings.load(contextWith(emptyPrefs()))
        assertEquals(AppSettings.EQ_IN_APP, AppSettings.eqMode.value)
    }

    @Test
    fun freshInstallStartsWithTheFlowingCover() {
        assertFalse(AppSettings.playerGradientBg.value)
        AppSettings.load(contextWith(emptyPrefs()))
        assertFalse(AppSettings.playerGradientBg.value)
    }

    @Test
    fun aStoredChoiceStillWins() {
        val prefs: SharedPreferences = mockk {
            every { getString(any(), any()) } answers { secondArg() }
            every { getString("eq_mode", null) } returns AppSettings.EQ_OFF
            every { getBoolean(any(), any()) } answers { secondArg() }
            every { getBoolean("player_gradient_bg", any()) } returns true
            every { getFloat(any(), any()) } answers { secondArg() }
        }
        AppSettings.load(contextWith(prefs))
        assertEquals(AppSettings.EQ_OFF, AppSettings.eqMode.value)
        assertEquals(true, AppSettings.playerGradientBg.value)
        // Leave the object on the defaults the rest of the suite expects.
        AppSettings.load(contextWith(emptyPrefs()))
    }
}
