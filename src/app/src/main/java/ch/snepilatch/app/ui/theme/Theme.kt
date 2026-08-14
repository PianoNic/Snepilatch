package ch.snepilatch.app.ui.theme

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.text.TextStyle

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

/**
 * [MaterialTheme] hands every unstyled `Text` its `typography.bodyLarge` — 16sp on a 24sp line with
 * 0.5sp tracking. The screens were all laid out before the app had a theme at all, against the bare
 * [TextStyle.Default] (14sp, natural line height, no tracking), so adopting it grew the type and the
 * rows built around it everywhere at once. Handing [LocalTextStyle] back its old value keeps the
 * colour scheme without the reflow; `MaterialTheme.typography` still reads normally where a screen
 * asks for a style by name.
 */
@Composable
fun SnepilatchTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = SnepilatchColors,
        typography = Typography
    ) {
        CompositionLocalProvider(LocalTextStyle provides TextStyle.Default, content = content)
    }
}
