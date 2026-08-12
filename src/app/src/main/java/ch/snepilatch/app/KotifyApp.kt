package ch.snepilatch.app

import android.app.Application
import android.provider.Settings
import ch.snepilatch.app.util.LokiLogger
import ch.snepilatch.app.viewmodel.AppSettings
import kotify.utils.LogBackend
import kotify.utils.Logger

class KotifyApp : Application() {
    override fun onCreate() {
        super.onCreate()

        // Direct-from-prefs read: AppSettings.load() hasn't run yet at this point (it runs from
        // MainActivity), and the user may have set this in Account > About > Debug Logging.
        val lokiEndpoint = AppSettings.savedLokiEndpoint(this)
        if (lokiEndpoint.isNotBlank()) {
            LokiLogger.init(
                endpoint = lokiEndpoint,
                appName = "snepilatch",
                deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID),
                appVersion = BuildConfig.VERSION_NAME
            )
        }

        // Always wired, even before logging is enabled: LokiLogger no-ops until init() has run,
        // so this picks up KotifyClient logs immediately if the user turns logging on later.
        Logger.setLogBackend(object : LogBackend {
            override var isDebugEnabled: Boolean = false
            override fun info(msg: String) { LokiLogger.i("Kotify", msg) }
            override fun error(msg: String) { LokiLogger.e("Kotify", msg) }
            override fun debug(msg: String) { if (isDebugEnabled) LokiLogger.d("Kotify", msg) }
        })
    }
}
