package ch.snepilatch.app.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** VALIDATED as well as INTERNET: a captive portal reports the capability without carrying traffic. */
fun hasInternet(context: Context): Boolean {
    val cm = context.getSystemService(ConnectivityManager::class.java) ?: return true
    val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}
