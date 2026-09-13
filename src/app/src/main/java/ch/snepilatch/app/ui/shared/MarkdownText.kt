package ch.snepilatch.app.ui.shared

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.snepilatch.app.ui.theme.SnepilatchLightGray
import ch.snepilatch.app.ui.theme.SnepilatchWhite

/**
 * Enough markdown for a GitHub release body, and no more.
 *
 * Release notes use headings, bullets, the occasional quote, and inline bold, italic, code and
 * links. Block markers become real layout; inline markers become styled text, and links, written
 * or bare, become tappable and open the browser. A GitHub pull or issue url reads as `#794`
 * rather than the whole address, since that is what the generated "by @user in <url>" lines are
 * full of (#806).
 *
 * Lives here rather than beside the release notes screen because two screens show the same
 * bodies, and one had already gone without.
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    headingColor: Color = SnepilatchWhite,
    bodyColor: Color = SnepilatchLightGray,
    linkColor: Color = MaterialTheme.colorScheme.primary,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (line in markdown.lines()) {
            val trimmed = line.trim()
            when {
                trimmed.isEmpty() -> Spacer(Modifier.height(4.dp))
                trimmed.startsWith("### ") -> Text(
                    inlineMarkdown(trimmed.removePrefix("### "), linkColor),
                    color = headingColor, fontSize = 14.sp, fontWeight = FontWeight.SemiBold
                )
                trimmed.startsWith("## ") -> Text(
                    inlineMarkdown(trimmed.removePrefix("## "), linkColor),
                    color = headingColor, fontSize = 15.sp, fontWeight = FontWeight.Bold
                )
                trimmed.startsWith("# ") -> Text(
                    inlineMarkdown(trimmed.removePrefix("# "), linkColor),
                    color = headingColor, fontSize = 16.sp, fontWeight = FontWeight.Bold
                )
                trimmed.startsWith("- ") || trimmed.startsWith("* ") -> Text(
                    inlineMarkdown(trimmed.drop(2), linkColor, prefix = "  •  "),
                    color = bodyColor, fontSize = 14.sp, lineHeight = 20.sp
                )
                trimmed.startsWith("> ") -> Text(
                    inlineMarkdown(trimmed.removePrefix("> "), linkColor),
                    color = bodyColor.copy(alpha = 0.7f), fontSize = 13.sp,
                    modifier = Modifier.padding(start = 12.dp)
                )
                else -> Text(
                    inlineMarkdown(trimmed, linkColor),
                    color = bodyColor, fontSize = 14.sp, lineHeight = 20.sp
                )
            }
        }
    }
}

// Bold before italic on purpose: `*(.+?)*` would match the first two asterisks of `**bold**`.
// Groups: 1 bold, 2 code, 3 link label, 4 link url, 5 bare url, 6 italic.
private val INLINE = Regex("""\*\*(.+?)\*\*|`(.+?)`|\[([^\]]+)]\(([^)\s]+)\)|(https?://[^\s)]+)|\*([^*]+?)\*""")
private val GITHUB_REFERENCE = Regex("""^https://github\.com/[^/]+/[^/]+/(?:pull|issues)/(\d+)/?$""")

/**
 * One line of inline markdown as styled text with tappable links. Internal so it can be tested
 * without a Compose harness; [prefix] is appended unstyled in front, for the bullet.
 */
internal fun inlineMarkdown(text: String, linkColor: Color, prefix: String = ""): AnnotatedString = buildAnnotatedString {
    append(prefix)
    var at = 0
    for (m in INLINE.findAll(text)) {
        append(text.substring(at, m.range.first))
        val g = m.groupValues
        when {
            g[1].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(g[1]) }
            g[2].isNotEmpty() -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(g[2]) }
            g[3].isNotEmpty() -> link(g[4], g[3], linkColor)
            g[5].isNotEmpty() -> link(g[5], linkLabel(g[5]), linkColor)
            else -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(g[6]) }
        }
        at = m.range.last + 1
    }
    append(text.substring(at))
}

/** What a bare url reads as: `#794` for a pull or issue, otherwise the address without its scheme. */
internal fun linkLabel(url: String): String =
    GITHUB_REFERENCE.find(url)?.let { "#${it.groupValues[1]}" }
        ?: url.removePrefix("https://").removePrefix("http://").trimEnd('/')

private fun AnnotatedString.Builder.link(url: String, label: String, color: Color) {
    val styles = TextLinkStyles(style = SpanStyle(color = color, textDecoration = TextDecoration.Underline))
    withLink(LinkAnnotation.Url(url, styles)) { append(label) }
}

/** The text alone, markers gone; what the block renderer showed before links were kept. */
internal fun String.cleanMarkdown(): String = inlineMarkdown(this, Color.Unspecified).text
