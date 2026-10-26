package ch.snepilatch.app.ui.shared

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.snepilatch.app.R
import ch.snepilatch.app.logic.shared.LoadError
import ch.snepilatch.app.ui.screens.LocalBottomOverlayHeight
import ch.snepilatch.app.ui.theme.SnepilatchLightGray
import ch.snepilatch.app.ui.theme.SnepilatchWhite

/** A title, an optional line under it and an optional action, centred where a page's content would be. */
@Composable
fun CenteredMessage(title: String, message: String?, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    // Centred in what is visible: the mini player and the bottom bar cover the foot of the page.
    Column(
        modifier.fillMaxSize().padding(bottom = LocalBottomOverlayHeight.current.value).padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, color = SnepilatchWhite, fontSize = 16.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        if (message != null) {
            Text(message, color = SnepilatchLightGray, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
        }
        action?.invoke()
    }
}

/**
 * What a page shows in place of content it failed to load, instead of an empty page. A failure worth
 * retrying offers "Try again"; a 404 says the thing is gone, as the web player's error page does, since
 * retrying would not bring it back. [notFoundTitle] names what was not found.
 */
@Composable
fun LoadErrorState(
    error: LoadError,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    @StringRes notFoundTitle: Int = R.string.load_error_not_found_page,
) {
    when (error) {
        LoadError.NOT_FOUND -> CenteredMessage(
            stringResource(notFoundTitle),
            stringResource(R.string.load_error_not_found_body),
            modifier,
        )
        LoadError.GENERIC -> CenteredMessage(
            stringResource(R.string.load_error_title),
            stringResource(R.string.load_error_body),
            modifier,
        ) {
            TextButton(onClick = onRetry) {
                Text(stringResource(R.string.load_error_try_again), color = SnepilatchWhite)
            }
        }
    }
}
