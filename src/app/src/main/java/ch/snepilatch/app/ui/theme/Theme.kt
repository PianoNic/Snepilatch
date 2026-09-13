package ch.snepilatch.app.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import ch.snepilatch.app.viewmodel.ThemeController

/**
 * The app paints itself dark everywhere, so the Material scheme is dark-only: no dynamic colour and
 * no system-theme switch, both of which would tint components against a background that never changes.
 * Its primary is the accent [ThemeController] extracts from the playing album art, so every Material
 * default (buttons, progress bars, slider ticks, text cursors, selection) follows the same colour the
 * screens tint themselves with.
 */
private fun schemeWith(accent: Color) = darkColorScheme(
    primary = accent,
    onPrimary = SnepilatchBlack,
    secondary = SnepilatchLightGray,
    onSecondary = SnepilatchBlack,
    background = SnepilatchBlack,
    onBackground = SnepilatchWhite,
    surface = SnepilatchBlack,
    onSurface = SnepilatchWhite,
    surfaceVariant = SnepilatchGray,
    onSurfaceVariant = SnepilatchLightGray,
    surfaceContainer = SnepilatchElevated,
    surfaceContainerHigh = SnepilatchElevated,
    surfaceContainerHighest = SnepilatchGray,
    surfaceContainerLow = SnepilatchBlack,
    surfaceContainerLowest = SnepilatchBlack,
    outline = SnepilatchLightGray,
    outlineVariant = SnepilatchGray,
    error = SnepilatchError,
    onError = SnepilatchWhite
)

/**
 * [MaterialTheme] hands every unstyled `Text` its `typography.bodyLarge`, 16sp on a 24sp line with
 * 0.5sp tracking. The screens were all laid out before the app had a theme at all, against the bare
 * [TextStyle.Default] (14sp, natural line height, no tracking), so adopting it grew the type and the
 * rows built around it everywhere at once. Handing [LocalTextStyle] back its old value keeps the
 * colour scheme without the reflow; `MaterialTheme.typography` still reads normally where a screen
 * asks for a style by name.
 */
@Composable
fun SnepilatchTheme(content: @Composable () -> Unit) {
    val palette by ThemeController.themeColors.collectAsState()
    val accent by animateColorAsState(palette.primary, tween(800), label = "themeAccent")
    MaterialTheme(
        colorScheme = schemeWith(accent),
        typography = Typography
    ) {
        CompositionLocalProvider(LocalTextStyle provides TextStyle.Default, content = content)
    }
}
