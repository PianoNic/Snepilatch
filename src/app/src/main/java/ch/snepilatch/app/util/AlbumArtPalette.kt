package ch.snepilatch.app.util

import android.content.Context
import android.graphics.drawable.BitmapDrawable
import androidx.compose.ui.graphics.Color
import androidx.palette.graphics.Palette
import ch.snepilatch.app.data.ThemeColors
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
    // Reuse the app's shared Coil singleton (same instance the UI already populated) instead of a
    // fresh ImageLoader with an empty cache, and downsample to 112px — Palette's internal resize
    // target (resizeBitmapArea 112x112) — so we skip a wasted full-res software decode per skip.
    val loader = coil.Coil.imageLoader(context)
    val request = ImageRequest.Builder(context)
        .data(imageUrl)
        .size(112)
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

/** The three colours a home card lights itself with: its ground, and two glows off the artwork. */
data class CardColors(val base: Color, val glow: Color, val counterGlow: Color)

/**
 * Reads the cover and returns the colours to bloom the card with. The ground is the artwork's
 * darkest muted shade so text stays readable on it, and the two glows are its most saturated
 * shades, which is what gives the card light coming out of the cover rather than a flat ramp.
 *
 * Returns null if the image fails to load, and the caller falls back to the colour the feed states.
 */
suspend fun extractCardColorsFromArt(context: Context, imageUrl: String): CardColors? {
    val loader = coil.Coil.imageLoader(context)
    val request = ImageRequest.Builder(context)
        .data(imageUrl)
        .size(112)
        .allowHardware(false)
        .build()
    val result = loader.execute(request)
    if (result !is SuccessResult) return null
    val bitmap = (result.drawable as? BitmapDrawable)?.bitmap ?: return null
    val palette = Palette.from(bitmap).generate()
    // The card takes its ground from the cover's outer ring, not from a swatch: these covers are
    // built on a solid colour that runs to the edge, and that edge is the colour the card is meant
    // to be. A swatch would pick something out of the photo in the middle instead.
    val base = edgeColor(bitmap) ?: palette.darkMutedSwatch?.rgb ?: return null
    val glow = palette.vibrantSwatch?.rgb
        ?: palette.lightVibrantSwatch?.rgb
        ?: palette.mutedSwatch?.rgb
        ?: base
    val counter = palette.darkVibrantSwatch?.rgb
        ?: palette.mutedSwatch?.rgb
        ?: palette.lightMutedSwatch?.rgb
        ?: glow
    return CardColors(Color(mutedGround(base)), Color(glow), Color(counter))
}

/** The average of the cover's outermost ring, which on a built cover is its background colour. */
private fun edgeColor(bitmap: android.graphics.Bitmap): Int? {
    val w = bitmap.width
    val h = bitmap.height
    val ring = maxOf(2, minOf(w, h) / 20)
    if (w <= ring * 2 || h <= ring * 2) return null
    var r = 0L
    var g = 0L
    var b = 0L
    var n = 0
    fun take(pixel: Int) {
        r += (pixel shr 16) and 0xFF
        g += (pixel shr 8) and 0xFF
        b += pixel and 0xFF
        n++
    }
    for (x in 0 until w) {
        for (y in 0 until ring) {
            take(bitmap.getPixel(x, y))
            take(bitmap.getPixel(x, h - 1 - y))
        }
    }
    for (y in ring until h - ring) {
        for (x in 0 until ring) {
            take(bitmap.getPixel(x, y))
            take(bitmap.getPixel(w - 1 - x, y))
        }
    }
    if (n == 0) return null
    return (0xFF shl 24) or ((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (b / n).toInt()
}

/**
 * The dark, calm version of a colour. Covers are bright by design, and a card filled with the raw
 * edge colour would glare and swallow its own text, so saturation and lightness are capped.
 */
private fun mutedGround(color: Int): Int {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(color, hsv)
    hsv[1] = minOf(hsv[1], 0.55f)
    hsv[2] = minOf(hsv[2], 0.34f)
    return android.graphics.Color.HSVToColor(hsv)
}
