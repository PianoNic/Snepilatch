package ch.snepilatch.app

import android.app.Application
import android.provider.Settings
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import ch.snepilatch.app.util.LokiLogger
import ch.snepilatch.app.viewmodel.AppSettings
import kotify.utils.LogBackend
import kotify.utils.Logger

class KotifyApp : Application(), ImageLoaderFactory {
    /**
     * The one image loader the whole app uses (issue #611).
     *
     * Coil reads this off the Application, so every AsyncImage and every direct
     * `Coil.imageLoader(context)` call gets it without any of them asking. That is the point: cover
     * art is loaded from the queue, the player, the library, the lyrics screen, the notification and
     * the palette extractor, and a cache that only some of them shared would keep re-fetching the
     * same artwork.
     *
     * Both caches are sized here rather than left to the defaults so the numbers are a decision.
     * Cache headers are ignored on purpose: cover art at a given url never changes, so revalidating
     * it costs a round trip per image to be told nothing changed, which is exactly the stall that
     * shows up as jank when scrolling back through a list.
     */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .memoryCache {
            MemoryCache.Builder(this)
                .maxSizePercent(0.25)
                .build()
        }
        .diskCache {
            DiskCache.Builder()
                .directory(cacheDir.resolve("image_cache"))
                // Roughly a few thousand covers. Large enough that a scroll back through a library
                // is free, small enough to stay a cache rather than a download of everything seen.
                .maxSizeBytes(200L * 1024 * 1024)
                .build()
        }
        .respectCacheHeaders(false)
        .build()

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
