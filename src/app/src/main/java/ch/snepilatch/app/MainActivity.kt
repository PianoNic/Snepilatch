package ch.snepilatch.app

import android.Manifest
import android.content.pm.PackageManager
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
import ch.snepilatch.app.ui.screens.LoadingScreen
import ch.snepilatch.app.ui.screens.SpotifyApp
import ch.snepilatch.app.ui.screens.SpotifyLoginScreen
import ch.snepilatch.app.ui.theme.SpotifyBlack
import ch.snepilatch.app.util.loadCookies
import ch.snepilatch.app.viewmodel.SpotifyViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
            val vm: SpotifyViewModel = viewModel()
            val initialized by vm.isInitialized.collectAsState()
            val error by vm.initError.collectAsState()
            val needsLogin by vm.needsLogin.collectAsState()

            val context = this@MainActivity
            LaunchedEffect(Unit) {
                vm.loadPreferences(context)
                if (!initialized && error == null && !needsLogin) {
                    val savedCookies = loadCookies(context)
                    if (savedCookies != null) {
                        vm.startService(context)
                        vm.initialize(savedCookies)
                    } else {
                        vm.showLogin()
                    }
                }
            }

            // Wire service controls once initialized and service is ready
            LaunchedEffect(initialized) {
                if (initialized) {
                    kotlinx.coroutines.delay(500)
                    vm.wireServiceControls()
                }
            }

            Surface(
                modifier = Modifier
                    .fillMaxSize(),
                color = SpotifyBlack
            ) {
                when {
                    needsLogin -> SpotifyLoginScreen(vm)
                    !initialized -> LoadingScreen(
                        error = error,
                        onLogin = { vm.showLogin() },
                        onRetry = {
                            val cookies = loadCookies(context)
                            if (cookies != null) {
                                vm.initError.value = null
                                vm.initialize(cookies)
                            }
                        }
                    )
                    else -> SpotifyApp(vm)
                }
            }
        }
    }
}
