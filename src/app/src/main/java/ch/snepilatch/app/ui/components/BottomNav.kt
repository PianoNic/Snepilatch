package ch.snepilatch.app.ui.components

import ch.snepilatch.app.ui.theme.SpotifyWhite
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.snepilatch.app.R
import ch.snepilatch.app.data.Screen
import ch.snepilatch.app.ui.theme.SpotifyBlack
import ch.snepilatch.app.ui.theme.SpotifyLightGray
import ch.snepilatch.app.viewmodel.PlaybackViewModel
import coil.compose.AsyncImage
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint

@Composable
fun BottomNav(screen: Screen, vm: PlaybackViewModel, hazeState: HazeState) {
    val account by vm.account.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .hazeEffect(
                state = hazeState,
                style = HazeStyle(
                    backgroundColor = SpotifyBlack,
                    blurRadius = 24.dp,
                    tints = listOf(HazeTint(SpotifyBlack.copy(alpha = 0.7f)))
                )
            )
    ) {
        NavigationBar(
            containerColor = Color.Transparent,
            contentColor = SpotifyWhite,
            tonalElevation = 0.dp,
        ) {
            data class NavItem(val s: Screen, val icon: ImageVector, val label: String)
            val items = listOf(
                NavItem(Screen.HOME, Icons.Rounded.Home, stringResource(R.string.nav_home)),
                NavItem(Screen.SEARCH, Icons.Rounded.Search, stringResource(R.string.nav_search)),
                NavItem(Screen.LIBRARY, Icons.AutoMirrored.Rounded.QueueMusic, stringResource(R.string.nav_library)),
                NavItem(Screen.ACCOUNT, Icons.Rounded.Person, stringResource(R.string.nav_account))
            )
            val accountDesc = stringResource(R.string.account_image)
            items.forEach { nav ->
                val selected = screen == nav.s
                NavigationBarItem(
                    selected = selected,
                    onClick = { vm.navigateToTab(nav.s) },
                    icon = {
                        if (nav.s == Screen.ACCOUNT && account.profileImageUrl != null) {
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(CircleShape)
                                    .then(
                                        if (selected) Modifier.border(2.dp, SpotifyWhite, CircleShape)
                                        else Modifier
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                AsyncImage(
                                    model = account.profileImageUrl,
                                    contentDescription = accountDesc,
                                    modifier = Modifier.size(24.dp).clip(CircleShape),
                                    contentScale = ContentScale.Crop
                                )
                            }
                        } else {
                            Icon(nav.icon, nav.label, modifier = Modifier.size(24.dp))
                        }
                    },
                    label = { Text(nav.label, fontSize = 11.sp) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = SpotifyWhite,
                        selectedTextColor = SpotifyWhite,
                        unselectedIconColor = SpotifyLightGray,
                        unselectedTextColor = SpotifyLightGray,
                        indicatorColor = Color.Transparent
                    )
                )
            }
        }
    }
}
