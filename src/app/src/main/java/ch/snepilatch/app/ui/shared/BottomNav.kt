package ch.snepilatch.app.ui.shared

import ch.snepilatch.app.ui.theme.SnepilatchWhite
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.DownloadForOffline
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.snepilatch.app.R
import ch.snepilatch.app.data.NavSlot
import ch.snepilatch.app.data.Screen
import ch.snepilatch.app.logic.shared.NavSettings
import ch.snepilatch.app.ui.theme.SnepilatchBlack
import ch.snepilatch.app.ui.theme.SnepilatchLightGray
import ch.snepilatch.app.viewmodel.PlaybackViewModel
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint

@Composable
fun BottomNav(screen: Screen, vm: PlaybackViewModel, hazeState: HazeState) {
    val slot by NavSettings.navSlot.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .hazeEffect(
                state = hazeState,
                style = HazeStyle(
                    backgroundColor = SnepilatchBlack,
                    blurRadius = 24.dp,
                    tints = listOf(HazeTint(SnepilatchBlack.copy(alpha = 0.7f)))
                )
            )
    ) {
        // Air above the buttons, inside the blur, so the content scrolls under something rather
        // than straight into the icons.
        Spacer(Modifier.height(6.dp))
        // Hand rolled rather than a NavigationBar: its 80dp height and the gap between its icon and
        // label slots are both fixed, and this bar wants a bigger icon in less height.
        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .height(58.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            data class NavItem(val s: Screen, val icon: ImageVector, val label: String)
            val items = listOfNotNull(
                NavItem(Screen.HOME, Icons.Rounded.Home, stringResource(R.string.nav_home)),
                NavItem(Screen.SEARCH, Icons.Rounded.Search, stringResource(R.string.nav_search)),
                NavItem(Screen.LIBRARY, Icons.AutoMirrored.Rounded.QueueMusic, stringResource(R.string.nav_library)),
                slot.screen?.let { target -> slotLook(slot)?.let { (icon, label) -> NavItem(target, icon, stringResource(label)) } },
            )
            items.forEach { nav ->
                val selected = screen == nav.s
                val tint = if (selected) SnepilatchWhite else SnepilatchLightGray
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { vm.navigateToTab(nav.s) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(nav.icon, nav.label, modifier = Modifier.size(32.dp), tint = tint)
                    Spacer(Modifier.height(2.dp))
                    Text(nav.label, fontSize = 11.sp, color = tint)
                }
            }
        }
    }
}

/** The glyph and label of the bar's fourth button, or null when [slot] leaves the bar at three. */
internal fun slotLook(slot: NavSlot): Pair<ImageVector, Int>? = when (slot) {
    NavSlot.NONE -> null
    NavSlot.SETTINGS -> Icons.Rounded.Settings to R.string.settings
    NavSlot.DOWNLOADS -> Icons.Rounded.DownloadForOffline to R.string.downloads
    NavSlot.FRIEND_ACTIVITY -> Icons.Rounded.Groups to R.string.friend_activity
    NavSlot.WHATS_NEW -> Icons.Rounded.Campaign to R.string.whats_new_title
}
