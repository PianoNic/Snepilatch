package ch.snepilatch.app.viewmodel

import android.content.Context
import ch.snepilatch.app.data.ThemeColors
import ch.snepilatch.app.util.LokiLogger
import ch.snepilatch.app.util.extractThemeColorsFromArt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Process-scoped store for the album-art-derived accent palette (like [Navigator] / [AppSettings]).
 * [PlaybackViewModel] feeds it the current track's art via [updateFromArt] on each track change; the
 * many screens that tint themselves read [themeColors]. Extraction runs on its own IO scope, off the
 * playback path.
 */
object ThemeController {

    private const val TAG = "ThemeController"

    val themeColors = MutableStateFlow(ThemeColors())

    private var lastPaletteUrl: String? = null

    @Volatile private var appContext: Context? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun setContext(context: Context) {
        appContext = context.applicationContext
    }

    /** Extract a fresh palette only when the art URL actually changed since the last extraction. */
    fun updateFromArt(art: String?) {
        if (art != null && art != lastPaletteUrl) {
            lastPaletteUrl = art
            extractColorsFromArt(art)
        }
    }

    private fun extractColorsFromArt(imageUrl: String) {
        val ctx = appContext ?: return
        scope.launch {
            try {
                val colors = extractThemeColorsFromArt(ctx, imageUrl)
                if (colors != null) {
                    themeColors.value = colors
                    LokiLogger.d(TAG, "Palette updated from $imageUrl")
                }
            } catch (e: Exception) {
                LokiLogger.e(TAG, "extractColors", e)
            }
        }
    }
}
