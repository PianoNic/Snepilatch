package ch.snepilatch.app.logic.relay

import android.content.Context
import ch.snepilatch.app.logic.shared.AppSettings
import java.security.SecureRandom

/**
 * Who this install is to the relay: made once, then kept. The pair is what brings a device back
 * into its jam after a dropped connection, and what stops anyone else from taking its place.
 */
data class RelayIdentity(val installId: String, val secret: String) {
    companion object {
        private const val KEY_INSTALL_ID = "relay_install_id"
        private const val KEY_SECRET = "relay_secret"

        fun load(context: Context): RelayIdentity {
            val prefs = context.getSharedPreferences(AppSettings.PREFS, Context.MODE_PRIVATE)
            val installId = prefs.getString(KEY_INSTALL_ID, null)
            val secret = prefs.getString(KEY_SECRET, null)
            if (installId != null && secret != null) return RelayIdentity(installId, secret)
            val created = RelayIdentity(randomHex(16), randomHex(32))
            prefs.edit().putString(KEY_INSTALL_ID, created.installId).putString(KEY_SECRET, created.secret).apply()
            return created
        }

        private fun randomHex(bytes: Int): String {
            val buffer = ByteArray(bytes).also { SecureRandom().nextBytes(it) }
            return buffer.joinToString("") { "%02x".format(it) }
        }
    }
}
