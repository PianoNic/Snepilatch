package ch.snepilatch.app.logic.shared

import android.content.Context
import android.content.SharedPreferences
import ch.snepilatch.app.data.NavSlot
import kotlinx.coroutines.flow.MutableStateFlow

/** What the bottom bar's fourth button opens; Settings unless the user picked another or none. Loaded with the rest of [AppSettings]. */
object NavSettings {

    val navSlot = MutableStateFlow(NavSlot.SETTINGS)

    fun load(prefs: SharedPreferences) {
        navSlot.value = NavSlot.fromId(prefs.getString("nav_slot", null))
    }

    fun setNavSlot(slot: NavSlot, context: Context) {
        navSlot.value = slot
        context.getSharedPreferences(AppSettings.PREFS, Context.MODE_PRIVATE).edit().putString("nav_slot", slot.id).apply()
    }
}
