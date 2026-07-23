package ch.snepilatch.app.viewmodel

import android.content.Context
import ch.snepilatch.app.playback.MusicPlaybackService
import ch.snepilatch.app.util.LokiLogger
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Process-scoped store for the persisted user settings (like [ch.snepilatch.app.playback.SessionHolder]
 * / [Navigator]). Owns the setting [MutableStateFlow]s, their SharedPreferences persistence, the
 * region resolution, and the setters — including their side effects (notification-button push to the
 * service, locale change). [PlaybackViewModel] reads the playback-relevant ones (audio source, region,
 * canvas toggle) in the stream-resolution path; the UI reads/writes here directly.
 *
 * `canvasUrl` is NOT here — it's the current track's video URL (playback-derived, not persisted), so
 * it stays on [PlaybackViewModel]; `setCanvasEnabled` there wraps [setCanvasEnabled] to also clear it.
 */
object AppSettings {

    const val PREFS = "kotify_prefs"
    private const val TAG = "AppSettings"

    @Volatile private var appContext: Context? = null

    // Audio source preference: null = Spotify (default), "lossless" = third-party FLAC chain.
    val preferredAudioSource = MutableStateFlow<String?>(null)

    // Lyrics animation direction for line-synced (non word-synced): "vertical" or "horizontal"
    val lyricsAnimDirection = MutableStateFlow("vertical")

    // Language preference: "system", "en", "de", "ru", "gsw"
    val appLanguage = MutableStateFlow("system")

    // Notification button preferences: "like", "shuffle", "repeat"
    val notificationLeftButton = MutableStateFlow("repeat")
    val notificationRightButton = MutableStateFlow("like")

    // Content region for CDN resolution
    val contentRegion = MutableStateFlow("nearest")

    // Player background style: true = album-colour gradient (Spotify/YTM style), false = blurred art.
    val playerGradientBg = MutableStateFlow(true)

    // Canvas background toggle (the URL itself is playback state on PlaybackViewModel).
    val canvasEnabled = MutableStateFlow(false)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(context: Context) {
        appContext = context.applicationContext
        val prefs = prefs(context)
        val savedSource = prefs.getString("audio_source", null)
        // Migrate: old "spotify" value → null (Spotify CDN is now the default)
        preferredAudioSource.value = if (savedSource == "spotify") null else savedSource
        if (savedSource == "spotify") {
            prefs.edit().remove("audio_source").apply()
        }
        lyricsAnimDirection.value = prefs.getString("lyrics_anim_direction", "vertical") ?: "vertical"
        appLanguage.value = prefs.getString("app_language", "system") ?: "system"
        // Apply saved language on startup
        val lang = appLanguage.value
        if (lang != "system") {
            val locale = java.util.Locale.forLanguageTag(lang)
            val config = context.resources.configuration
            config.setLocale(locale)
            @Suppress("DEPRECATION")
            context.resources.updateConfiguration(config, context.resources.displayMetrics)
        }
        canvasEnabled.value = prefs.getBoolean("canvas_enabled", true)
        playerGradientBg.value = prefs.getBoolean("player_gradient_bg", true)
        contentRegion.value = prefs.getString("content_region", "nearest") ?: "nearest"
        notificationLeftButton.value = prefs.getString("notification_left_button", "repeat") ?: "repeat"
        notificationRightButton.value = prefs.getString("notification_right_button", "like") ?: "like"
    }

    /**
     * The 2-letter region passed to the CDN resolver. "nearest" resolves to the
     * device's real country at call time, preferring the mobile-network country
     * (where you physically are, roaming-aware), then the SIM's home country,
     * then the system locale region. The telephony signals are tried first
     * because the locale region only reflects the language/region *setting* — a
     * user in Switzerland with an English phone would otherwise resolve to the
     * wrong region. Any value other than "nearest" is used as-is.
     */
    fun effectiveRegion(): String {
        if (contentRegion.value != "nearest") return contentRegion.value
        val tm = appContext?.getSystemService(Context.TELEPHONY_SERVICE) as? android.telephony.TelephonyManager
        val net = tm?.networkCountryIso
        val sim = tm?.simCountryIso
        val locale = android.content.res.Resources.getSystem().configuration.locales[0].country
        // Keep only letters so a dual-SIM phone's "ch," collapses to "ch", then
        // take the first signal that yields a 2-letter code.
        val region = listOf(net, sim, locale)
            .firstNotNullOfOrNull { it?.filter(Char::isLetter)?.take(2)?.takeIf { c -> c.length == 2 } }
            ?.uppercase(java.util.Locale.ROOT)
            ?: "US"
        LokiLogger.i(TAG, "Content region 'nearest' -> net='$net' sim='$sim' locale='$locale' -> $region")
        return region
    }

    /** Direct-from-prefs read for service wiring, which can run before [load] on a headphone cold-start. */
    fun savedNotificationButtons(ctx: Context): Pair<String, String> {
        val prefs = prefs(ctx)
        return (prefs.getString("notification_left_button", "repeat") ?: "repeat") to
            (prefs.getString("notification_right_button", "like") ?: "like")
    }

    fun setPreferredAudioSource(source: String?, context: Context) {
        preferredAudioSource.value = source
        prefs(context)
            .edit().apply {
                if (source == null) remove("audio_source") else putString("audio_source", source)
            }.apply()
    }

    fun setContentRegion(region: String, context: Context) {
        contentRegion.value = region
        prefs(context)
            .edit().putString("content_region", region).apply()
    }

    fun setLyricsAnimDirection(direction: String, context: Context) {
        lyricsAnimDirection.value = direction
        prefs(context)
            .edit().putString("lyrics_anim_direction", direction).apply()
    }

    fun setAppLanguage(language: String, context: Context) {
        appLanguage.value = language
        prefs(context)
            .edit().putString("app_language", language).apply()
        // Apply locale change
        val locale = if (language == "system") {
            java.util.Locale.getDefault()
        } else {
            java.util.Locale.forLanguageTag(language)
        }
        val config = context.resources.configuration
        config.setLocale(locale)
        @Suppress("DEPRECATION")
        context.resources.updateConfiguration(config, context.resources.displayMetrics)
        // Restart activity to apply
        (context as? android.app.Activity)?.recreate()
    }

    fun setNotificationLeftButton(button: String, context: Context) {
        notificationLeftButton.value = button
        prefs(context)
            .edit().putString("notification_left_button", button).apply()
        MusicPlaybackService.instance?.let { svc ->
            svc.notificationLeftButton = button
            svc.updateNotification()
        }
    }

    fun setNotificationRightButton(button: String, context: Context) {
        notificationRightButton.value = button
        prefs(context)
            .edit().putString("notification_right_button", button).apply()
        MusicPlaybackService.instance?.let { svc ->
            svc.notificationRightButton = button
            svc.updateNotification()
        }
    }

    fun setPlayerGradientBg(enabled: Boolean, context: Context) {
        playerGradientBg.value = enabled
        prefs(context)
            .edit().putBoolean("player_gradient_bg", enabled).apply()
    }

    /** Persist the canvas toggle. PlaybackViewModel.setCanvasEnabled wraps this to also clear the URL. */
    fun setCanvasEnabled(enabled: Boolean, context: Context) {
        canvasEnabled.value = enabled
        prefs(context)
            .edit().putBoolean("canvas_enabled", enabled).apply()
    }
}
