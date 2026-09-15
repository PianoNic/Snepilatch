package ch.snepilatch.app.logic.shared

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** One signed-in account: who it is, how the account tab shows it, and the cookies that open its session. */
data class SavedAccount(val username: String, val displayName: String, val imageUrl: String?, val cookies: Map<String, String>)

/**
 * The accounts the app has signed into (#847), kept in the same prefs file as the active cookie
 * slot. [accounts] is what the account tab lists; the active account is whichever cookies sit in
 * the slot [loadCookies] reads at start. Process scoped like [AppSettings]; a call before [init]
 * changes nothing, which is what the unit tests rely on.
 */
object AccountStore {
    private const val ACCOUNTS_KEY = "accounts"
    private var prefs: SharedPreferences? = null

    val accounts = MutableStateFlow<List<SavedAccount>>(emptyList())

    fun init(context: Context) {
        prefs = context.getSharedPreferences(AppSettings.PREFS, Context.MODE_PRIVATE)
        accounts.value = decodeAccounts(prefs?.getString(ACCOUNTS_KEY, null))
    }

    /** Adds or refreshes [account] by username, so a re-login or a new picture updates the row. */
    fun remember(account: SavedAccount) = persist(accounts.value.filter { it.username != account.username } + account)

    fun forget(username: String) = persist(accounts.value.filter { it.username != username })

    private fun persist(list: List<SavedAccount>) {
        accounts.value = list
        prefs?.edit()?.putString(ACCOUNTS_KEY, encodeAccounts(list))?.apply()
    }
}

fun encodeAccounts(accounts: List<SavedAccount>): String = JSONArray().also { arr ->
    accounts.forEach { a ->
        arr.put(
            JSONObject()
                .put("username", a.username)
                .put("displayName", a.displayName)
                .put("imageUrl", a.imageUrl ?: JSONObject.NULL)
                .put("cookies", JSONObject(a.cookies)),
        )
    }
}.toString()

fun decodeAccounts(json: String?): List<SavedAccount> {
    if (json.isNullOrBlank()) return emptyList()
    return try {
        val arr = JSONArray(json)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val cookies = o.getJSONObject("cookies")
            SavedAccount(
                username = o.getString("username"),
                displayName = o.optString("displayName"),
                imageUrl = o.optString("imageUrl").takeIf { it.isNotEmpty() && !o.isNull("imageUrl") },
                cookies = cookies.keys().asSequence().associateWith { cookies.getString(it) },
            )
        }
    } catch (_: Exception) { emptyList() }
}
