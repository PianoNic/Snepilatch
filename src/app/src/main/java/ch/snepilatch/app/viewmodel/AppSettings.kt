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

    // Equalizer modes; see [eqMode].
    const val EQ_OFF = "off"
    const val EQ_IN_APP = "inapp"
    const val EQ_EXTERNAL = "external"

    // Audio source ids for [preferredAudioSource]; null means Spfy's own CDN.
    const val SOURCE_LOSSLESS = "lossless"
    const val SOURCE_YTM = "ytm"

    @Volatile private var appContext: Context? = null

    // Audio source: null = Spfy (default), [SOURCE_LOSSLESS] = third-party FLAC chain,
    // [SOURCE_YTM] = YouTube Music (ch.snepilatch.app.playback.YouTubeMusicSource).
    val preferredAudioSource = MutableStateFlow<String?>(null)

    // Which source downloads fetch from, kept apart from [preferredAudioSource] so the files can be
    // FLAC while streaming stays on YouTube Music, or the other way round. Spfy is not an option:
    // its stream is Widevine and the saved bytes would not play.
    val downloadSource = MutableStateFlow(SOURCE_YTM)

    // Keep a track once it has actually been listened through, by encoding the audio that was
    // already decoded to play it rather than fetching the song again. Off by default: it holds the
    // decoded track in memory while it plays, and spends storage on its own.
    val autoSaveListened = MutableStateFlow(false)

    // Lyrics animation direction for line-synced (non word-synced): "vertical" or "horizontal"
    val lyricsAnimDirection = MutableStateFlow("vertical")

    // Language preference: "system", "en", "de", "ru", "gsw"
    val appLanguage = MutableStateFlow("system")

    // Notification button preferences: "like", "shuffle", "repeat"
    val notificationLeftButton = MutableStateFlow("repeat")
    val notificationRightButton = MutableStateFlow("like")

    // Content region for CDN resolution
    val contentRegion = MutableStateFlow("nearest")

    // Player background style: true = album-colour gradient (Spfy/YTM style), false = blurred art.
    val playerGradientBg = MutableStateFlow(true)

    // Canvas background toggle (the URL itself is playback state on PlaybackViewModel).
    val canvasEnabled = MutableStateFlow(false)

    // How the equalizer is handled. One choice, because the options exclude each other: the in-app EQ
    // computes its own input gain from the curve, while the headroom attenuation exists only to give an
    // EXTERNAL equalizer (Wavelet & co.) room to boost into. Running both would attenuate twice.
    //   [EQ_OFF]      no EQ, no attenuation
    //   [EQ_IN_APP]   our 10-band EQ, which makes its own headroom
    //   [EQ_EXTERNAL] no in-app EQ, just [eqHeadroomDb] of attenuation for the external one
    val eqMode = MutableStateFlow(EQ_OFF)
    val eqHeadroomDb = MutableStateFlow(-6f)

    // Per-band gains in dB for the in-app EQ (see EqualizerHeadroom.FREQUENCIES).
    val eqBands = MutableStateFlow(FloatArray(ch.snepilatch.app.playback.EqualizerHeadroom.BANDS))

    /** True when our own equalizer should be attached. */
    val eqInApp: Boolean get() = eqMode.value == EQ_IN_APP

    /** True when we should attenuate for someone else's equalizer. */
    val eqExternal: Boolean get() = eqMode.value == EQ_EXTERNAL

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(context: Context) {
        appContext = context.applicationContext
        val prefs = prefs(context)
        val savedSource = prefs.getString("audio_source", null)
        // Migrate: old "spotify" value → null (Spfy CDN is now the default)
        preferredAudioSource.value = if (savedSource == "spotify") null else savedSource
        if (savedSource == "spotify") {
            prefs.edit().remove("audio_source").apply()
        }
        downloadSource.value = prefs.getString("download_source", SOURCE_YTM) ?: SOURCE_YTM
        autoSaveListened.value = prefs.getBoolean("auto_save_listened", false)
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
        eqHeadroomDb.value = prefs.getFloat("eq_headroom_db", -6f)
        eqMode.value = prefs.getString("eq_mode", null) ?: migratedEqMode(prefs)
        eqBands.value = parseBands(prefs.getString("eq_bands", null))
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

    fun setDownloadSource(source: String, context: Context) {
        downloadSource.value = source
        prefs(context).edit().putString("download_source", source).apply()
    }

    fun setAutoSaveListened(enabled: Boolean, context: Context) {
        autoSaveListened.value = enabled
        prefs(context).edit().putBoolean("auto_save_listened", enabled).apply()
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

    /**
     * Persist the headroom toggle / attenuation and push the new gain to the service. It lands on the
     * next configure (track change or seek), so the level doesn't jump mid-track.
     */
    /**
     * The two booleans this replaced could both be set, which the UI had to paper over. Carry the old
     * state across once: the in-app EQ wins if it was on, otherwise headroom means an external one.
     */
    internal fun migratedEqMode(prefs: android.content.SharedPreferences): String = when {
        prefs.getBoolean("eq_enabled", false) -> EQ_IN_APP
        prefs.getBoolean("eq_headroom_enabled", false) -> EQ_EXTERNAL
        else -> EQ_OFF
    }

    /** Switch equalizer mode: re-attach or drop our EQ, and re-stage the gain for the new mode. */
    fun setEqMode(mode: String, context: Context) {
        eqMode.value = mode
        prefs(context).edit().putString("eq_mode", mode).apply()
        MusicPlaybackService.instance?.syncEqualizer()
        MusicPlaybackService.instance?.applyHeadroomGain()
    }

    /** Band gains persist as a comma-joined string; anything unparseable falls back to a flat curve. */
    private fun parseBands(raw: String?): FloatArray {
        val bands = ch.snepilatch.app.playback.EqualizerHeadroom.BANDS
        val parsed = raw?.split(",")?.mapNotNull { it.trim().toFloatOrNull() } ?: emptyList()
        return if (parsed.size == bands) parsed.toFloatArray() else FloatArray(bands)
    }

    fun setEqBands(bands: FloatArray, context: Context) {
        eqBands.value = bands
        prefs(context).edit().putString("eq_bands", bands.joinToString(",")).apply()
        MusicPlaybackService.instance?.setEqCurve(bands)
    }

    fun setEqHeadroomDb(db: Float, context: Context) {
        eqHeadroomDb.value = db
        prefs(context).edit().putFloat("eq_headroom_db", db).apply()
        MusicPlaybackService.instance?.applyHeadroomGain()
    }

    /** Persist the canvas toggle. PlaybackViewModel.setCanvasEnabled wraps this to also clear the URL. */
    fun setCanvasEnabled(enabled: Boolean, context: Context) {
        canvasEnabled.value = enabled
        prefs(context)
            .edit().putBoolean("canvas_enabled", enabled).apply()
    }
}
