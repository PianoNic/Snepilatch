package ch.snepilatch.app.ui.screens

import android.content.Context
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.snepilatch.app.R
import androidx.compose.ui.graphics.vector.ImageVector
import ch.snepilatch.app.data.PlayerShortcut
import ch.snepilatch.app.logic.shared.AppSettings
import ch.snepilatch.app.logic.shared.GestureSettings
import ch.snepilatch.app.logic.shared.ThemeController
import ch.snepilatch.app.ui.shared.RadioOption
import ch.snepilatch.app.ui.shared.RadioPickerDialog
import ch.snepilatch.app.ui.shared.SettingRow
import ch.snepilatch.app.ui.shared.SettingsSectionHeader
import ch.snepilatch.app.ui.shared.SettingToggleRow
import ch.snepilatch.app.ui.shared.playerShortcutIcon
import ch.snepilatch.app.ui.shared.playerShortcutTitle
import ch.snepilatch.app.ui.theme.SnepilatchWhite
import ch.snepilatch.app.viewmodel.PlaybackViewModel

/** The appearance and behaviour settings, moved off the account tab onto their own page (#828). */
@Composable
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
                stringResource(R.string.account_section_interface),
                color = SnepilatchWhite,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )
        }

        LanguageSection(vm, context)
        Spacer(Modifier.height(24.dp))
        BehaviorSection(context, animatedPrimary)
        Spacer(Modifier.height(24.dp))
        AppearanceSection(vm, context, animatedPrimary)
        Spacer(Modifier.height(24.dp))
        NotificationButtonsSection(context)
    }
}

@Composable
private fun LanguageSection(vm: PlaybackViewModel, context: Context) {
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
}

@Composable
private fun BehaviorSection(context: Context, accent: Color) {
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

    val swipeDownOpensQueue by GestureSettings.swipeDownOpensQueue.collectAsState()
    SettingToggleRow(
        title = stringResource(R.string.mini_player_swipe_queue),
        checked = swipeDownOpensQueue,
        onCheckedChange = { GestureSettings.setSwipeDownOpensQueue(it, context) },
        accent = accent,
        subtitle = stringResource(if (swipeDownOpensQueue) R.string.state_on else R.string.state_off),
        icon = Icons.AutoMirrored.Rounded.QueueMusic,
    )

    val playerShortcut by AppSettings.playerShortcut.collectAsState()
    ShortcutPickerRow(
        title = stringResource(R.string.player_shortcut),
        description = stringResource(R.string.player_shortcut_desc),
        icon = Icons.Rounded.TouchApp,
        current = playerShortcut,
        options = PlayerShortcut.entries,
    ) { AppSettings.setPlayerShortcut(it, context) }

    // The row swipes only offer what acts on the swiped track (#578).
    val swipeLeft by GestureSettings.swipeLeftAction.collectAsState()
    ShortcutPickerRow(
        title = stringResource(R.string.swipe_left_action),
        description = stringResource(R.string.swipe_action_desc),
        icon = Icons.Rounded.SwipeLeft,
        current = swipeLeft,
        options = PlayerShortcut.perTrack,
    ) { GestureSettings.setSwipeActions(context, left = it) }
    val swipeRight by GestureSettings.swipeRightAction.collectAsState()
    ShortcutPickerRow(
        title = stringResource(R.string.swipe_right_action),
        description = stringResource(R.string.swipe_action_desc),
        icon = Icons.Rounded.SwipeRight,
        current = swipeRight,
        options = PlayerShortcut.perTrack,
    ) { GestureSettings.setSwipeActions(context, right = it) }
}

/** A setting row that picks one [PlayerShortcut] out of [options], shown with its title and glyph. */
@Composable
private fun ShortcutPickerRow(
    title: String,
    description: String,
    icon: ImageVector,
    current: PlayerShortcut,
    options: List<PlayerShortcut>,
    onSelect: (PlayerShortcut) -> Unit,
) {
    var showPicker by remember { mutableStateOf(false) }
    SettingRow(
        title = title,
        subtitle = stringResource(playerShortcutTitle(current)),
        icon = icon,
        onClick = { showPicker = true },
    )
    if (showPicker) {
        RadioPickerDialog(
            title = title,
            description = description,
            options = options.map { RadioOption(it.id, stringResource(playerShortcutTitle(it)), icon = playerShortcutIcon(it)) },
            selected = current.id,
            onSelect = { id ->
                onSelect(options.first { it.id == id })
                showPicker = false
            },
            onDismiss = { showPicker = false },
        )
    }
}

@Composable
private fun AppearanceSection(vm: PlaybackViewModel, context: Context, accent: Color) {
    SettingsSectionHeader(stringResource(R.string.appearance))
    val canvasOn by AppSettings.canvasEnabled.collectAsState()
    SettingToggleRow(
        title = stringResource(R.string.canvas_background),
        checked = canvasOn,
        onCheckedChange = { vm.setCanvasEnabled(it, context) },
        accent = accent,
        subtitle = if (canvasOn) stringResource(R.string.canvas_on) else stringResource(R.string.canvas_off),
        icon = Icons.Rounded.PlayCircle,
    )
    val gradientBg by AppSettings.playerGradientBg.collectAsState()
    SettingToggleRow(
        title = stringResource(R.string.gradient_background),
        checked = gradientBg,
        onCheckedChange = { AppSettings.setPlayerGradientBg(it, context) },
        accent = accent,
        subtitle = stringResource(if (gradientBg) R.string.gradient_bg_on else R.string.gradient_bg_off),
        icon = Icons.Rounded.Gradient,
    )
}

@Composable
private fun NotificationButtonsSection(context: Context) {
    SettingsSectionHeader(stringResource(R.string.account_section_notifications))
    val labels = mapOf(
        "like" to stringResource(R.string.notif_like),
        "shuffle" to stringResource(R.string.notif_shuffle),
        "repeat" to stringResource(R.string.notif_repeat),
    )
    val descriptions = mapOf(
        "like" to stringResource(R.string.notif_like_short_desc),
        "shuffle" to stringResource(R.string.notif_shuffle_desc),
        "repeat" to stringResource(R.string.notif_repeat_desc),
    )
    val options = labels.map { (type, label) -> RadioOption(type, label, descriptions[type]) }

    val leftButton by AppSettings.notificationLeftButton.collectAsState()
    NotificationButtonRow(
        title = stringResource(R.string.notification_left_button),
        pickerTitle = stringResource(R.string.notification_button_left),
        current = leftButton,
        label = labels[leftButton] ?: leftButton,
        options = options,
    ) { AppSettings.setNotificationButtons(context, left = it) }

    val rightButton by AppSettings.notificationRightButton.collectAsState()
    NotificationButtonRow(
        title = stringResource(R.string.notification_right_button),
        pickerTitle = stringResource(R.string.notification_button_right),
        current = rightButton,
        label = labels[rightButton] ?: rightButton,
        options = options,
    ) { AppSettings.setNotificationButtons(context, right = it) }
}

@Composable
private fun NotificationButtonRow(
    title: String,
    pickerTitle: String,
    current: String,
    label: String,
    options: List<RadioOption>,
    onSelect: (String) -> Unit,
) {
    var showPicker by remember { mutableStateOf(false) }
    SettingRow(title = title, subtitle = label, icon = Icons.Rounded.Notifications, onClick = { showPicker = true })
    if (showPicker) {
        RadioPickerDialog(
            title = pickerTitle,
            options = options,
            selected = current,
            onSelect = {
                onSelect(it)
                showPicker = false
            },
            onDismiss = { showPicker = false }
        )
    }
}
