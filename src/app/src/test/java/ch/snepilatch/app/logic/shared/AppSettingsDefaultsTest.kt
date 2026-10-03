package ch.snepilatch.app.logic.shared

import android.content.Context
import android.content.SharedPreferences
import ch.snepilatch.app.data.PlayerShortcut
import ch.snepilatch.app.logic.relay.RelaySettings
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
    fun freshInstallDoesNotOpenQueueFromMiniPlayerSwipe() {
        assertFalse(GestureSettings.swipeDownOpensQueue.value)
        AppSettings.load(contextWith(emptyPrefs()))
        assertFalse(GestureSettings.swipeDownOpensQueue.value)
    }

    @Test
    fun unknownPlayerShortcutFallsBackToLike() {
        val prefs = emptyPrefs()
        every { prefs.getString("player_shortcut", null) } returns "unknown"

        AppSettings.load(contextWith(prefs))

        assertEquals(PlayerShortcut.LIKE, AppSettings.playerShortcut.value)
        AppSettings.load(contextWith(emptyPrefs()))
    }

    @Test
    fun playerButtonsKeepTheirOriginalDefaults() {
        AppSettings.load(contextWith(emptyPrefs()))
        assertEquals(PlayerShortcut.EQUALIZER, AppSettings.playerTopRightShortcut.value)
        assertEquals(PlayerShortcut.SHARE, AppSettings.playerShareShortcut.value)
        assertEquals(PlayerShortcut.QUEUE, AppSettings.playerQueueShortcut.value)
    }

    @Test
    fun unknownPlayerButtonActionsUseEachButtonsDefault() {
        val prefs = emptyPrefs()
        every { prefs.getString("player_top_right_shortcut", null) } returns "unknown"
        every { prefs.getString("player_share_shortcut", null) } returns "unknown"
        every { prefs.getString("player_queue_shortcut", null) } returns "unknown"
        AppSettings.load(contextWith(prefs))
        assertEquals(PlayerShortcut.EQUALIZER, AppSettings.playerTopRightShortcut.value)
        assertEquals(PlayerShortcut.SHARE, AppSettings.playerShareShortcut.value)
        assertEquals(PlayerShortcut.QUEUE, AppSettings.playerQueueShortcut.value)
    }

    @Test
    fun playerButtonChoicesPersistIndependently() {
        val stored = mutableMapOf<String, String?>()
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        every { editor.putString(any(), any()) } answers {
            stored[firstArg()] = secondArg()
            editor
        }
        val prefs = emptyPrefs()
        every { prefs.edit() } returns editor
        every { prefs.getString(any(), any()) } answers { stored[firstArg()] ?: secondArg() }
        val context = contextWith(prefs)
        try {
            AppSettings.load(context)
            AppSettings.setPlayerButtonActions(context, topRight = PlayerShortcut.QUEUE)
            assertEquals(PlayerShortcut.SHARE, AppSettings.playerShareShortcut.value)
            assertEquals(PlayerShortcut.QUEUE, AppSettings.playerQueueShortcut.value)
            AppSettings.setPlayerButtonActions(context, share = PlayerShortcut.LIKE)
            AppSettings.setPlayerButtonActions(context, queue = PlayerShortcut.EQUALIZER)
            assertEquals(PlayerShortcut.QUEUE, AppSettings.playerTopRightShortcut.value)
            assertEquals(PlayerShortcut.LIKE, AppSettings.playerShareShortcut.value)
            assertEquals(PlayerShortcut.EQUALIZER, AppSettings.playerQueueShortcut.value)

            AppSettings.load(contextWith(emptyPrefs()))
            AppSettings.load(context)
            assertEquals(PlayerShortcut.QUEUE, AppSettings.playerTopRightShortcut.value)
            assertEquals(PlayerShortcut.LIKE, AppSettings.playerShareShortcut.value)
            assertEquals(PlayerShortcut.EQUALIZER, AppSettings.playerQueueShortcut.value)
            assertEquals(PlayerShortcut.LIKE, AppSettings.playerShortcut.value)
        } finally {
            AppSettings.load(contextWith(emptyPrefs()))
        }
    }

    @Test
    fun swipeActionsUseTheirOwnDefaults() {
        AppSettings.load(contextWith(emptyPrefs()))

        assertEquals(PlayerShortcut.ADD_TO_PLAYLIST, GestureSettings.swipeLeftAction.value)
        assertEquals(PlayerShortcut.ADD_TO_QUEUE, GestureSettings.swipeRightAction.value)
    }

    @Test
    fun unknownSwipeActionsFallBackToTheirOwnDefaults() {
        val prefs = emptyPrefs()
        every { prefs.getString("swipe_left_action", null) } returns "unknown-left"
        every { prefs.getString("swipe_right_action", null) } returns "unknown-right"

        AppSettings.load(contextWith(prefs))

        assertEquals(PlayerShortcut.ADD_TO_PLAYLIST, GestureSettings.swipeLeftAction.value)
        assertEquals(PlayerShortcut.ADD_TO_QUEUE, GestureSettings.swipeRightAction.value)
        AppSettings.load(contextWith(emptyPrefs()))
    }

    @Test
    fun freshInstallUsesTheDefaultRelayServer() {
        AppSettings.load(contextWith(emptyPrefs()))
        assertEquals(RelaySettings.DEFAULT_URL, RelaySettings.url.value)
    }

    @Test
    fun aStoredChoiceStillWins() {
        val prefs: SharedPreferences = mockk {
            every { getString(any(), any()) } answers { secondArg() }
            every { getString("eq_mode", null) } returns AppSettings.EQ_OFF
            every { getBoolean(any(), any()) } answers { secondArg() }
            every { getBoolean("player_gradient_bg", any()) } returns true
            every { getBoolean("swipe_down_opens_queue", any()) } returns true
            every { getFloat(any(), any()) } answers { secondArg() }
        }
        AppSettings.load(contextWith(prefs))
        assertEquals(AppSettings.EQ_OFF, AppSettings.eqMode.value)
        assertEquals(true, AppSettings.playerGradientBg.value)
        assertEquals(true, GestureSettings.swipeDownOpensQueue.value)
        // Leave the object on the defaults the rest of the suite expects.
        AppSettings.load(contextWith(emptyPrefs()))
    }
}
