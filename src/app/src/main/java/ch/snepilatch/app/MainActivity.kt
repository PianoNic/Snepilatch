package ch.snepilatch.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import ch.snepilatch.app.playback.MediaButtonReceiver
import ch.snepilatch.app.playback.MusicPlaybackService
import ch.snepilatch.app.playback.SessionHolder
import ch.snepilatch.app.ui.components.UpdateDialog
import ch.snepilatch.app.ui.screens.LoadingScreen
import ch.snepilatch.app.ui.screens.SpfyApp
import ch.snepilatch.app.ui.screens.SpfyLoginScreen
import ch.snepilatch.app.ui.theme.SpfyBlack
import ch.snepilatch.app.util.UpdateChannel
import ch.snepilatch.app.util.UpdateInfo
import ch.snepilatch.app.util.UpdateService
import ch.snepilatch.app.util.loadCookies
import ch.snepilatch.app.download.DownloadFolder
import ch.snepilatch.app.download.DownloadNotifier
import ch.snepilatch.app.download.Downloads
import ch.snepilatch.app.viewmodel.AppSettings
import ch.snepilatch.app.viewmodel.PlaybackViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : ComponentActivity() {
    private val pendingDeepLink = MutableStateFlow<Uri?>(null)

    /**
     * Set when the download notification is tapped. A flow rather than a read of `intent`: the
     * notification's intent is SINGLE_TOP against a singleTop Activity, so with the app already open it
     * arrives at [onNewIntent] and `getIntent()` still holds whatever launched the Activity.
     */
    private val pendingOpenDownloads = MutableStateFlow(false)

    companion object {
        // Process-scoped so the update check fires once per app start, not per Activity recreation.
        private val updateChecked = AtomicBoolean(false)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDeepLinkIntent(intent)
    }

    private fun handleDeepLinkIntent(intent: Intent) {
        if (intent.action == Intent.ACTION_VIEW) {
            pendingDeepLink.value = intent.data
        }
        // Outside the ACTION_VIEW branch: the notification launches the app's own launcher intent,
        // which is ACTION_MAIN.
        if (intent.getBooleanExtra(DownloadNotifier.EXTRA_OPEN_DOWNLOADS, false)) {
            intent.removeExtra(DownloadNotifier.EXTRA_OPEN_DOWNLOADS)
            pendingOpenDownloads.value = true
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            // App is actually closing (not config change) — kill everything
            MusicPlaybackService.instance?.let { svc ->
                svc.player.stop()
                svc.stopSelf()
            }
            SessionHolder.clear()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleDeepLinkIntent(intent)
        volumeControlStream = android.media.AudioManager.STREAM_MUSIC
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )

        // Request notification permission for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
            }
        }

        setContent {
            val vm: PlaybackViewModel = viewModel()
            val initialized by vm.isInitialized.collectAsState()
            val error by vm.initError.collectAsState()
            val needsLogin by vm.needsLogin.collectAsState()

            // Auto-update check
            var updateInfo by remember { mutableStateOf<UpdateInfo?>(null) }

            val context = this@MainActivity
            LaunchedEffect(Unit) {
                AppSettings.load(context)
                // Off the main thread: opening the database and reading the index is disk work, and
                // this effect runs on the UI dispatcher. Deliberately no prune here — validating every
                // downloaded row costs one content-resolver round trip each, which would grow into a
                // stall at startup. AudioSourceResolver.localOrNull already drops a row whose file has
                // gone, at the moment it matters.
                withContext(Dispatchers.IO) {
                    Downloads.init(context)
                    DownloadFolder.load(context)
                    // A download lives in viewModelScope, so one killed with the app never cleared its
                    // own ongoing progress bar. Once per process, so a rotation mid-download keeps it.
                    DownloadNotifier.clearStaleProgress(context)
                }
                if (!initialized && error == null && !needsLogin) {
                    val savedCookies = loadCookies(context)
                    if (savedCookies != null) {
                        vm.startService(context)
                        vm.initialize(savedCookies)
                    } else {
                        vm.showLogin()
                    }
                }

                // Check for updates in background — once per process. This LaunchedEffect(Unit) re-runs
                // on every Activity recreation (e.g. config changes), which would otherwise refire the
                // network + TLS update check each time.
                if (updateChecked.compareAndSet(false, true)) {
                    withContext(Dispatchers.IO) {
                        val channel = if (AppSettings.updateChannel.value == AppSettings.CHANNEL_NIGHTLY) {
                            UpdateChannel.NIGHTLY
                        } else {
                            UpdateChannel.STABLE
                        }
                        val info = UpdateService.checkForUpdates(context, channel)
                        if (info != null) {
                            val dismissed = UpdateService.getDismissedVersion(context)
                            if (dismissed != info.latestVersion) {
                                updateInfo = info
                            }
                        }
                    }
                }
            }

            // Tapping the download notification lands on the manager rather than the last screen.
            // Keyed on the flow, not on `initialized`: the notification usually arrives while the app is
            // already open and initialized never changes again, so an effect keyed on it would not re-run.
            val openDownloads by pendingOpenDownloads.collectAsState()
            LaunchedEffect(initialized, openDownloads) {
                if (initialized && openDownloads) {
                    pendingOpenDownloads.value = false
                    vm.navigateTo(ch.snepilatch.app.data.Screen.DOWNLOADS)
                }
            }

            // Wire service controls once initialized and service is ready
            // Wait for the service to actually be up (instance published) rather than guessing with a
            // fixed delay, then wire controls and — for a headphone cold-launch started by the
            // MediaButtonReceiver with an autoplay extra — start playback. Kept as ONE effect so wiring
            // is guaranteed to finish before autoplay reaches the cold-start onReady handoff.
            LaunchedEffect(initialized) {
                if (initialized) {
                    MusicPlaybackService.serviceReady.first { it }
                    vm.wireServiceControls()
                    if (intent.getBooleanExtra(MediaButtonReceiver.EXTRA_AUTO_PLAY, false)) {
                        intent.removeExtra(MediaButtonReceiver.EXTRA_AUTO_PLAY)
                        if (!vm.playback.value.isPlaying) {
                            vm.togglePlayPause()
                        }
                    }
                }
            }

            // Handle deep links reactively (works for both initial launch and onNewIntent)
            val deepLinkUri by pendingDeepLink.collectAsState()
            LaunchedEffect(deepLinkUri, initialized) {
                if (initialized && deepLinkUri != null) {
                    val uri = deepLinkUri!!
                    pendingDeepLink.value = null
                    vm.handleDeepLink(uri)
                }
            }

            Surface(
                modifier = Modifier
                    .fillMaxSize(),
                color = SpfyBlack
            ) {
                // Update dialog
                if (updateInfo != null) {
                    UpdateDialog(
                        updateInfo = updateInfo!!,
                        onDismiss = { updateInfo = null }
                    )
                }

                when {
                    needsLogin -> SpfyLoginScreen(vm)
                    !initialized -> {
                        val cooldown by vm.rateLimitCooldown.collectAsState()
                        val seconds by vm.cooldownSeconds.collectAsState()
                        LoadingScreen(
                            error = error,
                            isRateLimited = cooldown,
                            cooldownSecondsRemaining = seconds,
                            onLogin = { if (!cooldown) vm.showLogin() },
                            onRetry = {
                                if (!cooldown) {
                                    val cookies = loadCookies(context)
                                    if (cookies != null) {
                                        vm.initError.value = null
                                        vm.initialize(cookies)
                                    }
                                }
                            }
                        )
                    }
                    else -> SpfyApp(vm)
                }
            }
        }
    }
}
