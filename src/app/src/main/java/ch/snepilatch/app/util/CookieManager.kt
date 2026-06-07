package ch.snepilatch.app.util

import android.content.Context
import org.json.JSONObject

private const val PREFS_NAME = "kotify_prefs"
private const val COOKIES_KEY = "spotify_cookies"

fun parseCookieString(cookieStr: String): Map<String, String> {
    return cookieStr.split(";").associate { part ->
        val (key, value) = part.trim().split("=", limit = 2)
        key.trim() to value.trim()
    }
}

fun saveCookies(context: Context, cookies: Map<String, String>) {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val json = JSONObject(cookies).toString()
    prefs.edit().putString(COOKIES_KEY, json).apply()
}

fun loadCookies(context: Context): Map<String, String>? {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val json = prefs.getString(COOKIES_KEY, null) ?: return null
    return try {
        val obj = JSONObject(json)
        obj.keys().asSequence().associateWith { obj.getString(it) }
    } catch (_: Exception) { null }
}

fun clearCookies(context: Context) {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit().remove(COOKIES_KEY).apply()
}
