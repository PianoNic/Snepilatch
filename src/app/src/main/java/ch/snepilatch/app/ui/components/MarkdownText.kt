package ch.snepilatch.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.snepilatch.app.ui.theme.SpfyLightGray
import ch.snepilatch.app.ui.theme.SpfyWhite

/**
 * Enough markdown for a GitHub release body, and no more.
 *
 * Release notes use headings, bullets, the occasional quote, and inline bold, links
 * and code. Anything printed into a plain `Text` shows its syntax, which is what a
 * changelog full of `**bold**` and `[text](url)` looked like before.
 *
 * Block markers become real layout. Inline markers are stripped rather than styled:
 * the alternative is building an `AnnotatedString` per line, and nothing in a
 * changelog reads worse for losing its bold. Add that when something needs it.
 *
 * Lives here rather than beside the release notes screen because two screens show
 * the same bodies, and one had already gone without.
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    headingColor: Color = SpfyWhite,
    bodyColor: Color = SpfyLightGray,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (line in markdown.lines()) {
            val trimmed = line.trim()
            when {
                trimmed.isEmpty() -> Spacer(Modifier.height(4.dp))
                trimmed.startsWith("### ") -> Text(
                    trimmed.removePrefix("### ").cleanMarkdown(),
                    color = headingColor, fontSize = 14.sp, fontWeight = FontWeight.SemiBold
                )
                trimmed.startsWith("## ") -> Text(
                    trimmed.removePrefix("## ").cleanMarkdown(),
                    color = headingColor, fontSize = 15.sp, fontWeight = FontWeight.Bold
                )
                trimmed.startsWith("# ") -> Text(
                    trimmed.removePrefix("# ").cleanMarkdown(),
                    color = headingColor, fontSize = 16.sp, fontWeight = FontWeight.Bold
                )
                trimmed.startsWith("- ") || trimmed.startsWith("* ") -> Text(
                    "  •  ${trimmed.drop(2).cleanMarkdown()}",
                    color = bodyColor, fontSize = 14.sp, lineHeight = 20.sp
                )
                trimmed.startsWith("> ") -> Text(
                    trimmed.removePrefix("> ").cleanMarkdown(),
                    color = bodyColor.copy(alpha = 0.7f), fontSize = 13.sp,
                    modifier = Modifier.padding(start = 12.dp)
                )
                else -> Text(
                    trimmed.cleanMarkdown(),
                    color = bodyColor, fontSize = 14.sp, lineHeight = 20.sp
                )
            }
        }
    }
}

/**
 * Strip inline markers, leaving the text they wrapped.
 *
 * Bold runs before italic on purpose: `*(.+?)*` matches the first two asterisks of
 * `**bold**`, so the other order turns it into `*bold*`.
 *
 * Internal so it can be tested without a Compose harness.
 */
internal fun String.cleanMarkdown(): String = this
    // Bold before italic: see the KDoc above.
    .replace(Regex("""\*\*(.+?)\*\*"""), "$1")
    .replace(Regex("""\*(.+?)\*"""), "$1")
    // Links keep their label and lose the url.
    .replace(Regex("""\[(.+?)]\((.+?)\)"""), "$1")
    .replace(Regex("""`(.+?)`"""), "$1")
