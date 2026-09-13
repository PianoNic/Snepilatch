package ch.snepilatch.app.ui.shared

import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import ch.snepilatch.app.ui.theme.SnepilatchLightGray
import ch.snepilatch.app.ui.theme.SnepilatchWhite

/** One settings entry: [title] over an optional [subtitle], an [icon] in front, a chevron when it [onClick]s somewhere. */
@Composable
fun SettingRow(title: String, subtitle: String? = null, icon: ImageVector? = null, onClick: (() -> Unit)? = null) {
    ListItem(
        headlineContent = { Text(title, color = SnepilatchWhite) },
        supportingContent = subtitle?.let { { Text(it, color = SnepilatchLightGray) } },
        leadingContent = icon?.let { { Icon(it, null, tint = SnepilatchLightGray) } },
        trailingContent = onClick?.let { { Icon(Icons.Rounded.ChevronRight, null, tint = SnepilatchLightGray) } },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
    )
}

/** A settings entry whose trailing control is a switch tinted with the album [accent]. */
@Composable
fun SettingToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    accent: Color,
    subtitle: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    ListItem(
        headlineContent = { Text(title, color = SnepilatchWhite) },
        supportingContent = subtitle?.let { { Text(it, color = SnepilatchLightGray) } },
        leadingContent = icon?.let { { Icon(it, null, tint = SnepilatchLightGray) } },
        trailingContent = {
            Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled, colors = settingSwitchColors(accent))
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

/** The switch tint every toggle in the app shares: the album accent on, light grey off. */
@Composable
fun settingSwitchColors(accent: Color): SwitchColors = SwitchDefaults.colors(
    checkedThumbColor = accent,
    checkedTrackColor = accent.copy(alpha = 0.5f),
    uncheckedThumbColor = SnepilatchLightGray,
    uncheckedTrackColor = SnepilatchLightGray.copy(alpha = 0.3f),
)
