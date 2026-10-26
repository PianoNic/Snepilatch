package ch.snepilatch.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.snepilatch.app.R
import ch.snepilatch.app.data.Screen
import ch.snepilatch.app.ui.shared.SettingRow
import ch.snepilatch.app.ui.theme.SnepilatchWhite
import ch.snepilatch.app.viewmodel.PlaybackViewModel

/**
 * Settings as their own place: one row per category, each opening a page of its own. The profile and
 * the accounts stay on the account screen, reached from the drawer, so nothing lives in both.
 */
@Composable
fun SettingsScreen(vm: PlaybackViewModel) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(top = 12.dp, bottom = LocalBottomOverlayHeight.current.value + 16.dp)
    ) {
        Text(
            stringResource(R.string.settings),
            color = SnepilatchWhite,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
        )
        SettingRow(
            title = stringResource(R.string.account_section_playback),
            subtitle = stringResource(R.string.settings_playback_desc),
            icon = Icons.Rounded.MusicNote,
            onClick = { vm.navigateTo(Screen.SETTINGS_PLAYBACK) },
        )
        SettingRow(
            title = stringResource(R.string.downloads),
            subtitle = stringResource(R.string.settings_downloads_desc),
            icon = Icons.Rounded.Download,
            onClick = { vm.navigateTo(Screen.SETTINGS_DOWNLOADS) },
        )
        SettingRow(
            title = stringResource(R.string.account_section_sound),
            subtitle = stringResource(R.string.settings_sound_desc),
            icon = Icons.Rounded.GraphicEq,
            onClick = { vm.navigateTo(Screen.SETTINGS_SOUND) },
        )
        SettingRow(
            title = stringResource(R.string.account_section_interface),
            subtitle = stringResource(R.string.interface_desc),
            icon = Icons.Rounded.Palette,
            onClick = { vm.navigateTo(Screen.INTERFACE) },
        )
        SettingRow(
            title = stringResource(R.string.about),
            subtitle = stringResource(R.string.settings_about_desc),
            icon = Icons.Rounded.Info,
            onClick = { vm.navigateTo(Screen.SETTINGS_ABOUT) },
        )
    }
}

/** One settings category on its own page, under a back arrow and its title. */
@Composable
fun SettingsPage(vm: PlaybackViewModel, title: String, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(top = 12.dp, bottom = LocalBottomOverlayHeight.current.value + 16.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { vm.goBack() }) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back), tint = SnepilatchWhite)
            }
            Text(title, color = SnepilatchWhite, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        content()
    }
}
