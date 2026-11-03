package ch.snepilatch.app.logic.shared

import android.content.Context
import android.content.SharedPreferences
import ch.snepilatch.app.data.AccountInfo
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
    private const val INFO_KEY = "account_info_"
    private var prefs: SharedPreferences? = null

    val accounts = MutableStateFlow<List<SavedAccount>>(emptyList())

    fun init(context: Context) {
        prefs = context.getSharedPreferences(AppSettings.PREFS, Context.MODE_PRIVATE)
        // A start without an answer once saved an account with no name, under the real one's cookies.
        accounts.value = decodeAccounts(prefs?.getString(ACCOUNTS_KEY, null)).filter { it.username.isNotBlank() }
    }

    /** Adds or refreshes [account] by username, so a re-login or a new picture updates the row. */
    fun remember(account: SavedAccount) = persist(accounts.value.filter { it.username != account.username } + account)

    /**
     * Gives the saved account these [cookies] belong to (same sp_dc) the cookies the server last set,
     * so switching back to it later starts from them rather than from the login's.
     */
    fun updateCookies(cookies: Map<String, String>) {
        val spDc = cookies["sp_dc"] ?: return
        val list = accounts.value
        if (list.none { it.cookies["sp_dc"] == spDc && it.cookies != cookies }) return
        persist(list.map { if (it.cookies["sp_dc"] == spDc) it.copy(cookies = cookies) else it })
    }

    fun forget(username: String) {
        persist(accounts.value.filter { it.username != username })
        prefs?.edit()?.remove(INFO_KEY + username)?.apply()
    }

    /** Keeps what the account page shows, so it shows before the network answers, or without one. */
    fun rememberInfo(info: AccountInfo) {
        prefs?.edit()?.putString(INFO_KEY + info.username, encodeAccountInfo(info))?.apply()
    }

    /** What was last kept for the account these [cookies] open. */
    fun infoFor(cookies: Map<String, String>): AccountInfo? {
        val spDc = cookies["sp_dc"] ?: return null
        val username = accounts.value.firstOrNull { it.cookies["sp_dc"] == spDc }?.username ?: return null
        return decodeAccountInfo(prefs?.getString(INFO_KEY + username, null))
    }

    private fun persist(list: List<SavedAccount>) {
        accounts.value = list
        prefs?.edit()?.putString(ACCOUNTS_KEY, encodeAccounts(list))?.apply()
    }
}

fun encodeAccountInfo(info: AccountInfo): String = JSONObject()
    .put("username", info.username)
    .put("displayName", info.displayName)
    .put("isPremium", info.isPremium)
    .put("profileImageUrl", info.profileImageUrl ?: JSONObject.NULL)
    .put("userId", info.userId)
    .put("followers", info.followers)
    .put("following", info.following)
    .put("playlistCount", info.playlistCount)
    .toString()

fun decodeAccountInfo(json: String?): AccountInfo? {
    if (json.isNullOrBlank()) return null
    return runCatching {
        val o = JSONObject(json)
        AccountInfo(
            username = o.getString("username"),
            displayName = o.getString("displayName"),
            isPremium = o.getBoolean("isPremium"),
            profileImageUrl = if (o.isNull("profileImageUrl")) null else o.getString("profileImageUrl"),
            userId = o.getString("userId"),
            followers = o.getInt("followers"),
            following = o.getInt("following"),
            playlistCount = o.getInt("playlistCount"),
        )
    }.getOrNull()
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
