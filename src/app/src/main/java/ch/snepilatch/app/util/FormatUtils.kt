package ch.snepilatch.app.util

fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

/**
 * Strips HTML tags from a string and returns plain text content.
 * Decodes HTML entities (e.g. &amp; -> &) as well.
 */
fun stripHtml(html: String): String {
    return android.text.Html.fromHtml(html, android.text.Html.FROM_HTML_MODE_COMPACT)
        .toString()
        .trim()
}
