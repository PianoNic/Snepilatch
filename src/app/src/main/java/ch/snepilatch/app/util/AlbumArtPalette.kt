package ch.snepilatch.app.util

import android.content.Context
import android.graphics.drawable.BitmapDrawable
import androidx.compose.ui.graphics.Color
import androidx.palette.graphics.Palette
import ch.snepilatch.app.data.ThemeColors
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult

/**
 * Loads an image from a URL and extracts a [ThemeColors] palette suitable for the
 * dynamic now-playing theme. Filters out overly bright, dark, green-dominant, and
 * neon colors so the UI stays readable.
 *
 * Returns null if the image fails to load or if no usable swatches are produced.
 */
suspend fun extractThemeColorsFromArt(context: Context, imageUrl: String): ThemeColors? {
    val loader = ImageLoader(context)
    val request = ImageRequest.Builder(context)
        .data(imageUrl)
        .allowHardware(false)
        .build()
    val result = loader.execute(request)
    if (result !is SuccessResult) return null
    val bitmap = (result.drawable as? BitmapDrawable)?.bitmap ?: return null
    val palette = Palette.from(bitmap).generate()

    val defaultGray = 0xFFB3B3B3.toInt()
    val candidates = listOfNotNull(
        palette.vibrantSwatch?.rgb,
        palette.lightVibrantSwatch?.rgb,
        palette.darkVibrantSwatch?.rgb,
        palette.mutedSwatch?.rgb,
        palette.lightMutedSwatch?.rgb,
        palette.darkMutedSwatch?.rgb
    )
    val primary = candidates.firstOrNull { isUsablePaletteColor(it) } ?: defaultGray
    val darkMuted = palette.getDarkMutedColor(0xFF282828.toInt())
    val muted = palette.getMutedColor(0xFF282828.toInt())

    return ThemeColors(
        primary = Color(primary),
        primaryDark = Color(primary).copy(alpha = 0.7f),
        surface = Color(darkMuted),
        gradientTop = Color(muted).copy(alpha = 0.8f),
        gradientBottom = Color(0xFF121212)
    )
}

/**
 * Filters out palette swatches that don't read well against the now-playing UI.
 * Rejects colors that are too bright, too dark, green-dominant, or neon.
 */
private fun isUsablePaletteColor(color: Int): Boolean {
    val r = (color shr 16) and 0xFF
    val g = (color shr 8) and 0xFF
    val b = color and 0xFF
    val brightness = (r * 299 + g * 587 + b * 114) / 1000
    if (brightness > 220 || brightness < 40) return false
    val max = maxOf(r, g, b).toFloat()
    val min = minOf(r, g, b).toFloat()
    val sat = if (max == 0f) 0f else (max - min) / max
    // Reject green-dominant hues
    if (sat > 0.3f && g == max.toInt() && g > 80) return false
    // Reject neon / overly saturated
    if (sat > 0.85f && brightness > 150) return false
    return true
}
