package ch.snepilatch.app.ui.screens

import ch.snepilatch.app.R
import ch.snepilatch.app.ui.theme.SpfyBlack
import ch.snepilatch.app.ui.theme.SpfyWhite
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
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
import androidx.compose.ui.viewinterop.AndroidView
import ch.snepilatch.app.util.LokiLogger
import ch.snepilatch.app.util.parseCookieString
import ch.snepilatch.app.util.saveCookies
import ch.snepilatch.app.viewmodel.PlaybackViewModel

@Composable
fun SpfyLoginScreen(vm: PlaybackViewModel) {
    Column(
        Modifier
            .fillMaxSize()
            .background(SpfyBlack)
            .statusBarsPadding()
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { vm.needsLogin.value = false }) {
                Icon(Icons.Rounded.Close, stringResource(R.string.close), tint = SpfyWhite)
            }
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.login_title), color = SpfyWhite, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }

        AndroidView(
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    // Google's OAuth refuses embedded WebViews ("disallowed_useragent",
                    // error 403) — it detects the "; wv" token Android appends to the
                    // WebView UA. Strip it so "Continue with Google" is allowed through.
                    settings.userAgentString = settings.userAgentString.replace("; wv", "")
                    val cookieManager = CookieManager.getInstance()
                    cookieManager.setAcceptCookie(true)
                    cookieManager.setAcceptThirdPartyCookies(this, true)
                    // A blank login page reports nothing on its own: the form is script-built, so a
                    // failure there leaves an empty view and no error anywhere. Surface what the page
                    // says about itself instead of guessing at it from a screenshot.
                    webChromeClient = object : WebChromeClient() {
                        override fun onConsoleMessage(message: ConsoleMessage?): Boolean {
                            if (message != null) {
                                LokiLogger.i(
                                    "Login",
                                    "console ${message.messageLevel()}: ${message.message()}" +
                                        " (${message.sourceId()}:${message.lineNumber()})"
                                )
                            }
                            return true
                        }
                    }
                    webViewClient = object : WebViewClient() {
                        override fun onReceivedError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            error: WebResourceError?,
                        ) {
                            super.onReceivedError(view, request, error)
                            LokiLogger.w(
                                "Login",
                                "load error ${error?.errorCode} ${error?.description}" +
                                    " for ${request?.url} main=${request?.isForMainFrame}"
                            )
                        }

                        override fun onReceivedHttpError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            response: WebResourceResponse?,
                        ) {
                            super.onReceivedHttpError(view, request, response)
                            LokiLogger.w(
                                "Login",
                                "http ${response?.statusCode} for ${request?.url}" +
                                    " main=${request?.isForMainFrame}"
                            )
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            LokiLogger.i("Login", "page finished: $url")
                            // The page hangs its layout off an absolutely positioned <main> whose
                            // height resolves against a containing block that collapses to nothing
                            // here, leaving it 48px tall with overflow:auto — so it clips a correctly
                            // laid out 699px form down to a 48px window and the screen reads as blank.
                            // Laying it out in normal flow instead is what puts the form back.
                            view?.evaluateJavascript(
                                "(function(){var s=document.createElement('style');" +
                                    "s.textContent='html,body{height:auto!important;" +
                                    "min-height:100%!important}" +
                                    "main{position:static!important;height:auto!important;" +
                                    "min-height:100vh!important;overflow:visible!important}';" +
                                    "document.head.appendChild(s)})()"
                            ) { }

                            view?.evaluateJavascript(
                                "(function(){var b=document.body,d=document.documentElement," +
                                    "cs=b?getComputedStyle(b):null,ds=d?getComputedStyle(d):null;" +
                                    "return JSON.stringify({" +
                                    "html:b?b.innerHTML.length:-1," +
                                    "inputs:document.querySelectorAll('input').length," +
                                    "bodyH:b?b.getBoundingClientRect().height:-1," +
                                    "bodyW:b?b.getBoundingClientRect().width:-1," +
                                    "docH:d?d.getBoundingClientRect().height:-1," +
                                    "innerH:window.innerHeight,innerW:window.innerWidth," +
                                    "bodyCss:cs?cs.height+'/'+cs.display+'/'+cs.position:''," +
                                    "docCss:ds?ds.height+'/'+ds.display:''," +
                                    "kids:b?b.children.length:-1," +
                                    "sized:Array.from(document.querySelectorAll('body *'))" +
                                    ".filter(function(e){var r=e.getBoundingClientRect();" +
                                    "return r.height>0&&r.width>0}).length," +
                                    "input:(function(){var i=document.querySelector('input');" +
                                    "if(!i)return 'none';var r=i.getBoundingClientRect();" +
                                    "var s=getComputedStyle(i);return r.x+','+r.y+' '+r.width+'x'+r.height" +
                                    "+' '+s.display+'/'+s.visibility+'/'+s.opacity})()})})()"
                            ) { result -> LokiLogger.i("Login", "dom $result") }
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
                    // removeAllCookies is asynchronous. Loading before it lands wipes the cookies the
                    // login page sets for itself while bootstrapping, so the load waits for it.
                    cookieManager.removeAllCookies {
                        cookieManager.flush()
                        loadUrl("https://accounts.spotify.com/login?continue=https%3A%2F%2Fopen.spotify.com")
                    }
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
        )
    }
}
