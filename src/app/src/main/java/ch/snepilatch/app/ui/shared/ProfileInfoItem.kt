package ch.snepilatch.app.ui.shared

import ch.snepilatch.app.ui.theme.SnepilatchWhite
import androidx.compose.material3.*
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import ch.snepilatch.app.ui.theme.SnepilatchLightGray

@Composable
fun ProfileInfoItem(label: String, value: String, icon: ImageVector) {
    ListItem(
        headlineContent = { Text(label, color = SnepilatchWhite) },
        supportingContent = { Text(value, color = SnepilatchLightGray) },
        leadingContent = { Icon(icon, null, tint = SnepilatchLightGray) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}
