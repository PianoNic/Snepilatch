package ch.snepilatch.app.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.snepilatch.app.R
import ch.snepilatch.app.logic.shared.AppSettings
import ch.snepilatch.app.logic.shared.ThemeController
import ch.snepilatch.app.ui.shared.RadioOption
import ch.snepilatch.app.ui.shared.RadioPickerDialog
import ch.snepilatch.app.ui.shared.SettingRow
import ch.snepilatch.app.ui.shared.SettingsSectionHeader
import ch.snepilatch.app.ui.shared.SettingToggleRow
import ch.snepilatch.app.ui.theme.SnepilatchWhite
import ch.snepilatch.app.viewmodel.PlaybackViewModel

@Composable
@Suppress("LongMethod")
fun InterfaceScreen(vm: PlaybackViewModel) {
    val context = LocalContext.current
    val theme by ThemeController.themeColors.collectAsState()
    val animatedPrimary by animateColorAsState(theme.primary, tween(800), label = "interfacePrimary")

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(top = 12.dp, bottom = LocalBottomOverlayHeight.current.value + 16.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { vm.goBack() }) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back), tint = SnepilatchWhite)
            }
            Text(
                stringResource(R.string.account_section_appearance),
                color = SnepilatchWhite,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )
        }

        SettingsSectionHeader(stringResource(R.string.language))

        val appLanguage by AppSettings.appLanguage.collectAsState()
        var showLanguagePicker by remember { mutableStateOf(false) }
        val systemDefaultLabel = stringResource(R.string.language_system_default)
        val languages = remember(systemDefaultLabel) {
            listOf(
                "system" to systemDefaultLabel,
                "en" to "English",
                "de" to "Deutsch",
                "ru" to "Русский",
                "gsw" to "Schwiizerdütsch"
            )
        }
        val currentLanguageLabel = languages.find { it.first == appLanguage }?.second ?: systemDefaultLabel
        SettingRow(
            title = stringResource(R.string.language),
            subtitle = currentLanguageLabel,
            icon = Icons.Rounded.Language,
            onClick = { showLanguagePicker = true },
        )
        if (showLanguagePicker) {
            RadioPickerDialog(
                title = stringResource(R.string.language),
                options = languages.map { RadioOption(it.first, it.second) },
                selected = appLanguage,
                onSelect = {
                    vm.setAppLanguage(it, context)
                    showLanguagePicker = false
                },
                onDismiss = { showLanguagePicker = false }
            )
        }

        Spacer(Modifier.height(24.dp))
        SettingsSectionHeader(stringResource(R.string.behavior))

        val lyricsAnim by AppSettings.lyricsAnimDirection.collectAsState()
        var showLyricsPicker by remember { mutableStateOf(false) }
        val lyricsLabel = if (lyricsAnim == "horizontal") stringResource(R.string.lyrics_horizontal) else stringResource(R.string.lyrics_vertical)
        SettingRow(
            title = stringResource(R.string.lyrics_animation),
            subtitle = lyricsLabel,
            icon = Icons.Rounded.MusicNote,
            onClick = { showLyricsPicker = true },
        )
        if (showLyricsPicker) {
            RadioPickerDialog(
                title = stringResource(R.string.lyrics_animation),
                description = stringResource(R.string.lyrics_anim_desc),
                options = listOf(
                    RadioOption("vertical", stringResource(R.string.lyrics_vertical)),
                    RadioOption("horizontal", stringResource(R.string.lyrics_horizontal))
                ),
                selected = lyricsAnim,
                onSelect = {
                    AppSettings.setLyricsAnimDirection(it, context)
                    showLyricsPicker = false
                },
                onDismiss = { showLyricsPicker = false }
            )
        }

        Spacer(Modifier.height(24.dp))
        SettingsSectionHeader(stringResource(R.string.appearance))

        val canvasOn by AppSettings.canvasEnabled.collectAsState()
        SettingToggleRow(
            title = stringResource(R.string.canvas_background),
            checked = canvasOn,
            onCheckedChange = { vm.setCanvasEnabled(it, context) },
            accent = animatedPrimary,
            subtitle = if (canvasOn) stringResource(R.string.canvas_on) else stringResource(R.string.canvas_off),
            icon = Icons.Rounded.PlayCircle,
        )

        val gradientBg by AppSettings.playerGradientBg.collectAsState()
        SettingToggleRow(
            title = stringResource(R.string.gradient_background),
            checked = gradientBg,
            onCheckedChange = { AppSettings.setPlayerGradientBg(it, context) },
            accent = animatedPrimary,
            subtitle = stringResource(if (gradientBg) R.string.gradient_bg_on else R.string.gradient_bg_off),
            icon = Icons.Rounded.Gradient,
        )

        Spacer(Modifier.height(24.dp))
        SettingsSectionHeader(stringResource(R.string.account_section_notifications))

        // Notification button options
        val notifLikeLabel = stringResource(R.string.notif_like)
        val notifShuffleLabel = stringResource(R.string.notif_shuffle)
        val notifRepeatLabel = stringResource(R.string.notif_repeat)
        val notifLikeDesc = stringResource(R.string.notif_like_short_desc)
        val notifShuffleDesc = stringResource(R.string.notif_shuffle_desc)
        val notifRepeatDesc = stringResource(R.string.notif_repeat_desc)
        val buttonOptions = remember(
            notifLikeLabel, notifShuffleLabel, notifRepeatLabel,
            notifLikeDesc, notifShuffleDesc, notifRepeatDesc
        ) {
            listOf(
                "like" to notifLikeLabel to notifLikeDesc,
                "shuffle" to notifShuffleLabel to notifShuffleDesc,
                "repeat" to notifRepeatLabel to notifRepeatDesc
            )
        }
        fun buttonLabel(type: String) = when (type) {
            "like" -> notifLikeLabel
            "shuffle" -> notifShuffleLabel
            "repeat" -> notifRepeatLabel
            else -> type
        }
        val notifRadioOptions = buttonOptions.map { (pair, desc) ->
            RadioOption(pair.first, pair.second, desc)
        }

        // Left notification button
        val leftButton by AppSettings.notificationLeftButton.collectAsState()
        var showLeftPicker by remember { mutableStateOf(false) }
        SettingRow(
            title = stringResource(R.string.notification_left_button),
            subtitle = buttonLabel(leftButton),
            icon = Icons.Rounded.Notifications,
            onClick = { showLeftPicker = true },
        )
        if (showLeftPicker) {
            RadioPickerDialog(
                title = stringResource(R.string.notification_button_left),
                options = notifRadioOptions,
                selected = leftButton,
                onSelect = {
                    AppSettings.setNotificationLeftButton(it, context)
                    showLeftPicker = false
                },
                onDismiss = { showLeftPicker = false }
            )
        }

        // Right notification button
        val rightButton by AppSettings.notificationRightButton.collectAsState()
        var showRightPicker by remember { mutableStateOf(false) }
        SettingRow(
            title = stringResource(R.string.notification_right_button),
            subtitle = buttonLabel(rightButton),
            icon = Icons.Rounded.Notifications,
            onClick = { showRightPicker = true },
        )
        if (showRightPicker) {
            RadioPickerDialog(
                title = stringResource(R.string.notification_button_right),
                options = notifRadioOptions,
                selected = rightButton,
                onSelect = {
                    AppSettings.setNotificationRightButton(it, context)
                    showRightPicker = false
                },
                onDismiss = { showRightPicker = false }
            )
        }
    }
}
