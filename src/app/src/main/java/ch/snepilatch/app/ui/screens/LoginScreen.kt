package ch.snepilatch.app.ui.screens

import ch.snepilatch.app.ui.theme.SpotifyWhite
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import ch.snepilatch.app.util.parseCookieString
import ch.snepilatch.app.util.saveCookies
import ch.snepilatch.app.viewmodel.SpotifyViewModel

@Composable
fun SpotifyLoginScreen(vm: SpotifyViewModel) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { vm.needsLogin.value = false }) {
                Icon(Icons.Default.Close, "Close", tint = SpotifyWhite)
            }
            Spacer(Modifier.width(8.dp))
            Text("Login to Spotify", color = SpotifyWhite, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }

        AndroidView(
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    CookieManager.getInstance().apply {
                        setAcceptCookie(true)
                        removeAllCookies(null)
                    }
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            // When we reach open.spotify.com, capture cookies
                            if (url?.contains("open.spotify.com") == true) {
                                val cookieStr = CookieManager.getInstance().getCookie(url) ?: return
                                val cookies = parseCookieString(cookieStr)
                                // Need sp_dc at minimum for session
                                if (cookies.containsKey("sp_dc")) {
                                    saveCookies(context, cookies)
                                    vm.startService(context)
                                    vm.onLoginComplete(cookies)
                                }
                            }
                        }
                    }
                    loadUrl("https://accounts.spotify.com/login?continue=https%3A%2F%2Fopen.spotify.com")
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
        )
    }
}
