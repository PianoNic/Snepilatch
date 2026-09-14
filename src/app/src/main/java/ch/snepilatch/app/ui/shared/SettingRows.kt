package ch.snepilatch.app.ui.shared

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.snepilatch.app.R
import ch.snepilatch.app.ui.theme.SnepilatchGray
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

@Composable
fun SettingsSectionHeader(title: String) {
    HorizontalDivider(color = SnepilatchGray, modifier = Modifier.padding(horizontal = 16.dp))
    Text(
        title,
        color = SnepilatchWhite,
        fontSize = 16.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp)
    )
}

data class RadioOption(
    val value: String,
    val label: String,
    val supportingText: String? = null,
    val icon: ImageVector? = null,
)

/** Single radio-select settings dialog shared by the Account and Appearance pickers. */
@Composable
fun RadioPickerDialog(
    title: String,
    description: String? = null,
    options: List<RadioOption>,
    selected: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    TightAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, color = SnepilatchWhite) },
        text = {
            Column {
                if (description != null) {
                    Text(description, color = SnepilatchLightGray, fontSize = 13.sp)
                    Spacer(Modifier.height(12.dp))
                }
                options.forEach { opt ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(opt.value) }
                            .padding(vertical = 12.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selected == opt.value,
                            onClick = { onSelect(opt.value) },
                        )
                        Spacer(Modifier.width(8.dp))
                        opt.icon?.let {
                            Icon(it, null, tint = SnepilatchWhite, modifier = Modifier.size(24.dp))
                            Spacer(Modifier.width(12.dp))
                        }
                        if (opt.supportingText != null) {
                            Column {
                                Text(opt.label, color = SnepilatchWhite, fontSize = 15.sp)
                                Text(opt.supportingText, color = SnepilatchLightGray, fontSize = 12.sp)
                            }
                        } else {
                            Text(opt.label, color = SnepilatchWhite, fontSize = 15.sp)
                        }
                    }
                }
            }
        },
        containerColor = SnepilatchGray,
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel), color = SnepilatchLightGray)
            }
        }
    )
}
