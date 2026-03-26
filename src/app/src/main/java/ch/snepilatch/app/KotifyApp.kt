package ch.snepilatch.app

import android.app.Application
import android.provider.Settings
import kotify.utils.LogBackend
import kotify.utils.Logger

class KotifyApp : Application() {
    override fun onCreate() {
        super.onCreate()

        // Loki logging: only enabled when loki.endpoint is set in local.properties
        val lokiEndpoint = BuildConfig.LOKI_ENDPOINT
        if (lokiEndpoint.isNotBlank()) {
            LokiLogger.init(
                endpoint = lokiEndpoint,
                appName = "snepilatch",
                deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID),
                appVersion = BuildConfig.VERSION_NAME
            )

            // Forward KotifyClient logs to LokiLogger
            Logger.setLogBackend(object : LogBackend {
                override var isDebugEnabled: Boolean = false
                override fun info(msg: String) { LokiLogger.i("Kotify", msg) }
                override fun error(msg: String) { LokiLogger.e("Kotify", msg) }
                override fun debug(msg: String) { if (isDebugEnabled) LokiLogger.d("Kotify", msg) }
            })
        }
    }
}
