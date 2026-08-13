package ch.snepilatch.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

/**
 * The app paints itself dark everywhere, so the Material scheme is dark-only — no dynamic colour and
 * no system-theme switch, both of which would tint components against a background that never changes.
 *
 * Without this the app had no [MaterialTheme] at all and Material fell back to its default, which is
 * the *light* scheme: dialogs and text fields drew near-black text on our dark surfaces.
 */
private val SnepilatchColors = darkColorScheme(
    primary = SpfyGreen,
    onPrimary = SpfyBlack,
    secondary = SpfyLightGray,
    onSecondary = SpfyBlack,
    background = SpfyBlack,
    onBackground = SpfyWhite,
    surface = SpfyBlack,
    onSurface = SpfyWhite,
    surfaceVariant = SpfyGray,
    onSurfaceVariant = SpfyLightGray,
    surfaceContainer = SpfyElevated,
    surfaceContainerHigh = SpfyElevated,
    surfaceContainerHighest = SpfyCardBg,
    surfaceContainerLow = SpfyDarkGray,
    surfaceContainerLowest = SpfyBlack,
    outline = SpfyLightGray,
    outlineVariant = SpfyGray,
    error = SpfyError,
    onError = SpfyWhite
)

@Composable
fun SnepilatchTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = SnepilatchColors,
        typography = Typography,
        content = content
    )
}
