package ch.snepilatch.app.viewmodel

import java.io.IOException

/** The token request's refusal, which a retry usually clears; Kotify's getSessionWithRetry has the story. */
internal fun isTokenRefusal(message: String?): Boolean =
    message != null && (message.contains("Unauthorized") || message.contains("401") || message.contains("code\":400"))

/**
 * Whether a failed start should open the app on its downloads and keep trying behind them, rather than a
 * retry screen: nothing to reach the server over, a request that got no answer, or a refusal while there is
 * something to play. With nothing downloaded a refusal keeps its screen, as the app would have nothing to show.
 */
internal fun startsOffline(error: Throwable, noRoute: Boolean, hasDownloads: Boolean): Boolean =
    noRoute || error is IOException || (hasDownloads && isTokenRefusal(error.message))
