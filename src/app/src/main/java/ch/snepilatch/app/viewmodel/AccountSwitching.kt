package ch.snepilatch.app.viewmodel

import android.content.Context
import androidx.lifecycle.viewModelScope
import ch.snepilatch.app.data.Screen
import ch.snepilatch.app.logic.shared.AccountStore
import ch.snepilatch.app.logic.shared.SavedAccount
import ch.snepilatch.app.logic.shared.SessionHolder
import ch.snepilatch.app.logic.shared.clearCookies
import ch.snepilatch.app.logic.shared.saveCookies
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Moving between signed-in accounts (#847). Every path first shuts the running session down
 * completely, so the next one is built from its own cookies rather than adopted from the holder,
 * which is what a plain cookie swap used to do.
 */

/** Signs [account] in: its cookies become the active slot and the session is rebuilt from them. */
fun PlaybackViewModel.switchAccount(context: Context, account: SavedAccount): Job? {
    if (account.username == SessionHolder.username) return null
    return viewModelScope.launch {
        saveCookies(context, account.cookies)
        shutDownSession()
        navigateToTab(Screen.HOME)
        initialize(account.cookies)
    }
}

/**
 * Shows the login for another account. The running session is left alone, so backing out of the
 * login returns to the account that is already signed in; the finished login tears it down itself.
 */
fun PlaybackViewModel.addAccount() = showLogin()

/**
 * Signs the current account out and forgets it. With another account still saved the app switches
 * to it rather than dropping to the login screen, which is what leaving one of several accounts
 * means; the last one out lands on the login.
 */
fun PlaybackViewModel.logout(context: Context): Job? {
    val username = SessionHolder.username
    val next = AccountStore.accounts.value.firstOrNull { it.username != username }
    AccountStore.forget(username)
    if (next != null) return switchAccount(context, next)
    return viewModelScope.launch {
        clearCookies(context)
        shutDownSession()
        showLogin()
    }
}
